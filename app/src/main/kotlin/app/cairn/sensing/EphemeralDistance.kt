package app.cairn.sensing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Convertit un flux de positions en une distance, sans jamais retenir de position.
 *
 * ============================================================================
 * C'EST LA CLASSE QUI JUSTIFIE LE PRODUIT. LISEZ-LA EN ENTIER.
 * ============================================================================
 *
 * Un traceur GPS classique enregistre une liste de points. Cette liste est le
 * document le plus intime qu'un téléphone puisse produire : elle contient votre
 * domicile, votre travail, votre médecin, vos amis, vos habitudes horaires.
 * Une fois écrite sur le disque, elle peut fuiter, être saisie, être vendue
 * après un rachat, ou être exigée par une réquisition.
 *
 * Cairn ne l'écrit pas. Mieux : Cairn ne la construit jamais.
 *
 * Regardez l'état de cet objet. Il tient en trois nombres : la latitude, la
 * longitude et l'horodatage du point PRÉCÉDENT. Il n'y a pas de `List`, pas de
 * `Array`, pas de tampon circulaire, pas de fichier temporaire. À chaque
 * nouvelle position, on calcule la distance parcourue depuis le point
 * précédent, on l'ajoute à un compteur, puis on ÉCRASE le point précédent.
 *
 * Ce n'est pas une politique de rétention qu'on pourrait changer d'avis :
 * c'est une impossibilité structurelle. Écrire une trace exigerait de
 * réécrire cette classe, ce qui se verrait dans le diff.
 *
 * Ce qui survit à un trajet de 40 km : le nombre 40 000. Et rien d'autre.
 * ============================================================================
 */
class EphemeralDistance {

    // --- L'intégralité de l'état. Trois nombres. -----------------------------
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var prevTimeMs = 0L
    // ------------------------------------------------------------------------

    var totalMeters: Double = 0.0
        private set

    /** Vitesse instantanée du dernier segment, m/s. */
    var lastSpeedMs: Double = 0.0
        private set

    var acceptedFixes: Int = 0
        private set

    var rejectedFixes: Int = 0
        private set

    /**
     * Intègre une position et renvoie la distance ajoutée, en mètres.
     *
     * Les paramètres sont des `Double` nus et non un objet `Location` : rien ne
     * doit inciter à conserver l'objet système, qui transporte bien plus
     * d'informations (altitude, cap, satellites, fournisseur).
     */
    fun accept(latDeg: Double, lonDeg: Double, timeMs: Long, accuracyM: Float): Double {
        // Un point trop imprécis fait gonfler artificiellement les distances.
        if (accuracyM > MAX_ACCURACY_M) {
            rejectedFixes++
            return 0.0
        }
        if (prevLat.isNaN()) {
            remember(latDeg, lonDeg, timeMs)
            acceptedFixes++
            return 0.0
        }

        val dtS = (timeMs - prevTimeMs) / MS_PER_SECOND
        val d = haversine(prevLat, prevLon, latDeg, lonDeg)

        // On écrase le point précédent AVANT toute autre logique, pour qu'aucun
        // chemin de sortie anticipée ne puisse le laisser traîner en mémoire.
        remember(latDeg, lonDeg, timeMs)

        val speed = if (dtS > 0.0) d / dtS else Double.MAX_VALUE

        return when {
            dtS <= 0.0 || dtS > MAX_GAP_S -> {
                rejectedFixes++
                0.0
            }
            // En dessous du bruit GPS, on ignore : sinon un téléphone posé sur
            // une table "parcourt" plusieurs kilomètres par jour.
            d < MIN_STEP_M -> {
                acceptedFixes++
                0.0
            }
            speed > MAX_PLAUSIBLE_SPEED_MS -> {
                rejectedFixes++
                0.0
            }
            else -> {
                totalMeters += d
                lastSpeedMs = speed
                acceptedFixes++
                d
            }
        }
    }

    /** Remplace le point précédent. C'est le seul endroit qui écrit ces trois nombres. */
    private fun remember(latDeg: Double, lonDeg: Double, timeMs: Long) {
        prevLat = latDeg
        prevLon = lonDeg
        prevTimeMs = timeMs
    }

    /** Oublie le point précédent (changement de mode, pause). Le cumul est conservé. */
    fun breakSegment() {
        prevLat = Double.NaN
        prevLon = Double.NaN
        prevTimeMs = 0L
        lastSpeedMs = 0.0
    }

    /** Remet tout à zéro, y compris le cumul. */
    fun reset() {
        breakSegment()
        totalMeters = 0.0
        acceptedFixes = 0
        rejectedFixes = 0
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = EARTH_RADIUS_M
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0
        const val EARTH_RADIUS_M = 6_371_000.0
        const val MAX_ACCURACY_M = 35f
        const val MIN_STEP_M = 4.0
        const val MAX_GAP_S = 60.0
        /** ~1 000 km/h : au-delà, c'est un saut de fix, pas un déplacement. */
        const val MAX_PLAUSIBLE_SPEED_MS = 280.0
    }
}
