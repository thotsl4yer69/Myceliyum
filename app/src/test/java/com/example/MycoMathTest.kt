package com.example

import com.example.util.MycoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure geospatial/seasonal helpers that drive the
 * hotspot prediction engine. These run on the JVM (no Android required).
 */
class MycoMathTest {

    @Test
    fun `month inside a normal season window is in season`() {
        // Autumn window April(4) to June(6)
        assertTrue(MycoMath.isMonthInSeason(4, 4, 6))
        assertTrue(MycoMath.isMonthInSeason(5, 4, 6))
        assertTrue(MycoMath.isMonthInSeason(6, 4, 6))
    }

    @Test
    fun `month outside a normal season window is out of season`() {
        assertFalse(MycoMath.isMonthInSeason(3, 4, 6))
        assertFalse(MycoMath.isMonthInSeason(7, 4, 6))
        assertFalse(MycoMath.isMonthInSeason(12, 4, 6))
    }

    @Test
    fun `season window that wraps across the new year is handled`() {
        // November(11) through February(2)
        assertTrue(MycoMath.isMonthInSeason(11, 11, 2))
        assertTrue(MycoMath.isMonthInSeason(12, 11, 2))
        assertTrue(MycoMath.isMonthInSeason(1, 11, 2))
        assertTrue(MycoMath.isMonthInSeason(2, 11, 2))
        assertFalse(MycoMath.isMonthInSeason(3, 11, 2))
        assertFalse(MycoMath.isMonthInSeason(6, 11, 2))
    }

    @Test
    fun `haversine distance between identical points is zero`() {
        assertEquals(0.0, MycoMath.haversineMeters(-37.8136, 144.9631, -37.8136, 144.9631), 1e-6)
    }

    @Test
    fun `haversine distance between Melbourne CBD and Dandenong Ranges is in expected range`() {
        // Melbourne CBD (-37.8136, 144.9631) to Dandenong Ranges (-37.8386, 145.3524)
        val meters = MycoMath.haversineMeters(-37.8136, 144.9631, -37.8386, 145.3524)
        // Straight-line distance is roughly 34 km.
        assertTrue("Expected ~34 km, got ${meters / 1000.0} km", meters in 30_000.0..40_000.0)
    }

    // ─── Elevation fitness ───────────────────────────────────────────

    @Test
    fun `elevation inside the species band scores perfectly`() {
        // Cortinarius band is montane (200-1400 m); 800 m sits mid-band.
        assertEquals(1.0, MycoMath.elevationFitness(800.0, "cortinarius_archeri"), 1e-9)
    }

    @Test
    fun `elevation far outside the species band scores low`() {
        // Psilocybe band is lowland (0-700 m); 2500 m is well above it.
        val high = MycoMath.elevationFitness(2500.0, "psilocybe_subaeruginosa")
        assertTrue("Expected a low score, got $high", high < 0.3)
    }

    @Test
    fun `elevation fitness is always within 0 and 1`() {
        for (e in listOf(-50.0, 0.0, 500.0, 1500.0, 4000.0)) {
            val s = MycoMath.elevationFitness(e, "amanita_muscaria")
            assertTrue("score $s out of range for $e m", s in 0.0..1.0)
        }
    }

    // ─── Terrain moisture ────────────────────────────────────────────

    @Test
    fun `a hollow on a gentle slope scores higher than an exposed local high`() {
        // Cell sits below its neighbours (concave hollow) on gentle relief.
        val hollow = MycoMath.terrainMoistureScore(
            cellElevation = 290.0,
            neighbourElevations = listOf(300.0, 305.0, 298.0, 310.0)
        )
        // Cell sits above its neighbours (exposed knoll), same gentle relief.
        val knoll = MycoMath.terrainMoistureScore(
            cellElevation = 320.0,
            neighbourElevations = listOf(300.0, 305.0, 298.0, 310.0)
        )
        assertTrue("hollow ($hollow) should beat knoll ($knoll)", hollow > knoll)
    }

    @Test
    fun `terrain score with no neighbour data falls back to neutral`() {
        assertEquals(0.5, MycoMath.terrainMoistureScore(300.0, emptyList()), 1e-9)
    }

    // ─── Slope aspect (Southern Hemisphere) ──────────────────────────

    @Test
    fun `south-facing slope scores higher than north-facing`() {
        // South-facing: north neighbour higher, south neighbour lower.
        val southFacing = MycoMath.slopeAspectMoistureScore(
            elevCenter = 300.0, elevNorth = 330.0, elevSouth = 270.0, elevEast = 300.0, elevWest = 300.0
        )
        // North-facing: the reverse.
        val northFacing = MycoMath.slopeAspectMoistureScore(
            elevCenter = 300.0, elevNorth = 270.0, elevSouth = 330.0, elevEast = 300.0, elevWest = 300.0
        )
        assertTrue("south ($southFacing) should beat north ($northFacing)", southFacing > northFacing)
    }

