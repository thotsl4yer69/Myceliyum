package com.example.util

import com.example.model.Species

/**
 * Conservative *safety* classification used to surface poisoning warnings on a
 * species' detail card.
 *
 * The data model has no dedicated toxicity field, so this derives a danger
 * level from the catalogue's existing free-text [Species.notes] plus a small
 * set of genus-level heuristics. Two deliberate design choices:
 *
 *  1. It only ever flags DANGER (deadly / toxic). It NEVER asserts a species is
 *     "edible" or "safe" — many prized edibles have deadly look-alikes, and a
 *     field guide must not imply a wild fungus is safe to eat.
 *  2. When the signal is ambiguous it errs toward caution (e.g. whole genera
 *     known to contain deadly members are treated as deadly).
 *
 * Pure and side-effect free, so it can be unit-tested on the JVM.
 */
object FungiSafety {

    enum class DangerLevel { DEADLY, TOXIC, NONE }

    /** Genera that contain deadly species (amatoxins, orellanine, muscarine,
     *  the Paxillus immune reaction…). Any member is treated as potentially
     *  deadly for safety messaging — Amanita alone contains the deadliest fungi
     *  known, and beginners cannot reliably separate the toxic from the benign. */
    private val deadlyGenera = setOf(
        "amanita", "galerina", "lepiota", "cortinarius", "paxillus", "chlorophyllum"
    )

    /** Genera with seriously toxic (rarely lethal) members common in SE Australia. */
    private val toxicGenera = setOf(
        "omphalotus", "hypholoma", "entoloma", "scleroderma", "ramaria", "gymnopilus"
    )

    // These words appear in the catalogue only for genuinely dangerous species
    // (or to warn of them), never as look-alike chatter, so they are reliable.
    private val deadlyWords = listOf("deadly", "fatal", "amatoxin", "death cap", "deathcap")
    private val toxicWords = listOf(
        "highly toxic", "very toxic", "seriously toxic", "assume toxic", "poisonous"
    )

    /** Conservative danger level for [species]. */
    fun dangerLevel(species: Species): DangerLevel {
        val text = species.notes.lowercase()
        val genus = species.genus.lowercase()
        return when {
            deadlyWords.any { it in text } -> DangerLevel.DEADLY
            genus in deadlyGenera -> DangerLevel.DEADLY
            toxicWords.any { it in text } -> DangerLevel.TOXIC
            genus in toxicGenera -> DangerLevel.TOXIC
            else -> DangerLevel.NONE
        }
    }

    /**
     * Prominent warning headline for a flagged-dangerous species, or null when
     * the species isn't flagged (in which case only [UNIVERSAL_DISCLAIMER] shows).
     */
    fun warningHeadline(species: Species): String? = when (dangerLevel(species)) {
        DangerLevel.DEADLY ->
            "DEADLY — this species, or its genus, can kill. Never eat it."
        DangerLevel.TOXIC ->
            "TOXIC — eating this species causes poisoning. Do not eat it."
        DangerLevel.NONE -> null
    }

    /** Field-safety disclaimer shown for EVERY species, dangerous or not. */
    const val UNIVERSAL_DISCLAIMER: String =
        "Identification aid only. Never eat any wild fungus based on this app or an " +
        "AI photo ID — many edible species have toxic or deadly look-alikes. Always " +
        "confirm a find with an expert before eating."
}
