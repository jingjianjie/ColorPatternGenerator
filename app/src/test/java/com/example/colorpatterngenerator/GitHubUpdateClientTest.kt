package com.example.colorpatterngenerator

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GitHubUpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedDownloadProducesOnlyTheCompleteApk() {
        val directory = temporaryFolder.newFolder("updates")
        val connection = FakeConnection(URL(APK_URL), body = PAYLOAD)
        val client = GitHubUpdateClient { connection }
        val progress = mutableListOf<Int>()

        val result = download(client, release(), directory, progress::add)

        assertEquals(File(directory, "update.apk"), result)
        assertArrayEquals(PAYLOAD, result.readBytes())
        assertEquals(listOf("update.apk"), directory.list()!!.toList())
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
        assertTrue(connection.disconnected)
        assertTrue(connection.streamClosed)
    }

    @Test
    fun rejectsAnIncorrectContentLengthBeforeReadingTheBody() {
        val directory = temporaryFolder.newFolder("updates")
        val connection = FakeConnection(URL(APK_URL), body = PAYLOAD, declaredLength = PAYLOAD.size + 1L)

        val error = assertThrows(IOException::class.java) {
            download(GitHubUpdateClient { connection }, release(), directory)
        }

        assertTrue(error.message!!.contains("size does not match"))
        assertFalse(connection.streamOpened)
        assertTrue(connection.disconnected)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun deletesATruncatedDownloadEvenWhenTheHeaderClaimsTheExpectedSize() {
        val directory = temporaryFolder.newFolder("updates")
        val connection = FakeConnection(
            URL(APK_URL), body = PAYLOAD.copyOf(5), declaredLength = PAYLOAD.size.toLong()
        )

        val error = assertThrows(IOException::class.java) {
            download(GitHubUpdateClient { connection }, release(), directory)
        }

        assertTrue(error.message!!.contains("incomplete"))
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun rejectsAnOversizedStreamWithoutAContentLengthHeader() {
        val directory = temporaryFolder.newFolder("updates")
        val connection = FakeConnection(URL(APK_URL), body = PAYLOAD + byteArrayOf(1), declaredLength = -1)

        val error = assertThrows(IOException::class.java) {
            download(GitHubUpdateClient { connection }, release(), directory)
        }

        assertTrue(error.message!!.contains("larger than expected"))
        assertTrue(connection.disconnected)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun checksumMismatchDeletesThePartialFileAndPreservesThePreviousApk() {
        val directory = temporaryFolder.newFolder("updates")
        val previousApk = File(directory, "update.apk").apply { writeText("previous verified APK") }
        val connection = FakeConnection(URL(APK_URL), body = PAYLOAD)
        val progress = mutableListOf<Int>()

        val error = assertThrows(IOException::class.java) {
            download(
                GitHubUpdateClient { connection }, release().copy(sha256 = "0".repeat(64)),
                directory, progress::add
            )
        }

        assertTrue(error.message!!.contains("checksum does not match"))
        assertFalse(progress.contains(100))
        assertEquals("previous verified APK", previousApk.readText())
        assertEquals(listOf("update.apk"), directory.list()!!.toList())
        assertTrue(connection.disconnected)
    }

    @Test
    fun followsTheGithubHttpsAssetRedirectAndVerifiesItsContent() {
        val directory = temporaryFolder.newFolder("updates")
        val assetUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/123/apk"
        val redirect = FakeConnection(URL(APK_URL), responseStatus = 302, location = assetUrl)
        val asset = FakeConnection(URL(assetUrl), body = PAYLOAD)
        val requestedUrls = mutableListOf<String>()
        val client = GitHubUpdateClient { url ->
            requestedUrls.add(url.toString())
            when (url.toString()) {
                APK_URL -> redirect
                assetUrl -> asset
                else -> error("Unexpected connection to $url")
            }
        }

        val result = download(client, release(), directory)

        assertArrayEquals(PAYLOAD, result.readBytes())
        assertEquals(listOf(APK_URL, assetUrl), requestedUrls)
        assertTrue(redirect.disconnected)
        assertTrue(asset.disconnected)
    }

    @Test
    fun rejectsAnHttpDowngradeBeforeConnectingToIt() {
        val directory = temporaryFolder.newFolder("updates")
        val redirect = FakeConnection(
            URL(APK_URL), responseStatus = 302, location = "http://release-assets.githubusercontent.com/apk"
        )
        val requestedUrls = mutableListOf<String>()
        val client = GitHubUpdateClient { url ->
            requestedUrls.add(url.toString())
            redirect
        }

        val error = assertThrows(IOException::class.java) { download(client, release(), directory) }

        assertTrue(error.message!!.contains("secure HTTPS"))
        assertEquals(listOf(APK_URL), requestedUrls)
        assertTrue(redirect.disconnected)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun stopsRedirectLoopsAndCleansTheDownload() {
        val directory = temporaryFolder.newFolder("updates")
        val connections = mutableListOf<FakeConnection>()
        val client = GitHubUpdateClient { url ->
            FakeConnection(url, responseStatus = 307, location = APK_URL).also(connections::add)
        }

        val error = assertThrows(IOException::class.java) { download(client, release(), directory) }

        assertTrue(error.message!!.contains("too many times"))
        assertEquals(6, connections.size)
        assertTrue(connections.all { it.disconnected })
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun cancellationDuringStreamingClosesTheConnectionAndDeletesPartialBytes() = runBlocking {
        val directory = temporaryFolder.newFolder("updates")
        val payload = ByteArray(20_000) { it.toByte() }
        val connection = FakeConnection(URL(APK_URL), body = payload)
        val client = GitHubUpdateClient { connection }
        var receivedProgress = false
        val job = launch {
            client.download(release(payload), directory) { progress ->
                if (progress > 0) {
                    receivedProgress = true
                    cancel("Cancel the update after receiving bytes")
                }
            }
        }
        job.join()

        assertTrue(receivedProgress)
        assertTrue(job.isCancelled)
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun rejectsAnApkFromAnotherRepositoryBeforeOpeningAConnection() {
        val directory = temporaryFolder.newFolder("updates")
        var openedConnection = false
        val client = GitHubUpdateClient {
            openedConnection = true
            error("An untrusted repository must not be contacted")
        }
        val otherRelease = release().copy(downloadUrl = APK_URL.replace("jingjianjie", "another-owner"))

        val error = assertThrows(IOException::class.java) { download(client, otherRelease, directory) }

        assertTrue(error.message!!.contains("expected GitHub repository"))
        assertFalse(openedConnection)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun missingPublishedReleaseReportsAnErrorInsteadOfBeingUpToDate() {
        val connection = FakeConnection(URL(LATEST_RELEASE_URL), responseStatus = 404)
        val client = GitHubUpdateClient { connection }

        val error = assertThrows(IOException::class.java) {
            runBlocking { client.checkForUpdate("1.1") }
        }

        assertEquals("No published GitHub release is available yet.", error.message)
        assertTrue(connection.disconnected)
    }

    private fun download(
        client: GitHubUpdateClient,
        release: GitHubRelease,
        directory: File,
        onProgress: (Int) -> Unit = {}
    ): File = runBlocking { client.download(release, directory, onProgress) }

    private fun release(payload: ByteArray = PAYLOAD): GitHubRelease = GitHubRelease(
        displayVersion = "v1.2",
        downloadUrl = APK_URL,
        size = payload.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    )

    private class FakeConnection(
        url: URL,
        private val body: ByteArray = byteArrayOf(),
        private val responseStatus: Int = 200,
        private val declaredLength: Long = body.size.toLong(),
        private val location: String? = null
    ) : HttpURLConnection(url) {
        var disconnected = false
        var streamOpened = false
        var streamClosed = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = responseStatus
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getInputStream(): InputStream {
            streamOpened = true
            return object : ByteArrayInputStream(body) {
                override fun close() {
                    streamClosed = true
                    super.close()
                }
            }
        }
    }

    private companion object {
        const val APK_URL =
            "https://github.com/jingjianjie/ColorPatternGenerator/releases/download/v1.2/colorpatterngenerator_pg.apk"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/jingjianjie/ColorPatternGenerator/releases/latest"
        val PAYLOAD = "APK bytes used to exercise transport integrity".toByteArray()
    }
}
