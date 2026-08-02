package app.cairn.domain

/**
 * Résumé statistique d'une fenêtre de ~30 secondes de mouvement.
 * Rien là-dedans ne dit où l'on est : uniquement comment on bouge.
 */
data class MotionWindow(
    /** Pas par minute mesurés par le podomètre matériel. */
    val cadenceSpm: Double,
    /** Vitesse moyenne en m/s. 0 si aucune source de vitesse. */
    val speedMs: Double,
    /** Écart-type de la vitesse sur la fenêtre. */
    val speedStdMs: Double,
    /** Part de la fenêtre passée quasi à l'arrêt (< 0,5 m/s). */
    val stopRatio: Double,
    /** Écart-type de la norme de l'accélération, gravité retirée (m/s²). */
    val accelStd: Double,
    /**
     * Fréquence dominante de l'oscillation basse (0,5–2,5 Hz).
     * Un cycliste pédale, un trottinettiste non : c'est ce qui les sépare.
     */
    val oscillationHz: Double,
    /** Variation de pression atmosphérique, hPa/min. Négatif = on monte. */
    val pressureRateHpaPerMin: Double,
    val hasGps: Boolean,
)

data class Verdict(val mode: Mode, val confidence: Double, val reason: String)

/**
 * Classifieur de mode de déplacement, entièrement sur l'appareil.
 *
 * Volontairement écrit sous forme de règles lisibles plutôt que de réseau de
 * neurones : sur un produit qui demande de lui faire confiance, l'utilisateur
 * doit pouvoir lire *pourquoi* l'application a décidé qu'il était en train,
 * et le corriger. Chaque verdict porte donc sa justification en clair.
 *
 * On n'utilise pas l'API Activity Recognition de Google : elle imposerait
 * Google Play Services, donc un service tiers avec accès réseau dans le
 * processus. Tout l'argument de Cairn tomberait.
 *
 * Les règles sont évaluées dans l'ordre, de la plus spécifique à la plus
 * générale ; la première qui se prononce l'emporte. Ajouter un mode consiste à
 * écrire une fonction et à l'insérer au bon rang, sans toucher aux autres.
 */
object ModeClassifier {

    private val rules: List<(MotionWindow) -> Verdict?> = listOf(
        ::planeByPressureDrop,
        ::planeByCruiseSpeed,
        ::running,
        ::walking,
        ::noSpeedSource,
        ::stationary,
        ::train,
        ::car,
        ::bikeOrScooter,
    )

    private val fallback = Verdict(Mode.UNKNOWN, CONF_UNKNOWN, "profil de mouvement non reconnu")

    fun classify(w: MotionWindow): Verdict =
        rules.firstNotNullOfOrNull { rule -> rule(w) } ?: fallback

    // ------------------------------------------------------------------ avion

    /**
     * La signature barométrique d'une cabine pressurisée est sans équivalent :
     * ~250 hPa perdus en une quinzaine de minutes. Aucun autre mode de
     * transport ne produit ça, et c'est détectable sans le moindre GPS.
     */
    private fun planeByPressureDrop(w: MotionWindow): Verdict? {
        val climbing = w.pressureRateHpaPerMin < PLANE_PRESSURE_RATE
        val carriedStill = w.cadenceSpm < NO_STEPS && w.accelStd < PLANE_MAX_VIBRATION
        if (!climbing || !carriedStill) return null
        return Verdict(
            Mode.PLANE, CONF_PLANE_BAROMETRIC,
            "chute de pression de ${fmt(-w.pressureRateHpaPerMin)} hPa/min sans aucun pas : " +
                "montée en cabine pressurisée",
        )
    }

    private fun planeByCruiseSpeed(w: MotionWindow): Verdict? =
        if (w.hasGps && w.speedMs > PLANE_CRUISE_MS) {
            Verdict(Mode.PLANE, CONF_PLANE_CRUISE, "vitesse soutenue de ${kmh(w.speedMs)} km/h")
        } else null

    // ------------------------------------------------- modes à la force des jambes

    private fun running(w: MotionWindow): Verdict? {
        val fastCadence = w.cadenceSpm >= RUN_CADENCE
        val plausibleSpeed = !w.hasGps || w.speedMs in RUN_SPEED_RANGE
        if (!fastCadence || !plausibleSpeed) return null
        return Verdict(Mode.RUN, CONF_RUN, "cadence de ${w.cadenceSpm.toInt()} pas/min")
    }

