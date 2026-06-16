package com.example

import com.example.model.Species
import com.example.util.SpeciesSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue search matching/ranking. Motivating bug: searching "golden tops"
 * (the common Australian name for Psilocybe subaeruginosa) returned nothing
 * because the entry was filed under "Gold-top" and matching was raw substring.
 *
 * The matcher is now lenient on purpose — it should rank close/fuzzy matches as
 * suggestions rather than show an empty screen.
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
    private val goldenTeacher = species(
        "Psilocybe cubensis",
        "Psilocybe",
        listOf("Golden Teacher", "Gold Top", "Gold Tops", "Golden Cap", "Cubes", "Cube")
    )
    private val flyAgaric = species("Amanita muscaria", "Amanita", listOf("Fly Agaric"))
    private val all = listOf(goldTop, goldenTeacher, flyAgaric)

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
    fun `golden teacher ranks cubensis first but still suggests the gold top`() {
        val ranked = SpeciesSearch.rank(all, "golden teacher")
        assertEquals(goldenTeacher, ranked.first())
        // The gold top shares "golden", so it's offered as a lower suggestion…
        assertTrue(goldTop in ranked)
        // …and the unrelated fly agaric is not.
        assertFalse(flyAgaric in ranked)
    }

    @Test
    fun `typos still surface a suggestion`() {
        assertTrue(SpeciesSearch.matchesName(flyAgaric, "amanta"))    // missing 'i'
        assertTrue(SpeciesSearch.matchesName(goldTop, "psilocibe"))   // 'y' -> 'i'
    }

    @Test
    fun `empty query returns the list unchanged`() {
        assertTrue(SpeciesSearch.matchesName(goldTop, "   "))
        assertEquals(all, SpeciesSearch.rank(all, ""))
    }

    @Test
    fun `clearly unrelated query matches nothing`() {
        assertTrue(SpeciesSearch.rank(all, "boletus").isEmpty())
    }
}
