package com.example.util

/**
 * Per-species edibility / toxicity, curated for scientific accuracy.
 *
 * Each classification reflects what the species itself is — NOT a coarse
 * genus rule and NOT a blanket "assume the worst". The map was curated from
 * the project's reference notes for every catalogue species, with two
 * deliberate corrections that pure text-matching gets wrong:
 *
 *  - Look-alike mentions never flag the species. e.g. *Psilocybe cyanescens*
 *    ("easily confused with the DEADLY Galerina") is [Edibility.PSYCHOACTIVE],
 *    and *Agaricus campestris* ("a potentially deadly Amanita" look-alike) is
 *    [Edibility.EDIBLE] — neither is DEADLY.
 *  - A toxic species is never reported edible even when its note mentions an
 *    edible relative (e.g. the yellow-stainer *Agaricus xanthodermus* is
 *    [Edibility.POISONOUS], not edible).
 *
 * Only *Amanita phalloides* and *Galerina marginata* — the two confirmed-lethal
 * species in the catalogue — are [Edibility.DEADLY]. Pure and JVM-testable.
 */
object FungiSafety {

    enum class Edibility {
        /** Confirmed lethal if eaten (amatoxins). */
        DEADLY,
        /** Toxic — causes poisoning, occasionally serious, but not reliably lethal. */
        POISONOUS,
        /** Contains psilocybin — psychoactive, not a food species. */
        PSYCHOACTIVE,
        /** Non-toxic but not a culinary species (tough, tiny, woody, bitter, …). */
        INEDIBLE,
        /** Established edible (per the reference catalogue). */
        EDIBLE,
        /** Edibility not characterised for this species. */
        UNKNOWN
    }

