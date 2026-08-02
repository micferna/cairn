package app.cairn.domain

/**
 * Les modes de déplacement que Cairn sait distinguer.
 *
 * Chaque mode porte son propre facteur d'émission et sa propre couleur, parce
 * que le produit ne raconte pas "où tu es allé" mais "comment tu t'es déplacé".
 */
// Facteurs d'émission en gCO2e par km et par passager.
// Ordres de grandeur ADEME / Base Empreinte, moyennes françaises.
private const val CO2_NONE = 0.0
private const val CO2_ELECTRIC_SCOOTER = 25.0
private const val CO2_CAR_AVERAGE = 193.0
private const val CO2_TRAIN = 6.0
private const val CO2_PLANE = 230.0

enum class Mode(
    val label: String,
    /** gCO2e par km et par passager. Ordres de grandeur ADEME / Base Empreinte. */
    val gramsCo2PerKm: Double,
    /** true si la distance peut être mesurée sans GPS (podomètre seul). */
    val measurableWithoutGps: Boolean,
) {
    FOOT("Marche", CO2_NONE, true),
    RUN("Course", CO2_NONE, true),
    BIKE("Vélo", CO2_NONE, false),
    SCOOTER("Trottinette", CO2_ELECTRIC_SCOOTER, false),
    CAR("Voiture", CO2_CAR_AVERAGE, false),
    TRAIN("Train", CO2_TRAIN, false),
    PLANE("Avion", CO2_PLANE, false),
    UNKNOWN("Indéterminé", CO2_NONE, false);

    val isHumanPowered: Boolean get() = this == FOOT || this == RUN || this == BIKE

    /** Couleur du mode, en hexadécimal, partagée par l'écran et la carte exportée. */
    fun hex(): String = when (this) {
        FOOT -> "#7FA88A"
        RUN -> "#C4D97A"
        BIKE -> "#6FA8C4"
        SCOOTER -> "#A98BC4"
        CAR -> "#D98B4E"
        TRAIN -> "#C4A24E"
        PLANE -> "#C46F6F"
        UNKNOWN -> "#5A6660"
    }
}

/**
 * Un déplacement continu dans un mode donné.
 *
 * Remarquez ce que cette classe ne contient pas : aucune latitude, aucune
 * longitude, aucun horodatage à la seconde près. [hourBucket] est l'heure
 * arrondie — parce que "part en courant à 06h32 tous les mardis" est déjà une
 * signature identifiante, même sans coordonnées.
 */
data class Session(
    val id: Long = 0,
    /** Date locale ISO, ex. "2026-08-02". */
    val day: String,
    /** Heure locale arrondie, 0..23. Volontairement grossier. */
    val hourBucket: Int,
    val mode: Mode,
    val durationS: Long,
    val distanceM: Double,
    val steps: Int,
    val ascentM: Double,
    val descentM: Double,
    val avgSpeedMs: Double,
    val maxSpeedMs: Double,
    /** Confiance du classifieur, 0..1. */
    val confidence: Double,
    /** Comment la distance a été obtenue : PEDOMETER ou GPS_DELTA. */
    val source: DistanceSource,
    /** Explication lisible du classement, ex. "cadence nulle + vitesse stable". */
    val reason: String = "",
    /** Forme anonymisée, sans échelle ni orientation. Nullable et opt-in. */
    val shape: Shape? = null,
    /** L'utilisateur a corrigé le mode : ce n'est plus une déduction. */
    val corrected: Boolean = false,
    /** Issue du comptage passif du podomètre, hors session mesurée. */
    val passive: Boolean = false,
) {
    val co2Grams: Double get() = mode.gramsCo2PerKm * (distanceM / METERS_PER_KM)
}

internal const val METERS_PER_KM = 1_000.0

enum class DistanceSource {
    /** Compté par le podomètre matériel. Zéro information de lieu. */
    PEDOMETER,

    /** Somme de deltas GPS calculés en RAM. Les points n'ont jamais touché le disque. */
    GPS_DELTA,
}

/** Agrégat journalier. Recalculable à partir des sessions. */
data class DayStat(
    val day: String,
    val steps: Int,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val activeS: Long,
)

/**
 * Une trace dépouillée de tout ce qui permettrait de la localiser :
 * recentrée sur l'origine, pivotée d'un angle aléatoire, ramenée à une boîte
 * unitaire (donc sans échelle), et simplifiée. Il ne reste que le *dessin*.
 */
data class Shape(val points: List<Pair<Float, Float>>)

/** Une ligne du registre de transparence. */
data class LedgerEntry(
    val id: Long = 0,
    val atMs: Long,
    val kind: LedgerKind,
    val detail: String,
    val bytes: Int = 0,
)

enum class LedgerKind(val label: String) {
    SENSOR_OPEN("Capteur ouvert"),
    SENSOR_CLOSE("Capteur fermé"),
    WRITE("Écriture disque"),
    DISCARD("Donnée jetée"),
    DELETE("Suppression"),
    EXPORT("Export manuel"),
}
