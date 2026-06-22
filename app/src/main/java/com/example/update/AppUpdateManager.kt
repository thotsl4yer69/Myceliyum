package com.example.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File

/**
 * Handles the side-effecting half of the update flow: downloading the APK with
 * the system [DownloadManager] and handing it to the package installer.
 *
 * Sideloaded debug builds can't update through the Play Store, so this performs
 * a classic "fetch APK → ACTION_VIEW install" sequence. On Android O+ the user
 * must have granted "install unknown apps" to this app; [canInstallPackages]
 * reports that and [openInstallPermissionSettings] sends them to grant it.
 */
class AppUpdateManager(private val context: Context) {

    /** Whether the OS will let this app launch an APK install right now. */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Sends the user to the system screen to allow installs from this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Downloads [apkUrl] into the app's external Downloads dir and suspends until
     * the download finishes. Returns the downloaded [File], or null on failure.
     */
    suspend fun downloadApk(apkUrl: String): File? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Replace any stale copy so FileProvider serves the fresh APK.
        val target = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Myceliyums update")
            .setDescription("Downloading the latest field build")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILE_NAME
            )

        val downloadId = dm.enqueue(request)

        // Poll for completion — avoids fragile context-registered receivers and
        // the Android 13+ exported-receiver requirements.
        val deadline = MAX_POLLS
        repeat(deadline) {
            when (queryStatus(dm, downloadId)) {
                DownloadManager.STATUS_SUCCESSFUL -> return if (target.exists()) target else null
                DownloadManager.STATUS_FAILED -> {
                    dm.remove(downloadId)
                    return null
                }
                else -> delay(POLL_INTERVAL_MS)
            }
        }
        // Timed out.
        dm.remove(downloadId)
        return null
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int {
        dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) return cursor.getInt(idx)
            }
        }
        return DownloadManager.STATUS_RUNNING
    }

    /** Launches the system package installer for a previously downloaded APK. */
    fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Opens the release page in the browser as a manual fallback. */
    fun openReleasePage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRepository.RELEASE_PAGE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        const val APK_FILE_NAME = "myceliyums-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val POLL_INTERVAL_MS = 600L
        private const val MAX_POLLS = 600 // ~6 minutes ceiling
    }
}
