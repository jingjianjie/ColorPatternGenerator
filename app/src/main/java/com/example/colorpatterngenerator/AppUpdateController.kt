package com.example.colorpatterngenerator

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Owns the user-initiated update flow, separate from pattern/network controls. */
class AppUpdateController(
    private val activity: AppCompatActivity,
    private val button: Button,
    private val status: TextView,
    savedInstanceState: Bundle?,
    private val canInteract: () -> Boolean
) : DefaultLifecycleObserver {
    private val client = GitHubUpdateClient()
    private val currentVersion = activity.packageManager
        .getPackageInfo(activity.packageName, 0).versionName ?: "0"
    private val updateDirectory = File(activity.cacheDir, "updates")
    private var availableRelease: GitHubRelease? = null
    private var pendingApk: File? = if (savedInstanceState?.getBoolean(STATE_PENDING) == true) {
        File(updateDirectory, "update.apk").takeIf { it.isFile }
    } else null
    private var job: Job? = null
    private var busy = false
    private var permissionDialog: AlertDialog? = null

    private val installPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            if (canInteract()) installPendingUpdate()
        } else {
            status.setText(R.string.update_install_permission_denied)
        }
        refreshButton()
    }

    init {
        activity.lifecycle.addObserver(this)
        status.text = if (pendingApk != null) activity.getString(R.string.update_ready)
        else activity.getString(R.string.update_current_version, currentVersion)
        button.setOnClickListener {
            if (!canInteract() || busy) return@setOnClickListener
            when {
                pendingApk != null -> installPendingUpdate()
                availableRelease != null -> downloadUpdate(availableRelease!!)
                else -> checkForUpdate()
            }
        }
        refreshButton()
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING, pendingApk != null)
    }

    fun refreshButton() {
        val interactive = canInteract()
        if (!interactive) permissionDialog?.dismiss()
        button.isEnabled = !busy && interactive
        button.setText(when {
            busy -> R.string.update_working
            pendingApk != null -> R.string.update_install
            availableRelease != null -> R.string.update_download
            else -> R.string.update_check
        })
    }

    private fun checkForUpdate() {
        busy = true
        refreshButton()
        status.setText(R.string.update_checking)
        job = activity.lifecycleScope.launch {
            try {
                availableRelease = client.checkForUpdate(currentVersion)
                status.text = availableRelease?.let {
                    activity.getString(R.string.update_available, it.displayVersion, currentVersion)
                } ?: activity.getString(R.string.update_up_to_date, currentVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            } finally {
                busy = false
                refreshButton()
            }
        }
    }

    private fun downloadUpdate(release: GitHubRelease) {
        busy = true
        refreshButton()
        status.text = activity.getString(R.string.update_downloading, 0)
        job = activity.lifecycleScope.launch {
            var downloaded: File? = null
            try {
                val apk = client.download(release, updateDirectory) { progress ->
                    activity.runOnUiThread {
                        if (!activity.isDestroyed) {
                            status.text = activity.getString(R.string.update_downloading, progress)
                        }
                    }
                }
                downloaded = apk
                withContext(Dispatchers.IO) { validateApk(apk) }
                pendingApk = apk
                status.setText(R.string.update_ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloaded?.delete()
                showError(e)
            } finally {
                busy = false
                refreshButton()
            }
        }
    }

    private fun installPendingUpdate() {
        val apk = pendingApk ?: return
        if (busy) return
        busy = true
        refreshButton()
        job = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { validateApk(apk) }
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !canInteract()) {
                    status.setText(R.string.update_ready)
                    return@launch
                }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    showInstallPermissionDialog()
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("App update", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    status.setText(R.string.update_installer_opened)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                apk.delete()
                pendingApk = null
                availableRelease = null
                showError(e)
            } catch (e: Exception) {
                showError(e)
            } finally {
                busy = false
                refreshButton()
            }
        }
    }

    private fun showInstallPermissionDialog() {
        permissionDialog?.dismiss()
        permissionDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                if (canInteract()) {
                    try {
                        installPermissionLauncher.launch(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        ))
                    } catch (e: Exception) {
                        showError(e)
                    }
                }
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun validateApk(apk: File) {
        if (!apk.isFile) throw IOException("The downloaded update is missing. Please check again.")
        val manager = activity.packageManager
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IOException("The download is not a valid Android APK.")
        val installed = manager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        if (candidate.packageName != activity.packageName) {
            throw IOException("This APK belongs to a different app.")
        }
        if (PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw IOException("This APK is not newer than the installed app.")
        }
        if ((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > android.os.Build.VERSION.SDK_INT) {
            throw IOException("This update requires a newer Android version.")
        }
        val installedSignatures = installed.signatures?.toSet().orEmpty()
        if (installedSignatures.isEmpty() || candidate.signatures?.toSet() != installedSignatures) {
            throw IOException("This APK was signed with a different key and cannot update this app.")
        }
    }

    private fun showError(error: Exception) {
        FileLogger.e("Update", "Update failed", error)
        status.text = activity.getString(R.string.update_error, error.message ?: "Please try again.")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        job?.cancel()
        permissionDialog?.dismiss()
    }

    companion object {
        private const val STATE_PENDING = "update_pending_apk"
    }
}
