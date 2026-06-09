package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {
    /**
     * Fetches past weather data for rainfall lag analysis.
     *
     * Uses 45 days of history so the lag analysis window (10-21 days ago)
     * is fully covered with room to spare, and we can compute accurate
     * recent averages for temperature suitability scoring.
     */
    @GET("v1/forecast")
    suspend fun getPastWeather(
        @Query("latitude") limitLat: Double,
        @Query("longitude") limitLng: Double,
        @Query("daily") dailyParams: String = "precipitation_sum,temperature_2m_max,temperature_2m_min",
        @Query("past_days") pastDays: Int = 45,
        @Query("forecast_days") forecastDays: Int = 0,
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoResponse

    /**
     * Fetches ground elevation (metres above sea level) for one or more
     * coordinates in a single call. Open-Meteo's elevation API is free,
     * needs no API key, and accepts up to 100 comma-separated coordinate
     * pairs per request — ideal for scoring a whole hotspot grid at once.
     */
    @GET("v1/elevation")
    suspend fun getElevation(
        @Query("latitude") latitudeCsv: String,
        @Query("longitude") longitudeCsv: String
    ): OpenMeteoElevationResponse
}

data class OpenMeteoElevationResponse(
    @Json(name = "elevation") val elevation: List<Double?>?
)

data class OpenMeteoResponse(
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "daily") val daily: OpenMeteoDaily
)

data class OpenMeteoDaily(
    @Json(name = "time") val time: List<String>,
    @Json(name = "precipitation_sum") val precipitationSum: List<Double?>?,
    @Json(name = "temperature_2m_max") val temperatureMax: List<Double?>?,
    @Json(name = "temperature_2m_min") val temperatureMin: List<Double?>?
)