    @Test
    fun `flat ground gives a neutral aspect score`() {
        val flat = MycoMath.slopeAspectMoistureScore(300.0, 300.0, 300.0, 300.0, 300.0)
        assertEquals(0.70, flat, 1e-9)
    }

    // ─── Soil moisture ───────────────────────────────────────────────

    @Test
    fun `ideal damp soil scores full, bone-dry scores low`() {
        assertEquals(1.0, MycoMath.soilMoistureFitness(0.32), 1e-9)
        assertTrue(MycoMath.soilMoistureFitness(0.05) < 0.3)
    }

    // ─── Canopy proximity ────────────────────────────────────────────

    @Test
    fun `closer woodland scores higher, and missing data is neutral`() {
        assertEquals(1.0, MycoMath.canopyProximityScore(50.0), 1e-9)
        assertTrue(MycoMath.canopyProximityScore(100.0) > MycoMath.canopyProximityScore(2000.0))
        assertEquals(0.6, MycoMath.canopyProximityScore(null), 1e-9)
    }

    // ─── Earth Engine layers ─────────────────────────────────────────

    @Test
    fun `dense tree cover scores higher than sparse`() {
        assertEquals(1.0, MycoMath.treeCanopyFitness(85.0), 1e-9)
        assertTrue(MycoMath.treeCanopyFitness(70.0) > MycoMath.treeCanopyFitness(8.0))
    }

    @Test
    fun `lush ndvi scores high, water scores low`() {
        assertEquals(1.0, MycoMath.ndviFitness(0.6), 1e-9)
        assertTrue(MycoMath.ndviFitness(-0.2) < 0.2)
    }

    @Test
    fun `forest land cover beats built-up for a generalist`() {
        val tree = MycoMath.landCoverSuitability(10, "amanita_muscaria")   // tree cover
        val built = MycoMath.landCoverSuitability(50, "amanita_muscaria")  // built-up
        assertTrue("tree ($tree) should beat built ($built)", tree > built)
    }

    @Test
    fun `psilocybe tolerates pasture and urban mulch better than a generalist`() {
        val psiGrass = MycoMath.landCoverSuitability(30, "psilocybe_subaeruginosa")
        val genGrass = MycoMath.landCoverSuitability(30, "amanita_muscaria")
        assertTrue("psilocybe ($psiGrass) should beat generalist ($genGrass) on grassland", psiGrass > genGrass)
    }

    // ─── Habitat gate (the urban counter-weight) ─────────────────────

    @Test
    fun `built-up and water land cover are gated to near-zero`() {
        assertTrue("built-up should be heavily gated", MycoMath.habitatGate(50, 0.05, "amanita_muscaria") < 0.2)
        assertTrue("water should be gated to near-zero", MycoMath.habitatGate(80, -0.1, "amanita_muscaria") < 0.15)
    }

    @Test
    fun `forest passes the gate, far above built-up`() {
        val forest = MycoMath.habitatGate(10, 0.6, "amanita_muscaria")
        val city = MycoMath.habitatGate(50, 0.0, "amanita_muscaria")
        assertEquals(1.0, forest, 1e-9)
        assertTrue("forest ($forest) must dominate city ($city)", forest > city * 3)
    }

    @Test
    fun `negative ndvi vetoes a cell even when land cover is unknown`() {
        // No EE land-cover class, but clearly non-vegetated (pavement/water).
        assertTrue(MycoMath.habitatGate(null, -0.05, "amanita_muscaria") <= 0.2)
    }

    @Test
    fun `psilocybe keeps a floor on urban mulch that forest species do not`() {
        val psi = MycoMath.habitatGate(50, 0.2, "psilocybe_subaeruginosa")
        val gen = MycoMath.habitatGate(50, 0.2, "amanita_muscaria")
        assertTrue("psilocybe ($psi) tolerates built-up better than generalist ($gen)", psi > gen)
    }

    @Test
    fun `riparian score rewards proximity to water and is neutral when far`() {
        assertEquals(1.0, MycoMath.riparianScore(50.0), 1e-9)
        assertTrue(MycoMath.riparianScore(200.0) > MycoMath.riparianScore(1500.0))
        assertEquals(0.45, MycoMath.riparianScore(null), 1e-9)   // no data → neutral, not penalised
        assertEquals(0.45, MycoMath.riparianScore(5000.0), 1e-9) // far → neutral
    }

