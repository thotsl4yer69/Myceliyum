package com.example.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Machine-readable description of the newest published build, fetched from the
 * rolling GitHub "latest" release as the `version.json` asset.
 *
 * `versionCode` matches the committed Android build configuration. The manifest
 * also carries the APK hash and source commit, verified before installation.
 */
@JsonClass(generateAdapter = true)
data class ReleaseVersion(
    @Json(name = "versionCode") val versionCode: Int,
    @Json(name = "versionName") val versionName: String = "",
    @Json(name = "notes") val notes: String = "",
    @Json(name = "apkUrl") val apkUrl: String = "",
    @Json(name = "sha256") val sha256: String = "",
    @Json(name = "sourceCommit") val sourceCommit: String = ""
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

/** Legacy and incomplete release metadata must never enable an automatic install. */
object ReleasePolicy {
    fun validMetadata(release: ReleaseVersion): Boolean =
        release.versionCode >= 7201 &&
        release.versionName.isNotBlank() &&
        release.sha256.matches(Regex("[a-fA-F0-9]{64}")) &&
        release.sourceCommit.matches(Regex("[a-fA-F0-9]{40}")) &&
        release.apkUrl.startsWith("https://github.com/thotsl4yer69/Myceliyum/releases/download/") &&
        release.apkUrl.endsWith(".apk")
}
