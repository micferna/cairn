package app.cairn.sensing

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Extrait la « signature vibratoire » du déplacement à partir de l'accéléromètre.
 *
 * Deux nombres sortent d'ici, et ils suffisent à distinguer la plupart des modes :
 *
 *  - **accelStd** : l'intensité des secousses. Un rail est lisse, une route
 *    l'est moins, un pavé sous des roues de trottinette beaucoup moins encore.
 *  - **oscillationHz** : la fréquence du balancement lent. Un cycliste qui
 *    pédale imprime une oscillation très reconnaissable entre 0,7 et 2,2 Hz.
 *    Un trottinettiste à la même vitesse n'en a aucune. C'est exactement ce
 *    qui les sépare, et aucune API système ne fait cette distinction.
 *
 * L'accéléromètre est un capteur inertiel : il mesure les forces subies par le
 * téléphone. Il ne peut structurellement rien dire de l'endroit où il se trouve.
 *
 * Le traitement est fait par filtres récursifs (deux moyennes glissantes
 * exponentielles) plutôt que par FFT : quelques additions par échantillon, pas
 * de tampon à conserver, pas d'allocation. C'est bon pour la batterie et ça
 * garantit qu'aucun historique de mouvement ne traîne en mémoire.
 */
class MotionSensor(private val sensorManager: SensorManager) : SensorEventListener {

    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isAvailable: Boolean get() = accel != null

    // Filtre passe-haut : retire la gravité et l'orientation lente du téléphone.
    private var gravityEma = Double.NaN

    // Filtre passe-bas sur le signal passe-haut : isole la bande 0,5–2,5 Hz.
    private var bandEma = 0.0
    private var prevBand = 0.0

    /**
     * Détecteur de pas logiciel, alimenté par le même flux d'accélération.
     *
     * On le fait tourner en permanence plutôt que d'ouvrir un second
     * abonnement : le capteur est déjà là, l'analyse coûte quelques additions
     * par échantillon, et on dispose ainsi d'un comptage de secours immédiat
     * si le podomètre matériel se révèle muet.
     */
    private val stepDetector = StepDetector()

    // Accumulateurs de la fenêtre courante.
    private var n = 0L
    private var sum = 0.0
    private var sumSq = 0.0
    private var zeroCrossings = 0
    private var windowStartMs = 0L

    fun start() {
        val s = accel ?: return
        windowStartMs = System.currentTimeMillis()
        // maxReportLatency laisse le hub capteur regrouper les échantillons :
        // le processeur principal peut dormir entre deux paquets.
        sensorManager.registerListener(this, s, SAMPLE_PERIOD_US, MAX_LATENCY_US)
    }

    fun stop() {
        if (accel != null) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z)

        if (gravityEma.isNaN()) {
            gravityEma = mag
            return
        }
        gravityEma += GRAVITY_ALPHA * (mag - gravityEma)
        val hp = mag - gravityEma

        synchronized(this) {
            stepDetector.accept(x, y, z, System.currentTimeMillis())
            n++
            sum += hp
            sumSq += hp * hp

            bandEma += BAND_ALPHA * (hp - bandEma)
            val roseThroughZero = prevBand <= 0.0 && bandEma > 0.0
            val fellThroughZero = prevBand >= 0.0 && bandEma < 0.0
            // On ne compte que les passages par zéro d'amplitude franche, pour
            // ne pas confondre le bruit de fond avec une oscillation réelle.
            val franc = kotlin.math.abs(bandEma - prevBand) > BAND_NOISE_FLOOR
            if ((roseThroughZero || fellThroughZero) && franc) zeroCrossings++
            prevBand = bandEma
        }
    }

    /** Pas détectés logiciellement depuis le dernier appel. */
    @Synchronized
    fun consumeSoftwareSteps(): Int = stepDetector.consumeDelta()

    /** Statistiques de la fenêtre écoulée, puis remise à zéro des accumulateurs. */
    @Synchronized
    fun consumeWindow(): Features {
        val now = System.currentTimeMillis()
        val durS = ((now - windowStartMs) / MS_PER_SECOND).coerceAtLeast(MIN_WINDOW_S)
        val count = n
        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 1) (sumSq / count - mean * mean).coerceAtLeast(0.0) else 0.0
        // Deux passages par zéro par période.
        val hz = zeroCrossings / (2.0 * durS)

        n = 0
        sum = 0.0
        sumSq = 0.0
        zeroCrossings = 0
        windowStartMs = now

        return Features(
            accelStd = sqrt(variance),
            oscillationHz = if (count > 10) hz else 0.0,
            samples = count,
        )
    }

    data class Features(val accelStd: Double, val oscillationHz: Double, val samples: Long)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun reset() {
        gravityEma = Double.NaN
        bandEma = 0.0
        prevBand = 0.0
        stepDetector.reset()
        consumeWindow()
    }

    private companion object {
        /** 25 Hz : de quoi voir une oscillation de pédalage sans vider la batterie. */
        const val SAMPLE_PERIOD_US = 40_000
        const val MAX_LATENCY_US = 5_000_000

        /** ~0,1 Hz de coupure : sépare la gravité du mouvement réel. */
        const val GRAVITY_ALPHA = 0.02

        /** ~2,5 Hz de coupure : ne garde que le balancement lent. */
        const val BAND_ALPHA = 0.35

        const val BAND_NOISE_FLOOR = 0.05
        const val MS_PER_SECOND = 1_000.0

        /** Garde-fou : évite une division par zéro si deux fenêtres se suivent. */
        const val MIN_WINDOW_S = 0.001
    }
}