    @Test
    fun `rich canopy score blends the three layers within range`() {
        val s = MycoMath.richCanopyScore(canopyPct = 75.0, ndvi = 0.7, worldCoverClass = 10, speciesId = "cortinarius_archeri")
        assertTrue("expected a strong woodland score, got $s", s > 0.8)
        // All-null inputs collapse to the neutral midpoints, never out of range.
        val neutral = MycoMath.richCanopyScore(null, null, null, "amanita_muscaria")
        assertTrue(neutral in 0.0..1.0)
    }

    @Test
    fun `soil pH fitness peaks in the slightly acidic to neutral range`() {
        assertEquals(1.0, MycoMath.soilPhFitness(6.0), 1e-9)   // ideal
        assertEquals(1.0, MycoMath.soilPhFitness(7.0), 1e-9)   // neutral edge
        assertTrue(MycoMath.soilPhFitness(3.5) < MycoMath.soilPhFitness(6.0)) // strongly acidic worse
        assertTrue(MycoMath.soilPhFitness(8.5) < MycoMath.soilPhFitness(7.0)) // alkaline falls off
        assertEquals(0.6, MycoMath.soilPhFitness(null), 1e-9)  // no data → neutral
    }

    @Test
    fun `soil drainage fitness favours loam over clay and pure sand`() {
        assertEquals(1.0, MycoMath.soilDrainageFitness(40.0), 1e-9)        // loamy → ideal
        assertTrue(MycoMath.soilDrainageFitness(95.0) < 1.0)               // very sandy → dries out
        assertTrue(MycoMath.soilDrainageFitness(5.0) < 1.0)               // very clayey → waterlogs
        assertEquals(0.6, MycoMath.soilDrainageFitness(null), 1e-9)        // no data → neutral
    }

    @Test
    fun `rich soil score stays in range and is neutral with no data`() {
        assertTrue(MycoMath.richSoilScore(6.0, 40.0) > 0.9)
        assertEquals(0.6, MycoMath.richSoilScore(null, null), 1e-9)
        assertTrue(MycoMath.richSoilScore(9.0, 95.0) in 0.0..1.0)
    }

    @Test
    fun `twi wetness score rewards moist hollows over dry ridges`() {
        assertEquals(1.0, MycoMath.twiWetnessScore(10.0), 1e-9)            // moist footslope → ideal
        assertTrue(MycoMath.twiWetnessScore(2.0) < MycoMath.twiWetnessScore(10.0)) // dry ridge worse
        assertTrue(MycoMath.twiWetnessScore(18.0) < MycoMath.twiWetnessScore(10.0)) // waterlogged tapers off
        assertEquals(0.5, MycoMath.twiWetnessScore(null), 1e-9)            // no data → neutral
    }

    @Test
    fun `host groups are derived from habitat and substrate text`() {
        val pineBirch = MycoMath.hostGroupsFor(
            listOf("Conifer Plantation", "Exotic Deciduous Woodlands"),
            listOf("Soil (mycorrhizal with Pinus radiata, Birch)")
        )
        assertTrue(MycoMath.HostGroup.NEEDLELEAF in pineBirch)
        assertTrue(MycoMath.HostGroup.DECIDUOUS_BROADLEAF in pineBirch)

        val euc = MycoMath.hostGroupsFor(listOf("Eucalypt Woodland"), listOf("Base of living trees (Eucalyptus)"))
        assertTrue(MycoMath.HostGroup.EVERGREEN_BROADLEAF in euc)

        // Dung/pasture saprobes are not tree-bound.
        val dung = MycoMath.hostGroupsFor(listOf("Pasture / Grazing Land"), listOf("Cattle dung"))
        assertTrue(dung.isEmpty())
    }

    @Test
    fun `host tree match rewards the right forest type and is neutral off-grid`() {
        val pine = setOf(MycoMath.HostGroup.NEEDLELEAF)
        assertEquals(1.0, MycoMath.hostTreeMatchScore(1, pine), 1e-9)      // evergreen-needleleaf → host present
        assertTrue(MycoMath.hostTreeMatchScore(2, pine) < 0.6)            // eucalypt forest, wrong host
        assertTrue(MycoMath.hostTreeMatchScore(5, pine) > 0.7)           // mixed forest → likely host
        assertEquals(0.6, MycoMath.hostTreeMatchScore(2, emptySet()), 1e-9) // not tree-bound → neutral
        assertEquals(0.55, MycoMath.hostTreeMatchScore(null, pine), 1e-9)   // no data → mild neutral
    }

