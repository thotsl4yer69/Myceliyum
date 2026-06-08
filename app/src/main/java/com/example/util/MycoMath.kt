package com.example.util

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, side-effect-free helpers used by the hotspot prediction engine.
 *
 * Extracted into a standalone object so the core geospatial/seasonal logic
 * can be unit-tested on the JVM without Android or Room dependencies.
 */
object MycoMath {

    // ─── Temporal helpers ────────────────────────────────────────────

    /**
     * Returns true if [month] (1-12) falls within the fruiting window
     * [start]..[end] (1-12), correctly handling windows that wrap across
     * the new year (e.g. start = 11 (Nov) through end = 2 (Feb)).
     */
    fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            month in start..end
        } else {
            month >= start || month <= end
        }
    }

    /**
     * Week-level seasonal fitness. Returns 0.0–1.0 based on how close
     * the current week is to the peak of the fruiting window.
     * Peak = middle of the season window → 1.0.
     * Edges of the window → 0.6.
     * Outside the window → 0.0–0.3 (graceful falloff for shoulder weeks).
     */
    fun seasonalFitness(dayOfYear: Int, seasonStart: Int, seasonEnd: Int): Double {
        // Convert months to approximate day-of-year mid-points
        val startDay = (seasonStart - 1) * 30 + 15
        val endDay = (seasonEnd - 1) * 30 + 15

        // Handle wrap-around seasons (e.g. Nov-Feb)
        val seasonLength: Int
        val peakDay: Int
        if (startDay <= endDay) {
            seasonLength = endDay - startDay
            peakDay = startDay + seasonLength / 2
        } else {
            seasonLength = (365 - startDay) + endDay
            peakDay = (startDay + seasonLength / 2) % 365
        }

        // Circular distance on the year
        val dist = circularDistance(dayOfYear, peakDay, 365)
        val halfWindow = seasonLength / 2.0 + 14 // +14 days shoulder

        return when {
            dist <= seasonLength / 4.0 -> 1.0  // Near peak
            dist <= seasonLength / 2.0 -> 0.6 + 0.4 * (1.0 - (dist - seasonLength / 4.0) / (seasonLength / 4.0))
            dist <= halfWindow -> 0.3 * (1.0 - (dist - seasonLength / 2.0) / 14.0) // Shoulder
            else -> 0.0
        }.coerceIn(0.0, 1.0)
    }

    private fun circularDistance(a: Int, b: Int, period: Int): Double {
        val d = abs(a - b)
        return minOf(d, period - d).toDouble()
    }

    // ─── Geospatial helpers ──────────────────────────────────────────

    /**
     * Great-circle distance between two lat/lng points in metres
     * using the haversine formula.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in metres
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val deltaPhi = (lat2 - lat1) * PI / 180.0
        val deltaLambda = (lon2 - lon1) * PI / 180.0

        val a = sin(deltaPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // ─── Rainfall lag analysis ───────────────────────────────────────

    /**
     * Analyses daily rainfall data to detect fruiting trigger events.
     *
     * Fungi typically fruit 10-21 days after a significant rain event (>20mm
     * in 48 hours). This function scans the daily precipitation array and
     * returns a 0.0–1.0 score reflecting how strongly recent rain patterns
     * match a fruiting trigger.
     *
     * @param dailyRainfallMm array of daily precipitation totals, index 0 = oldest day
     * @param lagStartDays earliest day offset from today for trigger window (default 10)
     * @param lagEndDays latest day offset from today for trigger window (default 21)
     * @param triggerThresholdMm minimum 2-day cumulative rain to count as a trigger event
     */
    fun rainfallTriggerScore(
        dailyRainfallMm: List<Double>,
        lagStartDays: Int = 10,
        lagEndDays: Int = 21,
        triggerThresholdMm: Double = 20.0
    ): Double {
        if (dailyRainfallMm.size < 2) return 0.0

        val totalDays = dailyRainfallMm.size
        var bestTriggerStrength = 0.0

        // Scan the lag window looking for 2-day cumulative rain events
        for (lagDay in lagStartDays..lagEndDays) {
            val idx = totalDays - lagDay
            if (idx < 1 || idx >= totalDays) continue

            val twoDay = dailyRainfallMm[idx] + dailyRainfallMm[idx - 1]
            if (twoDay >= triggerThresholdMm) {
                // Strength scales with rain amount (diminishing returns past 60mm)
                val strength = minOf(1.0, twoDay / 60.0)
                // Optimal trigger is around 14-17 days ago
                val optimalLag = 15.5
                val lagFitness = 1.0 - abs(lagDay - optimalLag) / (lagEndDays - lagStartDays).toDouble()
                val combined = strength * (0.5 + 0.5 * lagFitness)
                if (combined > bestTriggerStrength) bestTriggerStrength = combined
            }
        }

        // Also factor in sustained moisture (total rain in past 30 days)
        val recentTotal = dailyRainfallMm.takeLast(minOf(30, totalDays)).sum()
        val moistureBase = when {
            recentTotal in 40.0..180.0 -> 0.3  // Adequate background moisture
            recentTotal > 180.0 -> 0.2          // Waterlogged — slightly less ideal
            else -> recentTotal / 40.0 * 0.3    // Dry conditions
        }

        return minOf(1.0, bestTriggerStrength + moistureBase)
    }

    // ─── Temperature fitness ─────────────────────────────────────────

    /**
     * Species-specific temperature suitability score.
     * Returns 0.0–1.0 based on how well recent temperatures match the
     * species' preferred fruiting range.
     *
     * Most Victorian fungi fruit best at 8-18°C. Psilocybes prefer
     * cooler (5-15°C), tropical species need warmer (15-25°C).
     */
    fun temperatureFitness(
        avgTemp: Double,
        speciesId: String
    ): Double {
        val (idealMin, idealMax) = speciesTempRange(speciesId)
        val midpoint = (idealMin + idealMax) / 2.0
        val halfRange = (idealMax - idealMin) / 2.0

        val dist = abs(avgTemp - midpoint)
        return when {
            dist <= halfRange -> 1.0
            dist <= halfRange + 5.0 -> 1.0 - (dist - halfRange) / 10.0
            else -> maxOf(0.0, 0.5 - (dist - halfRange - 5.0) / 20.0)
        }.coerceIn(0.0, 1.0)
    }

    /** Species-specific ideal temperature ranges (°C) for fruiting. */
    private fun speciesTempRange(speciesId: String): Pair<Double, Double> = when {
        speciesId.startsWith("psilocybe") -> 5.0 to 15.0
        speciesId.startsWith("amanita") -> 8.0 to 18.0
        speciesId.startsWith("cortinarius") -> 6.0 to 16.0
        speciesId.startsWith("boletus") || speciesId.startsWith("suillus") -> 10.0 to 20.0
        speciesId.contains("tropical") || speciesId.startsWith("favolaschia") -> 12.0 to 25.0
        speciesId.startsWith("gymnopilus") -> 8.0 to 18.0
        speciesId.startsWith("omphalotus") -> 8.0 to 18.0
        speciesId.startsWith("trametes") -> 10.0 to 22.0
        speciesId.startsWith("ganoderma") -> 10.0 to 22.0
        speciesId.startsWith("mycena") -> 5.0 to 15.0
        speciesId.startsWith("coprinellus") || speciesId.startsWith("coprinus") -> 8.0 to 20.0
        else -> 8.0 to 18.0 // Victorian fungi default
    }

    // ─── Habitat suitability ─────────────────────────────────────────

    /**
     * Scores how well a species' preferred habitat matches the likely
     * land-cover at the given location. Since we don't have real land-cover
     * data, this uses the species' habitat list + known micro-habitat
     * heuristics for common Victorian species.
     *
     * Species with broader habitat tolerances get a higher baseline.
     * Species-specific boosts for known micro-habitats (e.g. Psilocybe
     * subaeruginosa in pine bark mulch parks).
     */
    fun habitatDiversityScore(habitatTypes: List<String>, substrates: List<String>): Double {
        // More diverse habitat tolerance → higher baseline probability of match
        val habitatBreadth = minOf(1.0, habitatTypes.size / 4.0)
        val substrateBreadth = minOf(1.0, substrates.size / 3.0)
        return (0.6 * habitatBreadth + 0.4 * substrateBreadth).coerceIn(0.2, 1.0)
    }

    /**
     * Species-specific habitat affinity weight. Some species are very
     * specific to certain micro-habitats; this gives them a differentiated
     * signal even when observation data is sparse.
     */
    fun speciesHabitatWeight(speciesId: String): Double = when {
        // Very habitat-specific — score varies more with location
        speciesId == "psilocybe_subaeruginosa" -> 1.4
        speciesId == "amanita_muscaria" -> 1.3
        speciesId == "amanita_phalloides" -> 1.3
        // Broad generalists — score varies less
        speciesId.startsWith("trametes") -> 0.8
        speciesId.startsWith("coprinellus") -> 0.7
        speciesId.startsWith("gymnopilus") -> 1.0
        else -> 1.0
    }

    // ─── Moon phase (optional factor) ────────────────────────────────

    /**
     * Returns the current moon phase as a 0.0–1.0 cycle value.
     * 0.0 / 1.0 = new moon, 0.5 = full moon.
     *
     * Uses a simplified synodic calculation (accurate to ~1 day).
     * Some foragers believe fruiting peaks around new moon and in the
     * days following full moon. This is included as a low-weight factor.
     */
    fun moonPhase(timestampMs: Long): Double {
        // Known new moon: Jan 6, 2000 18:14 UTC
        val knownNewMoonMs = 947181240000L
        val synodicPeriodMs = 29.53058867 * 24 * 60 * 60 * 1000
        val daysSince = (timestampMs - knownNewMoonMs).toDouble() / synodicPeriodMs
        return daysSince - floor(daysSince) // 0.0 = new moon, 0.5 = full moon
    }

    /**
     * Moon phase fruiting score. Many traditional foragers claim that
     * fungi fruit best 2-5 days after a new moon or full moon.
     * Returns 0.0–1.0 with peaks near those phases.
     */
    fun moonFruitingScore(timestampMs: Long): Double {
        val phase = moonPhase(timestampMs)
        // Two peaks: near new moon (0.0) and full moon (0.5)
        val distToNew = minOf(phase, 1.0 - phase)
        val distToFull = abs(phase - 0.5)
        val minDist = minOf(distToNew, distToFull)
        // Peak within 0.1 of cycle (~3 days), gentle falloff
        return when {
            minDist < 0.05 -> 1.0
            minDist < 0.15 -> 0.5 + 0.5 * (1.0 - (minDist - 0.05) / 0.1)
            else -> 0.3 + 0.2 * (1.0 - minOf(1.0, (minDist - 0.15) / 0.35))
        }.coerceIn(0.3, 1.0)
    }

    // ─── Observation evidence scoring ────────────────────────────────

    /**
     * Weights an observation by its verification quality.
     * Research-grade and herbarium specimens are most reliable;
     * casual citizen science records are down-weighted but still
     * contribute evidence.
     */
    fun qualityWeight(qualityGrade: String): Double = when (qualityGrade.lowercase()) {
        "research" -> 1.0
        "needs_id" -> 0.6
        "casual" -> 0.3
        else -> 0.5
    }

    /**
     * Source-specific multiplier for observation evidence.
     * Herbarium records (ALA/GBIF PRESERVED_SPECIMEN) verified by
     * professional mycologists are the gold standard. Fungimap records
     * from Royal Botanic Gardens Melbourne are weighted above generic iNat.
     */
    fun sourceWeight(source: String): Double = when (source.uppercase()) {
        "ALA" -> 1.3            // Verified Australian biodiversity records
        "GBIF" -> 1.2           // Global museum/herbarium records with DOIs
        "INATURALIST" -> 1.0    // Citizen science baseline
        "MUSHROOMOBSERVER" -> 0.9
        "USER" -> 1.5           // First-hand, georeferenced
        else -> 0.8
    }

    /**
     * Temporal decay weight with configurable half-life.
     * More recent observations are more valuable evidence.
     */
    fun recencyWeight(ageDays: Double, halfLifeDays: Double = 365.0): Double {
        if (ageDays < 0) return 0.0
        val lambda = ln(2.0) / halfLifeDays
        return exp(-lambda * ageDays)
    }

    /**
     * Gaussian spatial kernel for proximity weighting.
     */
    fun spatialKernel(distanceMeters: Double, sigma: Double = 800.0): Double {
        return exp(-(distanceMeters * distanceMeters) / (2.0 * sigma * sigma))
    }

    // ─── 5-tier classification ───────────────────────────────────────

    /**
     * Maps a 0.0–1.0 score to one of five tiers.
     */
    fun classifyTier(score: Double): String = when {
        score >= 0.80 -> "Excellent"
        score >= 0.60 -> "VeryGood"
        score >= 0.40 -> "Promising"
        score >= 0.20 -> "Possible"
        else -> "Unlikely"
    }
}
