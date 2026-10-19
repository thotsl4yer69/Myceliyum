package com.example.util

import com.example.model.UserSighting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SightingPolicy {
    fun validCoordinates(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0

    /** Sharing is always public-only. Complete personal backups use a separate format. */
    fun publicCsv(sightings: List<UserSighting>): String {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        fun cell(value: String): String {
            // Quoting alone does not prevent a spreadsheet from evaluating a formula.
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        return buildString {
            append("id,species_id,latitude,longitude,timestamp,date_utc,notes\r\n")
            sightings.filterNot { it.isPrivate }.forEach { s ->
                append("${s.id},${cell(s.speciesId)},${s.lat},${s.lng},${s.timestamp},${cell(date.format(Date(s.timestamp)))},${cell(s.notes)}\r\n")
            }
        }
    }
}