    @Test
    fun `seasonal fitness peaks mid-window and falls off outside it`() {
        // Autumn window April(4)–June(6): peak ≈ mid-May (day ~120).
        val peak = MycoMath.seasonalFitness(135, 4, 6)      // ~mid-May (peak)
        val shoulder = MycoMath.seasonalFitness(170, 4, 6)  // in the shoulder grace zone
        val offSeason = MycoMath.seasonalFitness(330, 4, 6) // late Nov
        assertEquals(1.0, peak, 1e-9)
        assertTrue("shoulder should be a partial, non-zero score", shoulder in 0.01..0.99)
        assertEquals(0.0, offSeason, 1e-9)
    }

    @Test
    fun `seasonal fitness wraps across the new year`() {
        // Summer window November(11)–February(2): peak ≈ late December.
        val midSummer = MycoMath.seasonalFitness(362, 11, 2) // ~28 Dec
        val midWinter = MycoMath.seasonalFitness(166, 11, 2) // ~mid-June (opposite)
        assertEquals(1.0, midSummer, 1e-9)
        assertTrue("winter should score far below the summer peak", midWinter < 0.2)
        // A day just inside the wrap (early Jan) should still score well.
        assertTrue(MycoMath.seasonalFitness(10, 11, 2) > 0.6)
    }

    @Test
    fun `short single-month season keeps a sensible plateau`() {
        // start == end (May only) must not collapse to a single high-scoring day.
        val atPeak = MycoMath.seasonalFitness(135, 5, 5)     // mid-May
        val tenDaysOff = MycoMath.seasonalFitness(145, 5, 5) // still within the floored core
        assertEquals(1.0, atPeak, 1e-9)
        assertEquals(1.0, tenDaysOff, 1e-9)
    }

    @Test
    fun `terrain moisture is scale-aware and backward compatible`() {
        val cell = 100.0
        val gentle = listOf(99.5, 99.5, 100.5, 100.5)  // ~1 m relief across the cell
        // Default param reproduces the legacy (500 m) calibration exactly.
        assertEquals(
            MycoMath.terrainMoistureScore(cell, gentle),
            MycoMath.terrainMoistureScore(cell, gentle, 500.0),
            1e-9
        )
        // At a fine (15 m) spacing the same 1 m relief reads as a real slope, so
        // the score discriminates instead of collapsing to the flat baseline.
        assertTrue(
            MycoMath.terrainMoistureScore(cell, gentle, 15.0) >
                MycoMath.terrainMoistureScore(cell, gentle, 500.0)
        )
    }

    @Test
    fun `slope aspect is scale-aware and backward compatible`() {
        // South-facing: terrain ~1 m higher to the north, lower to the south.
        val sFineDefault = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0)
        val sFine500 = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0, 500.0)
        val sFine15 = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0, 15.0)
        assertEquals(sFineDefault, sFine500, 1e-9)             // default == legacy 500 m
        assertTrue(sFine15 > sFine500)                          // fine scale sees the aspect
        assertTrue(sFine15 > 0.85)                              // clearly south-facing
    }

    @Test
    fun `rainfall trigger detects a multi-day soaking pulse`() {
        // 45 days, all light (5mm) except a 3-day soak ~15 days ago where no single
        // 2-day window hits 20mm but the 3-day total (24mm) does.
        val rain = MutableList(45) { 5.0 }
        val soakIdx = 45 - 15
        rain[soakIdx] = 9.0; rain[soakIdx - 1] = 9.0; rain[soakIdx - 2] = 9.0  // 3-day = 27mm
        val score = MycoMath.rainfallTriggerScore(rain)
        // Background-only (no pulse) baseline for comparison.
        val baseline = MycoMath.rainfallTriggerScore(MutableList(45) { 5.0 })
        assertTrue("multi-day pulse should raise the trigger score", score > baseline)
    }

    @Test
    fun `prediction confidence rises with evidence and real data`() {
        val none = MycoMath.predictionConfidence(0, 0.0, hasEnvLayers = false, hasElevation = false)
        val layersOnly = MycoMath.predictionConfidence(0, 0.0, hasEnvLayers = true, hasElevation = true)
        val full = MycoMath.predictionConfidence(4, 3.0, hasEnvLayers = true, hasElevation = true)

        assertTrue(none < 0.33)                                  // pure climate guess → Low
        assertEquals("Low", MycoMath.confidenceLabel(none))
        assertTrue(layersOnly in 0.33..0.66)                     // real layers, no records → Medium
        assertEquals("Medium", MycoMath.confidenceLabel(layersOnly))
        assertTrue(full >= 0.66)                                 // records + full layers → High
        assertEquals("High", MycoMath.confidenceLabel(full))
        assertTrue(full > layersOnly && layersOnly > none)       // monotonic with data richness
    }
}
