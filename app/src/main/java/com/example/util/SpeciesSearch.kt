package com.example.util

import com.example.model.Species

/**
 * Name matching for the species catalogue search box.
 *
 * The old search did a raw, case-insensitive `contains` against the scientific
 * name, genus and each common name. That is brittle for the way people actually
 * type fungus names: it can't bridge punctuation ("Gold-top" vs "gold top"),
 * singular/plural ("Golden Top" vs "golden tops"), or word order. So a curated
 * species like *Psilocybe subaeruginosa* (filed under "Gold-top") was invisible
 * to anyone searching "golden tops".
 *
 * This matcher normalises both sides (lowercase, fold every run of
 * non-alphanumerics to a single space) and then requires every whitespace token
 * of the query to appear in the combined name haystack, order-independently and
 * tolerant of a trailing plural "s". That keeps matching forgiving without
 * turning into fuzzy guesswork.
 */
object SpeciesSearch {

    /** Lowercase and fold punctuation/whitespace runs to single spaces. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Token containment, tolerant of a trailing plural "s" on either side. */
    private fun haystackContains(haystack: String, token: String): Boolean {
        if (token.isEmpty()) return true
        if (haystack.contains(token)) return true
        // Fold a trailing plural so "tops" finds "top" and vice versa.
        if (token.length > 3 && token.endsWith("s") && haystack.contains(token.dropLast(1))) return true
        return false
    }

    /**
     * True when [rawQuery] matches the species by scientific name, genus or any
     * common name. An empty/blank query matches everything (the unfiltered list).
     */
    fun matchesName(species: Species, rawQuery: String): Boolean {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return true
        val haystack = normalize(
            buildString {
                append(species.scientificName).append(' ')
                append(species.genus).append(' ')
                append(species.commonNames.joinToString(" "))
            }
        )
        // Every query token must appear; "golden tops", "gold top" and
        // "top gold" all resolve to the same entry.
        return q.split(' ').all { haystackContains(haystack, it) }
    }
}
