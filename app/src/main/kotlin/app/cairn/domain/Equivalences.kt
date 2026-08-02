package app.cairn.domain

import kotlin.math.abs
import kotlin.math.ln

/**
 * Le moteur d'équivalences.
 *
 * C'est la réponse de Cairn à la carte de Strava. Une carte répond à "où
 * êtes-vous allé". Une équivalence répond à "qu'est-ce que ça représente" —
 * question à laquelle on peut répondre sans connaître un seul lieu.
 *
 * "412 km ce mois-ci" ne dit rien à personne. "412 km, soit Paris–Lyon" est
 * immédiatement parlant, et ne révèle strictement rien : l'utilisateur n'est
 * jamais allé à Lyon.
 */
object Equivalences {

    data class Ref(val label: String, val valueSi: Double, val emoji: String)

    /** Repères de distance, en mètres. Valeurs approchées, à vol d'oiseau. */
    private val distanceRefs = listOf(
        Ref("un stade d'athlétisme", 400.0, "🏟"),
        Ref("la traversée de Central Park", 4_000.0, "🌳"),
        Ref("les 10 km du dimanche", 10_000.0, "👟"),
        Ref("un semi-marathon", 21_097.0, "🏃"),
        Ref("la traversée de la Manche", 34_000.0, "🌊"),
        Ref("un marathon", 42_195.0, "🏅"),
        Ref("Paris–Chartres", 90_000.0, "⛪"),
        Ref("Paris–Lille", 225_000.0, "🏭"),
        Ref("Paris–Bruxelles", 264_000.0, "🧇"),
        Ref("Paris–Lyon", 392_000.0, "🦁"),
        Ref("la traversée des Alpes (GR5)", 620_000.0, "🏔"),
        Ref("Paris–Marseille", 660_000.0, "⚓"),
        Ref("le chemin de Compostelle", 1_530_000.0, "🐚"),
        Ref("un Tour de France", 3_400_000.0, "🚴"),
        Ref("tout le littoral français", 5_500_000.0, "🏖"),
        Ref("Paris–New York", 5_837_000.0, "🗽"),
        Ref("la traversée de la Russie", 9_000_000.0, "🚂"),
        Ref("le diamètre de la Terre", 12_742_000.0, "🌍"),
        Ref("le tour de la Terre", 40_075_000.0, "🌐"),
        Ref("la Terre–Lune", 384_400_000.0, "🌕"),
    )

    /** Repères de dénivelé, en mètres. */
    private val ascentRefs = listOf(
        Ref("la tour Eiffel", 330.0, "🗼"),
        Ref("l'Empire State Building", 381.0, "🏙"),
        Ref("la Burj Khalifa", 828.0, "🏗"),
        Ref("le puy de Dôme", 1_465.0, "🌋"),
        Ref("le mont Ventoux", 1_610.0, "🚴"),
        Ref("le Mont-Blanc", 4_808.0, "🏔"),
        Ref("le Kilimandjaro", 5_895.0, "🐘"),
        Ref("l'Everest", 8_849.0, "🗻"),
        Ref("l'altitude de croisière d'un long-courrier", 11_000.0, "✈"),
        Ref("la ligne de Kármán, frontière de l'espace", 100_000.0, "🚀"),
    )

    /**
     * Choisit le repère qui parle le mieux pour une valeur donnée : celui dont
     * le rapport tombe entre 1 et 10. En dessous de 1 on n'a "rien fait", au
     * dessus de 10 le chiffre redevient abstrait.
     */
    private fun pick(refs: List<Ref>, valueSi: Double): Ref? {
        if (valueSi <= 0) return null
        val ideal = refs.filter { valueSi / it.valueSi in SPEAKING_RATIO }
        if (ideal.isNotEmpty()) return ideal.minByOrNull { valueSi / it.valueSi }
        // Rien de bien calibré : on prend le repère le plus proche en ordre de grandeur.
        return refs.minByOrNull { abs(ln(valueSi / it.valueSi)) }
    }

