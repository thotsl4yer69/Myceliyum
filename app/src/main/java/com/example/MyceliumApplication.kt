package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.remote.INaturalistApi
import com.example.data.remote.OpenMeteoApi
import com.example.data.repository.FungiRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class MyceliumApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var repository: FungiRepository

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // Moshi needs an explicit Kotlin adapter factory to (de)serialize Kotlin data
        // classes. Without it, parsing iNaturalist / Open-Meteo responses throws at runtime.
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

        val iNatApi = iNatRetrofit.create(INaturalistApi::class.java)
        val openMeteoApi = openMeteoRetrofit.create(OpenMeteoApi::class.java)

        repository = FungiRepository(this, database.fungiDao(), iNatApi, openMeteoApi)
    }
}
