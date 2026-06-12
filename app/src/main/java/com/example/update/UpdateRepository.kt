package com.example.update

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the rolling "latest" GitHub release to discover whether a newer build
 * of the app has been published.
 *
 * It fetches a small `version.json` asset rather than scraping the GitHub API,
 * which means no auth token, no rate limiting, and a stable, redirecting URL
 * that always resolves to whatever release GitHub currently marks as "latest".
 * CI ([.github/workflows/android-ci.yml]) writes that asset on every push to
 * main, next to the freshly built `app-debug.apk`.
 */
class UpdateRepository(
    private val client: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
    private val versionUrl: String = VERSION_JSON_URL
) {
    /**
     * Fetches the latest published version descriptor, or null if it can't be
     * retrieved/parsed (offline, release missing the asset, malformed JSON).
     * Never throws — update checks must fail quietly.
     */
    suspend fun fetchLatest(): ReleaseVersion? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(versionUrl)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                moshi.adapter(ReleaseVersion::class.java).fromJson(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** GitHub owner/repo that publishes the rolling release. */
        const val REPO = "thotsl4yer69/Myceliyum"

        /**
         * Stable redirect to the `version.json` asset on the current "latest"
         * release. GitHub 302s this to the actual asset on releaseassets host.
         */
        const val VERSION_JSON_URL =
            "https://github.com/$REPO/releases/latest/download/version.json"

        /** Page a user can open to see the release and download manually. */
        const val RELEASE_PAGE_URL =
            "https://github.com/$REPO/releases/latest"

        /** Fallback APK URL if `version.json` omits one. */
        const val DEFAULT_APK_URL =
            "https://github.com/$REPO/releases/latest/download/app-debug.apk"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
