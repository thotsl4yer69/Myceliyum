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
}
