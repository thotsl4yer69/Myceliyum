package com.example.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Machine-readable description of the newest published build, fetched from the
 * rolling GitHub "latest" release as the `version.json` asset.
 *
 * `versionCode` mirrors the app's [com.example.BuildConfig.VERSION_CODE], which
 * CI sets to the GitHub Actions run number, so a strictly greater value means a
 * newer build is available. `apkUrl` is the stable redirecting download URL for
 * the freshly built APK attached to the same release.
 */
@JsonClass(generateAdapter = true)
data class ReleaseVersion(
    @Json(name = "versionCode") val versionCode: Int,
    @Json(name = "versionName") val versionName: String = "",
    @Json(name = "notes") val notes: String = "",
    @Json(name = "apkUrl") val apkUrl: String = ""
)

/** UI-facing state for the update check + download flow. */
sealed interface UpdateState {
    /** No check in progress and nothing to show. */
    data object Idle : UpdateState

    /** A version check is in flight (background or manual). */
    data object Checking : UpdateState

    /** The installed build is the newest one. `silent` hides the toast on auto-checks. */
    data class UpToDate(val silent: Boolean) : UpdateState

    /** A newer build is available; prompt the user. */
    data class Available(val release: ReleaseVersion) : UpdateState

    /** The APK is downloading before the system installer is launched. */
    data class Downloading(val release: ReleaseVersion) : UpdateState

    /** "Install unknown apps" permission must be granted before we can install. */
    data class NeedsInstallPermission(val release: ReleaseVersion) : UpdateState

    /** Something went wrong. `silent` suppresses noise on auto-checks. */
    data class Failed(val message: String, val silent: Boolean) : UpdateState
}

/**
 * Pure version-comparison logic, kept free of Android dependencies so it can be
 * unit-tested directly.
 */
object VersionCompare {
    /**
     * True when [remote] describes a build strictly newer than the installed
     * [currentVersionCode]. A null remote (fetch failed / no manifest) is never
     * considered an update.
     */
    fun isUpdateAvailable(currentVersionCode: Int, remote: ReleaseVersion?): Boolean {
        if (remote == null) return false
        return remote.versionCode > currentVersionCode
    }
}