    private fun walking(w: MotionWindow): Verdict? {
        if (w.cadenceSpm < WALK_CADENCE) return null
        // Marcher à plus de 11 km/h n'existe pas : on est probablement porté par
        // un véhicule qui secoue assez pour déclencher le podomètre.
        val suspiciouslyFast = w.hasGps && w.speedMs > WALK_MAX_SPEED
        return Verdict(
            Mode.FOOT,
            if (suspiciouslyFast) CONF_WALK_DOUBTFUL else CONF_WALK,
            "cadence de ${w.cadenceSpm.toInt()} pas/min",
        )
    }

    // --------------------------------------- à partir d'ici : aucun pas détecté

    private fun noSpeedSource(w: MotionWindow): Verdict? =
        if (!w.hasGps) {
            // Sans GPS on ne peut pas mesurer la distance d'un véhicule : on le
            // dit plutôt que d'inventer un chiffre.
            Verdict(Mode.UNKNOWN, CONF_UNKNOWN, "aucun pas détecté et pas de source de vitesse")
        } else null

    private fun stationary(w: MotionWindow): Verdict? =
        if (w.speedMs < STILL_SPEED) Verdict(Mode.UNKNOWN, CONF_STILL, "immobile") else null

    /**
     * Le rail ne fait ni nid-de-poule, ni feu rouge, ni virage serré : la
     * régularité est la signature du train.
     */
    private fun train(w: MotionWindow): Verdict? {
        val railSpeed = w.speedMs in TRAIN_SPEED_RANGE
        val steady = w.speedStdMs < TRAIN_MAX_SPEED_STD && w.stopRatio < TRAIN_MAX_STOPS
        if (!railSpeed || !steady || w.accelStd >= TRAIN_MAX_VIBRATION) return null
        return Verdict(
            Mode.TRAIN, CONF_TRAIN,
            "${kmh(w.speedMs)} km/h d'une régularité de rail : vitesse quasi constante, " +
                "aucun arrêt, très peu de vibrations",
        )
    }

    private fun car(w: MotionWindow): Verdict? {
        val tooFastForAnythingElse = w.speedMs > CAR_OBVIOUS_SPEED
        val urbanPattern = w.speedMs > CAR_MIN_SPEED &&
            (w.stopRatio > CAR_STOP_RATIO || w.speedStdMs > CAR_SPEED_STD)
        if (!tooFastForAnythingElse && !urbanPattern) return null

        val why = when {
            w.stopRatio > CAR_STOP_RATIO ->
                "${kmh(w.speedMs)} km/h avec ${(w.stopRatio * PERCENT).toInt()} % " +
                    "du temps à l'arrêt : circulation urbaine"
            w.speedMs > CAR_NO_PEDAL_SPEED -> "${kmh(w.speedMs)} km/h sans pédalage possible"
            else -> "${kmh(w.speedMs)} km/h avec des relances marquées"
        }
        return Verdict(Mode.CAR, CONF_CAR, why)
    }

    /**
     * Le pédalage imprime une oscillation de 0,7 à 2,2 Hz dans l'accéléromètre.
     * La trottinette n'en a aucune, mais ses petites roues vibrent bien plus
     * haut en fréquence. Aucune API système ne fait cette distinction.
     */
    private fun bikeOrScooter(w: MotionWindow): Verdict? {
        if (w.speedMs !in RIDE_SPEED_RANGE) return null

        val pedalling = w.oscillationHz in PEDAL_HZ_RANGE && w.accelStd > PEDAL_MIN_VIBRATION
        val smallWheels = w.accelStd > SCOOTER_MIN_VIBRATION && w.speedMs < SCOOTER_MAX_SPEED

        return when {
            pedalling -> Verdict(
                Mode.BIKE, CONF_BIKE_PEDALLING,
                "oscillation de ${fmt(w.oscillationHz)} Hz à ${kmh(w.speedMs)} km/h : " +
                    "un coup de pédale",
            )
            smallWheels -> Verdict(
                Mode.SCOOTER, CONF_SCOOTER,
                "${kmh(w.speedMs)} km/h sans pédalage, avec les vibrations " +
                    "hautes de petites roues",
            )
            else -> Verdict(
                Mode.BIKE, CONF_BIKE_DEFAULT,
                "${kmh(w.speedMs)} km/h sans pas détecté",
            )
        }
    }

