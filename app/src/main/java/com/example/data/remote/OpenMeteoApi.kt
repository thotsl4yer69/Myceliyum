package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getPastWeather(
        @Query("latitude") limitLat: Double,
        @Query("longitude") limitLng: Double,
        @Query("daily") dailyParams: String = "precipitation_sum,temperature_2m_max,temperature_2m_min",
        @Query("past_days") pastDays: Int = 30,
        @Query("forecast_days") forecastDays: Int = 0,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse
}

data class OpenMeteoResponse(
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "daily") val daily: OpenMeteoDaily
)

data class OpenMeteoDaily(
    @Json(name = "time") val time: List<String>,
    @Json(name = "precipitation_sum") val precipitationSum: List<Double>?,
    @Json(name = "temperature_2m_max") val temperatureMax: List<Double>?
)
