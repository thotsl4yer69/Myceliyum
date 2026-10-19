package com.example.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the in-app update experience: checking the rolling GitHub release for
 * a newer build, then downloading and installing it on request.
 *
 * Installed and published version codes come from the same committed build
 * configuration. Only complete, integrity-protected metadata can enable updates.
 */
class UpdateViewModel(
    app: Application,
    private val repository: UpdateRepository = UpdateRepository(),
    private val updateManager: AppUpdateManager = AppUpdateManager(app),
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val currentVersionName: String = BuildConfig.VERSION_NAME
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Checks for a newer build. [silent] is used for the automatic check on
     * launch so a non-update outcome doesn't surface any UI.
     */
    fun checkForUpdates(silent: Boolean) {
        // Don't interrupt an in-progress download.
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val remote = repository.fetchLatest()
            _state.value = when {
                remote == null ->
                    if (silent) UpdateState.Idle
                    else UpdateState.Failed("No verified release is available, or the update server could not be reached.", silent = false)
                VersionCompare.isUpdateAvailable(currentVersionCode, remote) ->
                    UpdateState.Available(remote)
                else ->
                    UpdateState.UpToDate(silent)
            }
        }
    }

    /**
     * Starts the download + install for the given release. If the OS hasn't been
     * granted permission to install unknown apps, routes the user to grant it
     * first and re-arms the prompt.
     */
    fun downloadAndInstall(release: ReleaseVersion) {
        if (_state.value is UpdateState.Downloading) return
        if (!ReleasePolicy.validMetadata(release)) {
            _state.value = UpdateState.Failed("Release verification is incomplete.", silent = false)
            return
        }
        try {
            if (!updateManager.canInstallPackages()) {
                _state.value = UpdateState.NeedsInstallPermission(release)
                updateManager.openInstallPermissionSettings()
                return
            }
        } catch (_: Exception) {
            _state.value = UpdateState.Failed("Could not open install permission settings.", silent = false)
            return
        }
        _state.value = UpdateState.Downloading(release)
        viewModelScope.launch {
            var downloaded: java.io.File? = null
            try {
                val apk = updateManager.downloadApk(release.apkUrl)
                    ?: throw IllegalStateException("Download failed. Try again.")
                downloaded = apk
                updateManager.verifyApk(apk, release)
                updateManager.launchInstaller(apk)
                _state.value = UpdateState.Idle
            } catch (cancelled: CancellationException) {
                downloaded?.delete()
                throw cancelled
            } catch (error: Exception) {
                downloaded?.delete()
                _state.value = UpdateState.Failed(error.message ?: "Update verification failed.", silent = false)
            }
        }
    }

    /** Opens the GitHub release page so the user can download manually. */
    fun openReleasePage() = updateManager.openReleasePage()

    /** Dismisses any transient banner/dialog without acting. */
    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) {
            _state.value = UpdateState.Idle
        }
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return UpdateViewModel(application) as T
                }
            }
    }
}