    /**
     * Curated edibility by catalogue species id. Species absent from this map
     * default to [Edibility.UNKNOWN] (e.g. notes that make no edibility claim,
     * or species added later that haven't been curated yet).
     */
    val EDIBILITY: Map<String, Edibility> = mapOf(
        // ── Deadly (confirmed lethal) ──
        "amanita_phalloides" to Edibility.DEADLY,
        "galerina_marginata" to Edibility.DEADLY,
        // ── Poisonous (toxic if eaten) ──
        "agaricus_xanthodermus" to Edibility.POISONOUS,
        "amanita_ananiceps" to Edibility.POISONOUS,
        "amanita_muscaria" to Edibility.POISONOUS,
        "amanita_xanthocephala" to Edibility.POISONOUS,
        "austropaxillus_infundibuliformis" to Edibility.POISONOUS,
        "cortinarius_archeri" to Edibility.POISONOUS,
        "cortinarius_austrovenetus" to Edibility.POISONOUS,
        "cortinarius_metallicus" to Edibility.POISONOUS,
        "cortinarius_rotundisporus" to Edibility.POISONOUS,
        "cortinarius_sublargus" to Edibility.POISONOUS,
        "hypholoma_fasciculare" to Edibility.POISONOUS,
        "omphalotus_nidiformis" to Edibility.POISONOUS,
        "ramaria_lorithamnus" to Edibility.POISONOUS,
        "scleroderma_cepa" to Edibility.POISONOUS,
        // ── Psychoactive (psilocybin) ──
        "panaeolus_cyanescens" to Edibility.PSYCHOACTIVE,
        "psilocybe_cubensis" to Edibility.PSYCHOACTIVE,
        "psilocybe_cyanescens" to Edibility.PSYCHOACTIVE,
        "psilocybe_semilanceata" to Edibility.PSYCHOACTIVE,
        "psilocybe_subaeruginosa" to Edibility.PSYCHOACTIVE,
        // ── Inedible (non-toxic but not for the table) ──
        "amanita_grisella" to Edibility.INEDIBLE,
        "amethyst_laccaria" to Edibility.INEDIBLE,
        "anthracophyllum_archeri" to Edibility.INEDIBLE,
        "aseroe_rubra" to Edibility.INEDIBLE,
        "austroboletus_lacunosus" to Edibility.INEDIBLE,
        "boletellus_obscurecoccineus" to Edibility.INEDIBLE,
        "calocera_sinensis" to Edibility.INEDIBLE,
        "calostoma_fuscum" to Edibility.INEDIBLE,
        "clavaria_zollingeri" to Edibility.INEDIBLE,
        "clavulinopsis_sulcata" to Edibility.INEDIBLE,
        "coprinus_comatus" to Edibility.INEDIBLE,
        "dermocybe_canaria" to Edibility.INEDIBLE,
        "entoloma_virescens" to Edibility.INEDIBLE,
        "ganoderma_applanatum" to Edibility.INEDIBLE,
        "geastrum_triplex" to Edibility.INEDIBLE,
        "gymnopilus_junonius" to Edibility.INEDIBLE,
        "hygrocybe_austropratensis" to Edibility.INEDIBLE,
        "hygrocybe_chromolimonea" to Edibility.INEDIBLE,
        "hygrocybe_miniata" to Edibility.INEDIBLE,
        "hypholoma_brunneum" to Edibility.INEDIBLE,
        "ileodictyon_cibarium" to Edibility.INEDIBLE,
        "laccaria_lateritia" to Edibility.INEDIBLE,
        "laetiporus_portentosus" to Edibility.INEDIBLE,
        "leratiomyces_ceres" to Edibility.INEDIBLE,
        "macrolepiota_clelandii" to Edibility.INEDIBLE,
        "marasmius_elegans" to Edibility.INEDIBLE,
        "mycena_interrupta" to Edibility.INEDIBLE,
        "mycena_kuurkacea" to Edibility.INEDIBLE,
        "panellus_stipticus" to Edibility.INEDIBLE,
        "phlebopus_marginatus" to Edibility.INEDIBLE,
        "piptoporus_australiensis" to Edibility.INEDIBLE,
        "pisolithus_arhizus" to Edibility.INEDIBLE,
        "russula_persanguinea" to Edibility.INEDIBLE,
        "schizophyllum_commune" to Edibility.INEDIBLE,
        "stereum_ostrea" to Edibility.INEDIBLE,
        "trametes_versicolor" to Edibility.INEDIBLE,
        "tubaria_rufofulva" to Edibility.INEDIBLE,
        "xylaria_hypoxylon" to Edibility.INEDIBLE,
        // ── Edible (established culinary species) ──
        "agaricus_campestris" to Edibility.EDIBLE,
        "auricularia_cornea" to Edibility.EDIBLE,
        "cyttaria_gunnii" to Edibility.EDIBLE,
        "fistulina_hepatica" to Edibility.EDIBLE,
        "lactarius_deliciosus" to Edibility.EDIBLE,
        "lepista_nuda" to Edibility.EDIBLE,
        "lycoperdon_perlatum" to Edibility.EDIBLE,
        "marasmius_oreades" to Edibility.EDIBLE,
        "pleurotus_ostreatus" to Edibility.EDIBLE,
        "suillus_granulatus" to Edibility.EDIBLE,
        "suillus_luteus" to Edibility.EDIBLE,
        "tremella_mesenterica" to Edibility.EDIBLE
    )

    /** Edibility for a catalogue species id (UNKNOWN if not curated). */
    fun edibilityOf(speciesId: String): Edibility = EDIBILITY[speciesId] ?: Edibility.UNKNOWN

    /** True for species that cause poisoning if eaten (deadly or toxic). */
    fun isDangerous(e: Edibility): Boolean = e == Edibility.DEADLY || e == Edibility.POISONOUS

    /** Short, factual label for the detail banner. */
    fun label(e: Edibility): String = when (e) {
        Edibility.DEADLY -> "Deadly — contains lethal toxins"
        Edibility.POISONOUS -> "Poisonous — toxic if eaten"
        Edibility.PSYCHOACTIVE -> "Psychoactive — contains psilocybin"
        Edibility.INEDIBLE -> "Inedible — not a culinary species"
        Edibility.EDIBLE -> "Edible (per the reference catalogue)"
        Edibility.UNKNOWN -> "Edibility not characterised"
    }
}
