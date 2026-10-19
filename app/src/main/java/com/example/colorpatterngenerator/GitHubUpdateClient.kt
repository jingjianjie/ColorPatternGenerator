package com.example.colorpatterngenerator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GitHubRelease(
    val displayVersion: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String? = null
)

/** Downloads public releases without credentials; the installer also verifies APK identity. */
class GitHubUpdateClient(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    suspend fun checkForUpdate(currentVersion: String): GitHubRelease? = withContext(Dispatchers.IO) {
        val installedVersion = ReleaseVersion.parseOrNull(currentVersion)
            ?: throw IOException("The installed app has an unsupported version: $currentVersion.")
        val connection = openGet(URL(LATEST_RELEASE_URL), "application/vnd.github+json")
        val metadata = try {
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_METADATA_BYTES) {
                        throw IOException("GitHub returned too much release information.")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            bytes.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
        currentCoroutineContext().ensureActive()
        try {
            val json = JSONObject(metadata)
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) {
                throw IOException("No stable GitHub release is available yet.")
            }
            val tag = json.getString("tag_name")
            val latestVersion = ReleaseVersion.parseOrNull(tag)
                ?: throw IOException("The GitHub release tag '$tag' is not a supported stable version.")
            if (latestVersion <= installedVersion) return@withContext null

            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_ASSET_NAME }
                ?: throw IOException("Release $tag does not include $APK_ASSET_NAME yet.")
            if (apk.optString("state") != "uploaded") {
                throw IOException("The APK for release $tag has not finished uploading.")
            }
            val size = apk.getLong("size")
            validateSize(size)
            val downloadUrl = apk.getString("browser_download_url")
            validateDownloadUrl(downloadUrl)
            val digest = if (apk.isNull("digest")) null else apk.optString("digest")
            val sha256 = when {
                digest.isNullOrEmpty() -> null
                digest.matches(Regex("sha256:[0-9a-fA-F]{64}")) -> digest.substringAfter(':')
                else -> throw IOException("GitHub provided an unsupported APK checksum.")
            }
            GitHubRelease(tag, downloadUrl, size, sha256)
        } catch (exception: JSONException) {
            throw IOException("GitHub returned incomplete release information.", exception)
        }
    }

    /** The progress callback runs on the IO dispatcher; callers marshal UI work themselves. */
    suspend fun download(
        release: GitHubRelease,
        directory: File,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        validateSize(release.size)
        val url = validateDownloadUrl(release.downloadUrl)
        if (release.sha256 != null && !release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IOException("The APK checksum is invalid.")
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create the update download folder.")
        }
        val partial = File.createTempFile("update-", ".part", directory)
        try {
            val connection = openGet(url, "application/octet-stream")
            try {
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0 && contentLength != release.size) {
                    throw IOException("The APK download size does not match the GitHub release.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = 0
                onProgress(0)
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.size || received > MAX_APK_BYTES) {
                                throw IOException("The APK download is larger than expected.")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val progress = (received * 100 / release.size).toInt().coerceAtMost(99)
                            if (progress != lastProgress) {
                                onProgress(progress)
                                lastProgress = progress
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (received != release.size) {
                    throw IOException("The APK download was incomplete. Please try again.")
                }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (release.sha256 != null && !actualDigest.equals(release.sha256, ignoreCase = true)) {
                    throw IOException("The APK checksum does not match GitHub. Please try again.")
                }
                val destination = File(directory, "update.apk")
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous update download.")
                }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(destination)) {
                    throw IOException("Unable to save the downloaded update.")
                }
                onProgress(100)
                destination
            } finally {
                connection.disconnect()
            }
        } finally {
            partial.delete()
        }
    }

    private suspend fun openGet(initialUrl: URL, accept: String): HttpURLConnection {
        var url = initialUrl
        for (redirects in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            requireHttps(url)
            val connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "ColorPatternGenerator-Android-Updater")
            if (url.host.equals("api.github.com", ignoreCase = true)) {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                when (status) {
                    HttpURLConnection.HTTP_OK -> return connection
                    301, 302, 303, 307, 308 -> {
                        if (redirects == MAX_REDIRECTS) {
                            throw IOException("GitHub redirected the download too many times.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("GitHub returned an invalid redirect.")
                        url = URL(url, location)
                        requireHttps(url)
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> throw IOException(
                        if (initialUrl.toString() == LATEST_RELEASE_URL) {
                            "No published GitHub release is available yet."
                        } else {
                            "The release APK is no longer available on GitHub."
                        }
                    )
                    403, 429 -> throw IOException("GitHub temporarily refused the request. Please try again later.")
                    else -> throw IOException("GitHub request failed (HTTP $status). Please try again.")
                }
            } catch (exception: Throwable) {
                connection.disconnect()
                throw exception
            }
            connection.disconnect()
        }
        throw IOException("GitHub redirected the download too many times.")
    }

    private fun validateDownloadUrl(value: String): URL {
        val url = URL(value)
        requireHttps(url)
        if (!url.host.equals("github.com", ignoreCase = true) ||
            !url.path.startsWith("/jingjianjie/ColorPatternGenerator/releases/download/") ||
            url.path.substringAfter("/releases/download/").split('/').size != 2 ||
            !url.path.endsWith("/$APK_ASSET_NAME") ||
            url.query != null || url.ref != null
        ) {
            throw IOException("The APK download is not from the expected GitHub repository.")
        }
        return url
    }

    private fun requireHttps(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null ||
            (url.port != -1 && url.port != 443)
        ) {
            throw IOException("GitHub updates require a secure HTTPS connection.")
        }
    }

    private fun validateSize(size: Long) {
        if (size <= 0 || size > MAX_APK_BYTES) {
            throw IOException("The release APK is empty or exceeds the 100 MB download limit.")
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/jingjianjie/ColorPatternGenerator/releases/latest"
        const val APK_ASSET_NAME = "colorpatterngenerator_pg.apk"
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        const val MAX_REDIRECTS = 5
    }
}
