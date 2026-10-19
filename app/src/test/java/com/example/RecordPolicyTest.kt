package com.example

import com.example.model.UserSighting
import com.example.util.OccurrenceIdentity
import com.example.util.SightingPolicy
import org.junit.Assert.*
import org.junit.Test

class RecordPolicyTest {
    private fun record(private: Boolean, notes: String) = UserSighting(1, "unidentified", -36.75, 144.28, 1700000000000, null, notes, private)
    @Test fun privateRecordsNeverReachSharedCsv() {
        val csv = SightingPolicy.publicCsv(listOf(record(true, "SECRET_LOCATION"), record(false, "visible")))
        assertFalse(csv.contains("SECRET_LOCATION")); assertTrue(csv.contains("visible"))
        assertEquals(2, csv.split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun csvQuotesMultilineNotesAndNeutralisesFormulaInjection() {
        val csv = SightingPolicy.publicCsv(listOf(record(false, "=HYPERLINK(\"bad\")\nsecond line")))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\nsecond line\""))
    }
    @Test fun invalidCoordinatesAreRejectedWithoutDefaults() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 91.0, -91.0).forEach { assertFalse(SightingPolicy.validCoordinates(it, 144.0)) }
        assertFalse(SightingPolicy.validCoordinates(-36.0, 181.0))
        assertTrue(SightingPolicy.validCoordinates(0.0, 0.0))
    }
    @Test fun providerIdsCannotCollideAndSyndicatedOccurrencesCollapse() {
        assertNotEquals(OccurrenceIdentity.key("GBIF", "42"), OccurrenceIdentity.key("ALA", "42"))
        assertEquals(OccurrenceIdentity.key("iNaturalist", "42"), OccurrenceIdentity.canonical("GBIF", "999", "https://www.inaturalist.org/observations/42"))
    }
}
