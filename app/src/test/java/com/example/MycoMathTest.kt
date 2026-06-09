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
}