    // ------------------------------------------------------------------ seuils

    private const val PERCENT = 100.0
    private const val MS_TO_KMH = 3.6

    // Confiance associée à chaque règle : une signature barométrique d'avion ne
    // laisse guère de place au doute, un vélo déduit par élimination beaucoup plus.
    private const val CONF_PLANE_BAROMETRIC = 0.92
    private const val CONF_PLANE_CRUISE = 0.95
    private const val CONF_RUN = 0.9
    private const val CONF_WALK = 0.88
    private const val CONF_WALK_DOUBTFUL = 0.6
    private const val CONF_TRAIN = 0.82
    private const val CONF_CAR = 0.78
    private const val CONF_BIKE_PEDALLING = 0.8
    private const val CONF_SCOOTER = 0.7
    private const val CONF_BIKE_DEFAULT = 0.55
    private const val CONF_STILL = 0.5
    private const val CONF_UNKNOWN = 0.3

    private const val NO_STEPS = 5.0
    private const val PLANE_PRESSURE_RATE = -4.5
    private const val PLANE_MAX_VIBRATION = 1.2
    private const val PLANE_CRUISE_MS = 55.0

    private const val RUN_CADENCE = 145.0
    private val RUN_SPEED_RANGE = 1.8..7.0
    private const val WALK_CADENCE = 45.0
    private const val WALK_MAX_SPEED = 3.2

    private const val STILL_SPEED = 0.8

    private val TRAIN_SPEED_RANGE = 11.0..90.0
    private const val TRAIN_MAX_SPEED_STD = 2.0
    private const val TRAIN_MAX_STOPS = 0.05
    private const val TRAIN_MAX_VIBRATION = 1.0

    private const val CAR_OBVIOUS_SPEED = 13.0
    private const val CAR_MIN_SPEED = 4.0
    private const val CAR_STOP_RATIO = 0.10
    private const val CAR_SPEED_STD = 2.5
    private const val CAR_NO_PEDAL_SPEED = 25.0

    private val RIDE_SPEED_RANGE = 1.8..14.0
    private val PEDAL_HZ_RANGE = 0.7..2.2
    private const val PEDAL_MIN_VIBRATION = 0.35
    private const val SCOOTER_MIN_VIBRATION = 1.1
    private const val SCOOTER_MAX_SPEED = 8.0

    private fun kmh(ms: Double) = (ms * MS_TO_KMH).toInt().toString()
    private fun fmt(v: Double) = "%.1f".format(v)
}

/**
 * Lisse les verdicts dans le temps.
 *
 * Sans ça, une fenêtre bizarre — un feu rouge, un passage en tunnel — ferait
 * basculer le mode toutes les 30 secondes et découperait un trajet en douze
 * sessions absurdes. On exige [SWITCH_STREAK] fenêtres concordantes avant de
 * changer d'avis.
 */
class ModeTracker {

    private var current: Verdict = Verdict(Mode.UNKNOWN, 0.0, "démarrage")
    private var candidate: Mode? = null
    private var streak = 0

    val mode: Mode get() = current.mode
    val reason: String get() = current.reason
    val confidence: Double get() = current.confidence

    /** Renvoie true si le mode vient de changer (donc : clore la session en cours). */
    fun offer(v: Verdict): Boolean {
        if (v.mode == current.mode) {
            candidate = null
            streak = 0
            // On garde la justification la plus confiante observée.
            if (v.confidence > current.confidence) current = v
            return false
        }
        if (v.mode == candidate) streak++ else { candidate = v.mode; streak = 1 }

        // Un verdict très confiant (avion) n'a pas besoin d'attendre confirmation.
        val decisive = v.confidence >= DECISIVE_CONFIDENCE
        if (streak < SWITCH_STREAK && !decisive) return false

        current = v
        candidate = null
        streak = 0
        return true
    }

    fun reset() {
        current = Verdict(Mode.UNKNOWN, 0.0, "démarrage")
        candidate = null
        streak = 0
    }

    private companion object {
        const val SWITCH_STREAK = 2
        const val DECISIVE_CONFIDENCE = 0.9
    }
}
