package com.example

import android.app.Application
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsStore
import com.example.data.remote.ALAApi
import com.example.data.remote.GBIFApi
import com.example.data.remote.INaturalistApi
import com.example.data.remote.EnvLayersApi
import com.example.data.remote.GeocodingApi
import com.example.data.remote.OpenMeteoApi
import com.example.data.remote.OverpassApi
import com.example.data.repository.FungiRepository
import org.osmdroid.config.Configuration as OsmConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class MyceliumApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var repository: FungiRepository
    lateinit var settingsStore: SettingsStore

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        settingsStore = SettingsStore(this)

        // Initialise osmdroid before any MapView is created. Without loading
        // its configuration (cache dirs) and setting a real User-Agent, OSM
        // tile servers reject requests and the map renders blank.
        OsmConfig.getInstance().apply {
            load(this@MyceliumApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Moshi needs the Kotlin reflection adapter to (de)serialize Kotlin
        // data classes that aren't annotated for codegen. Without it, Moshi
        // throws at runtime and the errors get swallowed by the repository's
        // try/catch, leaving the app with no data.
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val iNatRetrofit = Retrofit.Builder()
            .baseUrl("https://api.inaturalist.org/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val openMeteoRetrofit = Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val alaRetrofit = Retrofit.Builder()
            .baseUrl("https://biocache-ws.ala.org.au/ws/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val gbifRetrofit = Retrofit.Builder()
            .baseUrl("https://api.gbif.org/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val overpassRetrofit = Retrofit.Builder()
            .baseUrl("https://overpass-api.de/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val iNatApi = iNatRetrofit.create(INaturalistApi::class.java)
        val openMeteoApi = openMeteoRetrofit.create(OpenMeteoApi::class.java)
        val alaApi = alaRetrofit.create(ALAApi::class.java)
        val gbifApi = gbifRetrofit.create(GBIFApi::class.java)
        val overpassApi = overpassRetrofit.create(OverpassApi::class.java)

        val geocodingApi = Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeocodingApi::class.java)

        // Optional Earth Engine backend — only built when a base URL is set,
        // so the app works keylessly out of the box. Invalid/malformed values
        // are ignored so startup never crashes because of backend config.
        val envLayersApi: EnvLayersApi? = BuildConfig.BACKEND_BASE_URL
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { rawBaseUrl ->
                val parsedBaseUrl = parseBackendBaseUrl(rawBaseUrl) ?: return@let null
                runCatching {
                    Retrofit.Builder()
                        .baseUrl(parsedBaseUrl)
                        .client(okHttpClient)
                        .addConverterFactory(MoshiConverterFactory.create(moshi))
                        .build()
                        .create(EnvLayersApi::class.java)
                }.onFailure { err ->
                    Log.w(TAG, "Ignoring invalid BACKEND_BASE_URL configuration.", err)
                }.getOrNull()
            }

        repository = FungiRepository(
            this, database.fungiDao(), iNatApi, openMeteoApi, alaApi, gbifApi,
            overpassApi, envLayersApi, BuildConfig.BACKEND_TOKEN,
            geocodingApi, BuildConfig.GOOGLE_API_KEY
        )
    }

    private fun parseBackendBaseUrl(rawBaseUrl: String): HttpUrl? {
        rawBaseUrl.toHttpUrlOrNull()?.let { return it }
        if (!rawBaseUrl.endsWith("/")) {
            val normalizedBaseUrl = "$rawBaseUrl/"
            val retried = normalizedBaseUrl.toHttpUrlOrNull()
            if (retried != null) {
                Log.i(TAG, "BACKEND_BASE_URL missing trailing slash; normalized host=${retried.host}")
            } else {
                Log.w(TAG, "Ignoring invalid BACKEND_BASE_URL configuration.")
            }
            return retried
        }
        Log.w(TAG, "Ignoring invalid BACKEND_BASE_URL configuration.")
        return null
    }

    companion object {
        private const val TAG = "MyceliumApplication"
    }
}