    /** Phrase du type "3,2 × le Mont-Blanc" ou "12 % du tour de la Terre". */
    private fun phrase(ref: Ref, valueSi: Double): String {
        val ratio = valueSi / ref.valueSi
        return if (ratio >= 1.0) {
            "${fmtRatio(ratio)} × ${ref.label}"
        } else {
            "${fmtPercent(ratio * PERCENT)} % de ${ref.label}"
        }
    }

    data class Result(val emoji: String, val text: String, val ratio: Double)

    fun forDistance(meters: Double): Result? =
        pick(distanceRefs, meters)?.let { Result(it.emoji, phrase(it, meters), meters / it.valueSi) }

    fun forAscent(meters: Double): Result? =
        pick(ascentRefs, meters)?.let { Result(it.emoji, phrase(it, meters), meters / it.valueSi) }

    /**
     * Le prochain palier atteignable, avec la part déjà parcourue. Donne un
     * objectif sans jamais parler de lieu — l'inverse d'un segment Strava.
     */
    data class Milestone(val emoji: String, val label: String, val targetM: Double, val progress: Double)

    fun nextDistanceMilestone(meters: Double): Milestone? =
        distanceRefs.firstOrNull { it.valueSi > meters }
            ?.let { Milestone(it.emoji, it.label, it.valueSi, (meters / it.valueSi).coerceIn(0.0, 1.0)) }

    fun nextAscentMilestone(meters: Double): Milestone? =
        ascentRefs.firstOrNull { it.valueSi > meters }
            ?.let { Milestone(it.emoji, it.label, it.valueSi, (meters / it.valueSi).coerceIn(0.0, 1.0)) }

    /** Tous les repères déjà dépassés — la vitrine à trophées. */
    fun distanceUnlocked(meters: Double): List<Ref> = distanceRefs.filter { it.valueSi <= meters }
    fun ascentUnlocked(meters: Double): List<Ref> = ascentRefs.filter { it.valueSi <= meters }

    // ------------------------------------------------------------------ carbone

    /**
     * Un arbre tempéré absorbe de l'ordre de 25 kg de CO2 par an.
     * Un repas avec du bœuf pèse environ 7 kg CO2e.
     */
    fun carbonPhrase(grams: Double): String {
        val kg = grams / GRAMS_PER_KG
        return when {
            kg < 1 -> "moins d'un kilo de CO₂"
            kg < BEEF_MEAL_CEILING_KG -> "${fmtRatio(kg / BEEF_MEAL_KG)} repas avec du bœuf"
            else -> "${fmtRatio(kg / TREE_YEAR_KG)} arbres pendant un an pour l'absorber"
        }
    }

    /** CO2 évité en faisant à la force du mollet ce qui aurait pu être fait en voiture. */
    fun avoidedCarbonGrams(sessions: List<Session>): Double =
        sessions.filter { it.mode.isHumanPowered }
            .sumOf { Mode.CAR.gramsCo2PerKm * (it.distanceM / METERS_PER_KM) }

    // ---------------------------------------------------------------- formatage

    private fun fmtRatio(r: Double): String = when {
        r >= HUNDRED -> r.toInt().toString()
        r >= TEN -> "%.0f".format(r)
        else -> "%.1f".format(r).removeSuffix(",0").removeSuffix(".0")
    }

    private fun fmtPercent(p: Double): String = when {
        p >= TEN -> "%.0f".format(p)
        p >= 1 -> "%.1f".format(p)
        else -> "%.2f".format(p)
    }

    // ----------------------------------------------------------------- seuils

    /**
     * Un rapport entre 1 et 10 est celui qui "parle" : en dessous on n'a rien
     * accompli, au dessus le chiffre redevient abstrait.
     */
    private val SPEAKING_RATIO = 1.0..10.0

    private const val PERCENT = 100.0
    private const val TEN = 10.0
    private const val HUNDRED = 100.0
    private const val GRAMS_PER_KG = 1_000.0

    /** Un repas avec du bœuf pèse de l'ordre de 7 kg CO2e. */
    private const val BEEF_MEAL_KG = 7.0
    private const val BEEF_MEAL_CEILING_KG = 70.0

    /** Un arbre tempéré absorbe de l'ordre de 25 kg de CO2 par an. */
    private const val TREE_YEAR_KG = 25.0
}
