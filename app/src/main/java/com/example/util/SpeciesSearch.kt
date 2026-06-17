package com.example.util

import com.example.model.Species
import kotlin.math.max
import kotlin.math.min

/**
 * Name matching and ranking for the species catalogue search box.
 *
 * The search is deliberately lenient: it should *suggest* close entries rather
 * than show an empty screen when nothing matches exactly. A raw `contains`
 * couldn't even bridge punctuation/plurals (so "golden tops" missed the
 * "Gold-top" entry); this matcher instead scores each species for relevance and
 * the caller shows the best matches, ranked.
 *
 * Scoring per query token (best signal wins), summed across the query:
 *   - exact token / plural-folded token   — strongest
 *   - prefix match either direction       — strong
 *   - substring anywhere in the name      — medium
 *   - fuzzy (small edit distance)         — weak, catches typos like "amanta"
 *
 * A species is a candidate when its total score clears [MIN_SCORE], so a single
 * decent token match is enough to surface it as a suggestion. Results are then
 * ordered by score, so the closest names float to the top.
 */
object SpeciesSearch {

    /** Minimum total relevance for a species to be offered as a suggestion. */
    private const val MIN_SCORE = 5

    /** Lowercase and fold punctuation/whitespace runs to single spaces. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun tokens(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotEmpty() }

    /** Combined searchable text for a species (sci name + genus + common names). */
    private fun haystack(species: Species): String = normalize(
        buildString {
            append(species.scientificName).append(' ')
            append(species.genus).append(' ')
            append(species.commonNames.joinToString(" "))
        }
    )

    /** Bounded Levenshtein edit distance; returns early once it exceeds [maxD]. */
    private fun editDistance(a: String, b: String, maxD: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > maxD) return maxD + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost)
                rowMin = min(rowMin, curr[j])
            }
            if (rowMin > maxD) return maxD + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Best relevance signal for one query token against a species. */
    private fun tokenScore(hay: String, hayTokens: List<String>, qt: String): Int {
        if (qt.isEmpty()) return 0
        // Very short fragments only count as a plain substring, so a single
        // letter doesn't fuzzy-match (and pull in) the whole catalogue.
        if (qt.length < 2) return if (hay.contains(qt)) 2 else 0

        var best = if (hay.contains(qt)) 6 else 0
        for (ht in hayTokens) {
            when {
                ht == qt -> best = max(best, 12)
                qt.endsWith("s") && ht == qt.dropLast(1) -> best = max(best, 11)
                ht.endsWith("s") && qt == ht.dropLast(1) -> best = max(best, 11)
                ht.startsWith(qt) || qt.startsWith(ht) -> best = max(best, 9)
                qt.length >= 4 && ht.length >= 4 -> {
                    val tol = if (max(qt.length, ht.length) >= 7) 2 else 1
                    val d = editDistance(qt, ht, tol)
                    if (d <= tol) best = max(best, 7 - d) // 6 (d=1) or 5 (d=2)
                }
            }
        }
        return best
    }

    /**
     * Relevance of [species] to [rawQuery]; 0 means "not a candidate". An empty
     * query is relevant to everything (the unfiltered list) with a flat score.
     */
    fun relevance(species: Species, rawQuery: String): Int {
        val qTokens = tokens(rawQuery)
        if (qTokens.isEmpty()) return 1
        val total = rawScore(species, qTokens)
        return if (total >= MIN_SCORE) total else 0
    }

    /** Unthresholded relevance sum — used for ordering without dropping. */
    private fun rawScore(species: Species, qTokens: List<String>): Int {
        val hay = haystack(species)
        val hayTokens = hay.split(' ').filter { it.isNotEmpty() }
        return qTokens.sumOf { tokenScore(hay, hayTokens, it) }
    }

    /** Convenience boolean: is this species a search candidate at all? */
    fun matchesName(species: Species, rawQuery: String): Boolean =
        relevance(species, rawQuery) > 0

    /**
     * Filter [species] to the candidates for [rawQuery] and order them by
     * relevance (best first), tie-broken by scientific name for stability.
     * An empty query returns the list unchanged (original catalogue order).
     */
    fun rank(species: List<Species>, rawQuery: String): List<Species> {
        val qTokens = tokens(rawQuery)
        if (qTokens.isEmpty()) return species
        return species.asSequence()
            .map { it to rawScore(it, qTokens) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(
                compareByDescending<Pair<Species, Int>> { it.second }
                    .thenBy { it.first.scientificName }
            )
            .map { it.first }
            .toList()
    }

    /**
     * Order [species] by relevance to [rawQuery] without dropping any. Used for
     * worldwide results, which GBIF has already matched server-side — we only
     * re-rank them (closest names first) rather than re-filter and risk hiding
     * a legitimate hit that matched on a field we don't carry locally.
     */
    fun sortByRelevance(species: List<Species>, rawQuery: String): List<Species> {
        val qTokens = tokens(rawQuery)
        if (qTokens.isEmpty()) return species
        return species.sortedWith(
            compareByDescending<Species> { rawScore(it, qTokens) }
                .thenBy { it.scientificName }
        )
    }
}
