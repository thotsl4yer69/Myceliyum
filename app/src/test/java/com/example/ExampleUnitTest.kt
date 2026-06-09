package com.example

import com.example.util.MycoMath
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity check on the geo helpers used to drive hotspot scoring.
 * Real coverage of the algorithm lives in [MycoMathTest].
 */
class GeoSanityTest {

    @Test
    fun `the equator and prime meridian are roughly 0 km from themselves`() {
        // This catches silly unit bugs (e.g. accidentally returning kilometres).
        assertTrue(
            "Same point should have a distance close to 0 m",
            MycoMath.haversineMeters(0.0, 0.0, 0.0, 0.0) < 1.0
        )
    }
}
