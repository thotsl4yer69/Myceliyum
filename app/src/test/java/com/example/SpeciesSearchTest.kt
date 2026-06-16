package com.example

import com.example.model.Species
import com.example.util.SpeciesSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue search matching. The motivating bug: searching "golden tops" (the
 * common Australian name for Psilocybe subaeruginosa) returned nothing because
 * the entry was filed under "Gold-top" and matching was raw substring only.
 */
class SpeciesSearchTest {

    private fun species(
        scientificName: String,
        genus: String,
        commonNames: List<String>
    ) = Species(
        id = scientificName.lowercase().replace(" ", "_"),
        scientificName = scientificName,
        commonNames = commonNames,
        genus = genus,
        family = "",
        habitatTypes = emptyList(),
        substrates = emptyList(),
        seasonStart = 1,
        seasonEnd = 12,
        capDescription = "",
        gillDescription = "",
        stemDescription = "",
        sporeColor = "",
        bruisingReaction = "",
        lookAlikes = emptyList(),
        notes = "",
        imageUrls = emptyList()
    )

    private val goldTop = species(
        "Psilocybe subaeruginosa",
        "Psilocybe",
        listOf("Gold Top", "Golden Top", "Gold Tops", "Golden Tops", "Subs", "Sub", "Gold-top")
    )

    @Test
    fun `golden tops finds the gold top`() {
        assertTrue(SpeciesSearch.matchesName(goldTop, "golden tops"))
    }

    @Test
    fun `hyphen, spacing and case variants all match`() {
        for (q in listOf("Gold-top", "gold top", "GOLD TOP", "gold tops", "golden top")) {
            assertTrue("expected '$q' to match", SpeciesSearch.matchesName(goldTop, q))
        }
    }

    @Test
    fun `scientific name and genus still match`() {
        assertTrue(SpeciesSearch.matchesName(goldTop, "subaeruginosa"))
        assertTrue(SpeciesSearch.matchesName(goldTop, "psilocybe"))
    }

    @Test
    fun `word order does not matter`() {
        assertTrue(SpeciesSearch.matchesName(goldTop, "top gold"))
    }

    @Test
    fun `empty query matches everything`() {
        assertTrue(SpeciesSearch.matchesName(goldTop, ""))
        assertTrue(SpeciesSearch.matchesName(goldTop, "   "))
    }

    @Test
    fun `unrelated multi-word query does not match`() {
        // "golden teacher" is a cultivated Psilocybe cubensis strain, not this
        // species — it must not spuriously resolve to the gold top.
        assertFalse(SpeciesSearch.matchesName(goldTop, "golden teacher"))
        assertFalse(SpeciesSearch.matchesName(goldTop, "fly agaric"))
    }
}
