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

    // ─── Terrain & elevation (per-cell landscape) ────────────────────

    /**
     * Species-specific elevation suitability (0.0–1.0).
     *
     * Real ground elevation at the cell is matched against the species'
     * preferred altitude band. Unlike the global climate factors, this
     * varies cell-to-cell, so the map reflects genuine terrain.
     */
    fun elevationFitness(elevationM: Double, speciesId: String): Double {
        val (lo, hi) = speciesElevationBand(speciesId)
        val mid = (lo + hi) / 2.0
        val half = (hi - lo) / 2.0
        val dist = abs(elevationM - mid)
        return when {
            dist <= half -> 1.0
            dist <= half + 300.0 -> 1.0 - (dist - half) / 600.0
            else -> maxOf(0.0, 0.5 - (dist - half - 300.0) / 1200.0)
        }.coerceIn(0.0, 1.0)
    }

    /** Species-specific ideal elevation band (metres ASL) for fruiting. */
    private fun speciesElevationBand(speciesId: String): Pair<Double, Double> = when {
        // Lowland pastures, urban mulch and coastal scrub
        speciesId.startsWith("psilocybe") -> 0.0 to 700.0
        speciesId.startsWith("coprinellus") || speciesId.startsWith("coprinus") -> 0.0 to 600.0
        // Montane wet forest specialists
        speciesId.startsWith("cortinarius") -> 200.0 to 1400.0
        speciesId.startsWith("boletus") || speciesId.startsWith("suillus") -> 100.0 to 1200.0
        speciesId.startsWith("amanita") -> 50.0 to 1100.0
        speciesId.startsWith("mycena") -> 0.0 to 1200.0
        speciesId.startsWith("gymnopilus") || speciesId.startsWith("omphalotus") -> 0.0 to 1000.0
        else -> 0.0 to 1000.0 // Broad Victorian default
    }

    /**
     * Terrain moisture/landform suitability (0.0–1.0) from local relief.
     *
     * Derived from the cell's elevation relative to its neighbours:
     *  - Gentle-to-moderate slopes drain well yet hold leaf-litter moisture.
     *  - Concave hollows / gully heads accumulate moisture and organic matter
     *    (prime fruiting ground), so sitting *below* the local mean scores up.
     *  - Exposed local highs / very steep faces are drier and score down.
     *
     * @param cellElevation elevation of this cell (m)
     * @param neighbourElevations elevations of the surrounding grid cells (m)
     */
    fun terrainMoistureScore(cellElevation: Double, neighbourElevations: List<Double>): Double {
        if (neighbourElevations.isEmpty()) return 0.5
        val meanNbr = neighbourElevations.average()
        val relief = (neighbourElevations.maxOrNull()!! - neighbourElevations.minOrNull()!!)
        val concavity = meanNbr - cellElevation // >0 ⇒ cell sits in a hollow

        // Local slope (relief over ~500 m cells) — gentle/moderate is best.
        val slopeScore = when {
            relief < 8.0 -> 0.6            // very flat — can be waterlogged or exposed
            relief <= 40.0 -> 1.0          // gentle–moderate — ideal drainage + moisture
            relief <= 80.0 -> 0.7          // steeper
            else -> 0.4                    // ridge/cliff — poor footing for fruiting
        }
        // Landform position — hollows hold moisture, local highs shed it.
        val concavityScore = when {
            concavity > 6.0 -> 1.0
            concavity > 1.0 -> 0.8
            concavity > -3.0 -> 0.6        // ~flat / mid-slope
            else -> 0.45                   // exposed local high
        }
        return (0.5 * slopeScore + 0.5 * concavityScore).coerceIn(0.0, 1.0)
    }

    /**
     * Slope-aspect moisture suitability (0.0–1.0) for the Southern Hemisphere.
     *
     * The sun sits to the north, so **south-facing** slopes stay cooler, shadier
     * and moister (prime fungal ground), while north-facing slopes dry out.
     * East-facing slopes (gentle morning sun) are mildly preferred over
     * west-facing (harsh afternoon sun). Aspect is derived from the elevation
     * difference across the cell, so flat ground scores neutrally.
     *
     * Pass the centre elevation plus its four cardinal neighbours; missing
     * neighbours fall back to the centre (→ zero gradient → neutral).
     */
    fun slopeAspectMoistureScore(
        elevCenter: Double,
        elevNorth: Double?,
        elevSouth: Double?,
        elevEast: Double?,
        elevWest: Double?
    ): Double {
        val n = elevNorth ?: elevCenter
        val s = elevSouth ?: elevCenter
        val e = elevEast ?: elevCenter
        val w = elevWest ?: elevCenter
        // +southness ⇒ terrain falls away to the south ⇒ south-facing.
        val southness = ((n - s) / 30.0).coerceIn(-1.0, 1.0)
        // +eastness ⇒ terrain falls away to the east ⇒ east-facing.
        val eastness = ((w - e) / 30.0).coerceIn(-1.0, 1.0)
        return (0.70 + 0.25 * southness + 0.05 * eastness).coerceIn(0.0, 1.0)
    }

    /**
     * Soil-moisture suitability (0.0–1.0) from volumetric water content
     * (m³/m³, as reported by Open-Meteo's 0–7 cm soil layer). Most fungi
     * fruit best in consistently damp — but not waterlogged — soil.
     */
    fun soilMoistureFitness(vwc: Double): Double = when {
        vwc <= 0.0 -> 0.0
        vwc < 0.15 -> vwc / 0.15 * 0.5                       // dry
        vwc < 0.25 -> 0.5 + (vwc - 0.15) / 0.10 * 0.4        // improving
        vwc <= 0.40 -> 1.0                                   // ideal damp range
        vwc <= 0.50 -> 1.0 - (vwc - 0.40) / 0.10 * 0.3       // getting waterlogged
        else -> 0.6
    }.coerceIn(0.0, 1.0)

    /**
     * Canopy/forest proximity suitability (0.0–1.0). Most target fungi are
     * woodland species (mycorrhizal or wood-rotting), so cells in or near
     * mapped forest/wood/park features score higher. [distanceMeters] is the
     * distance to the nearest such feature, or null when canopy data is
     * unavailable (→ neutral, so the factor doesn't penalise on a failed fetch).
     */
    fun canopyProximityScore(distanceMeters: Double?): Double = when {
        distanceMeters == null -> 0.6
        distanceMeters <= 150.0 -> 1.0                                          // inside/at the treeline
        distanceMeters <= 1500.0 -> 1.0 - (distanceMeters - 150.0) / 1350.0 * 0.7 // 1.0 → 0.3
        distanceMeters <= 3000.0 -> 0.3 - (distanceMeters - 1500.0) / 1500.0 * 0.2 // 0.3 → 0.1
        else -> 0.1
    }.coerceIn(0.0, 1.0)

    // ─── Earth Engine layers (optional backend) ──────────────────────

    /**
     * Land-cover suitability (0.0–1.0) from an ESA WorldCover class code.
     * Woodland/wetland favoured; most species avoid bare/water/built ground —
     * except pasture/urban-mulch specialists like Psilocybe subaeruginosa.
     */
    fun landCoverSuitability(worldCoverClass: Int?, speciesId: String): Double {
        if (worldCoverClass == null) return 0.6
        val psilocybe = speciesId.startsWith("psilocybe")
        return when (worldCoverClass) {
            10 -> 1.0                               // tree cover
            90, 95 -> 0.9                           // wetland / mangrove
            20, 100 -> 0.8                          // shrubland / moss-lichen
            30 -> if (psilocybe) 0.85 else 0.6      // grassland / pasture
            40 -> 0.45                              // cropland
            50 -> if (psilocybe) 0.70 else 0.40     // built-up (urban mulch beds)
            60 -> 0.20                              // bare / sparse
            70 -> 0.10                              // snow / ice
            80 -> 0.05                              // permanent water
            else -> 0.5
        }
    }

    /** Tree-canopy suitability (0.0–1.0) from a canopy-cover percentage (0–100). */
    fun treeCanopyFitness(percent: Double?): Double {
        if (percent == null) return 0.6
        val p = percent.coerceIn(0.0, 100.0)
        return when {
            p >= 60.0 -> 1.0
            p >= 20.0 -> 0.6 + (p - 20.0) / 40.0 * 0.4
            p >= 5.0 -> 0.3 + (p - 5.0) / 15.0 * 0.3
            else -> 0.2 + p / 5.0 * 0.1
        }.coerceIn(0.0, 1.0)
    }

    /** Vegetation-greenness suitability (0.0–1.0) from an NDVI value (−1..1). */
    fun ndviFitness(ndvi: Double?): Double {
        if (ndvi == null) return 0.6
        return when {
            ndvi < 0.0 -> 0.05                              // water / built / bare
            ndvi < 0.2 -> 0.2 + ndvi / 0.2 * 0.2            // sparse
            ndvi < 0.4 -> 0.4 + (ndvi - 0.2) / 0.2 * 0.3    // moderate
            ndvi <= 0.8 -> 1.0                              // lush vegetation
            else -> 0.9
        }.coerceIn(0.0, 1.0)
    }

    /**
     * Blends the Earth Engine layers (tree canopy %, NDVI, land-cover class)
     * into a single per-cell canopy/vegetation suitability. Used in place of
     * the OSM proximity heuristic when the backend is configured.
     */
    fun richCanopyScore(canopyPct: Double?, ndvi: Double?, worldCoverClass: Int?, speciesId: String): Double {
        val tree = treeCanopyFitness(canopyPct)
        val veg = ndviFitness(ndvi)
        val land = landCoverSuitability(worldCoverClass, speciesId)
        return (0.40 * tree + 0.35 * land + 0.25 * veg).coerceIn(0.0, 1.0)
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
