package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single app-wide preferences DataStore.
private val Context.dataStore by preferencesDataStore(name = "mycelium_settings")

/**
 * Persists user preferences across launches: measurement units, map style,
 * and whether the one-time safety disclaimer has been accepted.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val UNITS = stringPreferencesKey("measure_units")
        val MAP_THEME = stringPreferencesKey("map_theme")
        val SPLASH_ACCEPTED = booleanPreferencesKey("splash_accepted")
        val APP_THEME = stringPreferencesKey("app_theme")
    }

    companion object {
        const val DEFAULT_UNITS = "Metric"
        const val DEFAULT_MAP_THEME = "Standard Street"
        // App-wide colour scheme: "System" follows the device setting.
        const val DEFAULT_APP_THEME = "System"
    }

    val measureUnits: Flow<String> =
        context.dataStore.data.map { it[Keys.UNITS] ?: DEFAULT_UNITS }

    val mapTheme: Flow<String> =
        context.dataStore.data.map { it[Keys.MAP_THEME] ?: DEFAULT_MAP_THEME }

    val splashAccepted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SPLASH_ACCEPTED] ?: false }

    val appTheme: Flow<String> =
        context.dataStore.data.map { it[Keys.APP_THEME] ?: DEFAULT_APP_THEME }

    suspend fun setMeasureUnits(value: String) {
        context.dataStore.edit { it[Keys.UNITS] = value }
    }

    suspend fun setMapTheme(value: String) {
        context.dataStore.edit { it[Keys.MAP_THEME] = value }
    }

    suspend fun setSplashAccepted(value: Boolean) {
        context.dataStore.edit { it[Keys.SPLASH_ACCEPTED] = value }
    }

    suspend fun setAppTheme(value: String) {
        context.dataStore.edit { it[Keys.APP_THEME] = value }
    }
}
