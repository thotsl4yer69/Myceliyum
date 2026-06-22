package com.example

import com.example.util.FungiSafety
import com.example.util.FungiSafety.Edibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edibility-classifier tests. The point of the curated map is accuracy, so
 * these pin the cases that naive text/genus matching gets wrong — look-alike
 * mentions must not flag the species, and a toxic species is never reported
 * edible just because its note names an edible relative.
 */
class FungiSafetyTest {

    @Test
    fun `only the two confirmed-lethal species are deadly`() {
        assertEquals(Edibility.DEADLY, FungiSafety.edibilityOf("amanita_phalloides"))
        assertEquals(Edibility.DEADLY, FungiSafety.edibilityOf("galerina_marginata"))
        val deadlyCount = FungiSafety.EDIBILITY.values.count { it == Edibility.DEADLY }
        assertEquals("only phalloides + galerina should be DEADLY", 2, deadlyCount)
    }

    @Test
    fun `deadly-look-alike species are NOT themselves flagged deadly`() {
        // Each of these has a deadly look-alike named in its notes, but the
        // species itself is not deadly — the old genus/keyword rules got these wrong.
        assertEquals(Edibility.PSYCHOACTIVE, FungiSafety.edibilityOf("psilocybe_cyanescens")) // "DEADLY Galerina" look-alike
        assertEquals(Edibility.EDIBLE, FungiSafety.edibilityOf("agaricus_campestris"))         // "deadly Amanita" look-alike
        assertEquals(Edibility.EDIBLE, FungiSafety.edibilityOf("marasmius_oreades"))           // "deadly Clitocybe" look-alike
        assertEquals(Edibility.INEDIBLE, FungiSafety.edibilityOf("hypholoma_brunneum"))        // "DEADLY Galerina" look-alike
        assertFalse(FungiSafety.edibilityOf("agaricus_campestris") == Edibility.DEADLY)
    }

    @Test
    fun `a toxic species is never reported edible even if its note names an edible relative`() {
        // Yellow-stainer: "TOXIC ... distinguishes it from edible Agaricus".
        assertEquals(Edibility.POISONOUS, FungiSafety.edibilityOf("agaricus_xanthodermus"))
        // Earthball: "TOXIC if eaten ... separates earthballs from edible puffballs".
        assertEquals(Edibility.POISONOUS, FungiSafety.edibilityOf("scleroderma_cepa"))
    }

    @Test
    fun `accuracy over caution - genus is not a blanket flag`() {
        // Amanita muscaria is toxic, not deadly; not every Amanita is deadly.
        assertEquals(Edibility.POISONOUS, FungiSafety.edibilityOf("amanita_muscaria"))
        // The edible oyster keeps its accurate label despite a toxic look-alike note.
        assertEquals(Edibility.EDIBLE, FungiSafety.edibilityOf("pleurotus_ostreatus"))
    }

    @Test
    fun `psilocybin species are psychoactive`() {
        for (id in listOf("psilocybe_subaeruginosa", "psilocybe_cubensis", "panaeolus_cyanescens")) {
            assertEquals(id, Edibility.PSYCHOACTIVE, FungiSafety.edibilityOf(id))
        }
    }

    @Test
    fun `established edibles are labelled edible`() {
        for (id in listOf("lactarius_deliciosus", "suillus_luteus", "lepista_nuda", "lycoperdon_perlatum")) {
            assertEquals(id, Edibility.EDIBLE, FungiSafety.edibilityOf(id))
        }
    }

    @Test
    fun `isDangerous is true only for deadly and poisonous`() {
        assertTrue(FungiSafety.isDangerous(Edibility.DEADLY))
        assertTrue(FungiSafety.isDangerous(Edibility.POISONOUS))
        assertFalse(FungiSafety.isDangerous(Edibility.EDIBLE))
        assertFalse(FungiSafety.isDangerous(Edibility.PSYCHOACTIVE))
        assertFalse(FungiSafety.isDangerous(Edibility.INEDIBLE))
        assertFalse(FungiSafety.isDangerous(Edibility.UNKNOWN))
    }

    @Test
    fun `uncurated ids default to unknown and every level has a label`() {
        assertEquals(Edibility.UNKNOWN, FungiSafety.edibilityOf("not_a_real_species"))
        for (e in Edibility.values()) {
            assertTrue("label for $e should be non-blank", FungiSafety.label(e).isNotBlank())
        }
    }
}
