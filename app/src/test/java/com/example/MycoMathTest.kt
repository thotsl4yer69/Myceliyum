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
}
