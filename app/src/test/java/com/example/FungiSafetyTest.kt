package com.example

import com.example.model.Species
import com.example.util.FungiSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety classifier tests. The contract is deliberately one-sided: the
 * classifier may only ever *warn*, never reassure. A wrong "this is edible"
 * could be lethal, so the tests guard that danger always wins over any edible
 * wording and that whole dangerous genera are flagged even when a note is terse.
 */
class FungiSafetyTest {

    private fun species(
        scientificName: String,
        genus: String,
        notes: String
    ) = Species(
        id = scientificName.lowercase().replace(" ", "_"),
        scientificName = scientificName,
        commonNames = emptyList(),
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
        notes = notes,
        imageUrls = emptyList()
    )

    @Test
    fun `the death cap is flagged deadly from its notes`() {
        val deathCap = species(
            "Amanita phalloides", "Amanita",
            "DEADLY. Contains amatoxins that cause delayed liver and kidney failure."
        )
        assertEquals(FungiSafety.DangerLevel.DEADLY, FungiSafety.dangerLevel(deathCap))
    }

    @Test
    fun `funeral bell is flagged deadly even without genus rule`() {
        val galerina = species(
            "Galerina marginata", "Galerina",
            "DEADLY — contains the same amatoxins as the Death Cap."
        )
        assertEquals(FungiSafety.DangerLevel.DEADLY, FungiSafety.dangerLevel(galerina))
    }

    @Test
    fun `whole deadly genera are flagged even with a terse note`() {
        // A Cortinarius with no scary keyword in its note is still deadly-genus.
        val webcap = species("Cortinarius archeri", "Cortinarius", "A striking blue webcap of southern forests.")
        assertEquals(FungiSafety.DangerLevel.DEADLY, FungiSafety.dangerLevel(webcap))
    }

    @Test
    fun `ghost fungus is flagged toxic by genus`() {
        val ghost = species("Omphalotus nidiformis", "Omphalotus", "Glows in the dark; grows on dead wood.")
        assertEquals(FungiSafety.DangerLevel.TOXIC, FungiSafety.dangerLevel(ghost))
    }

    @Test
    fun `danger always wins over any edible wording`() {
        // An Amanita whose note even mentions "edible" must never be downgraded.
        val tricky = species(
            "Amanita example", "Amanita",
            "Some claim parts are edible overseas, but assume toxic."
        )
        assertEquals(FungiSafety.DangerLevel.DEADLY, FungiSafety.dangerLevel(tricky))
        assertNotNull(FungiSafety.warningHeadline(tricky))
    }

    @Test
    fun `an unflagged species gets no warning headline but still carries the disclaimer`() {
        val woodEar = species(
            "Auricularia cornea", "Auricularia",
            "The southern 'wood ear'; edible cooked but bland. Confirm ID first."
        )
        assertEquals(FungiSafety.DangerLevel.NONE, FungiSafety.dangerLevel(woodEar))
        assertNull(FungiSafety.warningHeadline(woodEar))
        // The classifier never asserts "safe": the universal disclaimer covers it.
        assertTrue(FungiSafety.UNIVERSAL_DISCLAIMER.contains("Never eat", ignoreCase = true))
    }

    @Test
    fun `flagged species expose a non-empty headline that names the hazard`() {
        val deathCap = species("Amanita phalloides", "Amanita", "DEADLY amatoxin mushroom.")
        val headline = FungiSafety.warningHeadline(deathCap)
        assertNotNull(headline)
        assertTrue(headline!!.contains("DEADLY"))
    }
}
