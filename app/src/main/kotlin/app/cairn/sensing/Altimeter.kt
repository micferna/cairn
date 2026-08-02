package app.cairn.sensing

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Dénivelé positif et négatif, mesurés au baromètre.
 *
 * Le baromètre est le capteur préféré de Cairn, pour deux raisons qui vont
 * dans le même sens :
 *
 *  - **Il est plus précis que le GPS en altitude.** Le GPS donne l'altitude à
 *    ±10 m dans de bonnes conditions ; un baromètre MEMS détecte 20 cm. Sur un
 *    parcours de montagne, le D+ barométrique est simplement meilleur.
 *  - **Il ne sait rien de la géographie.** Une pression en hectopascals ne
 *    permet de vous localiser nulle part. C'est une donnée sur l'air autour du
 *    téléphone, pas sur sa position sur Terre.
 *
 * Autrement dit, la fonctionnalité "montagne" de Cairn est à la fois la plus
 * précise du marché et celle qui divulgue le moins. C'est rare que les deux
 * aillent ensemble.
 *
 * Le piège du D+ barométrique, c'est le bruit : sans filtrage, un téléphone
 * posé sur une table accumule des centaines de mètres de dénivelé par jour à
 * cause des micro-variations météo. D'où les deux étages ci-dessous : filtre
 * médian, puis hystérésis.
 */
class Altimeter(private val sensorManager: SensorManager) : SensorEventListener {

    private val pressureSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = pressureSensor != null

    /** Fenêtre glissante pour le filtre médian. */
    private val window = DoubleArray(MEDIAN_WINDOW)
    private var windowFill = 0
    private var windowIndex = 0

    /** Altitude de référence courante : ne bouge qu'au-delà de l'hystérésis. */
    private var refAltitudeM = Double.NaN

    var ascentM: Double = 0.0
        private set

    var descentM: Double = 0.0
        private set

    var lastAltitudeM: Double = Double.NaN
        private set

    /** Pente de la pression, hPa/min. C'est le détecteur d'avion. */
    var pressureRateHpaPerMin: Double = 0.0
        private set

    private var ratePressureRef = Double.NaN
    private var rateTimeRefMs = 0L

    fun start() {
        val s = pressureSensor ?: return
        sensorManager.registerListener(this, s, SAMPLE_PERIOD_US)
    }

    fun stop() {
        if (pressureSensor != null) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val hpa = event.values[0].toDouble()
        if (hpa <= 0 || hpa.isNaN()) return

        updatePressureRate(hpa)
        pushAndMedian(hpa)?.let(::integrateAltitude)
    }

    /**
     * Étage 1 : médiane glissante sur 9 échantillons, pour tuer les valeurs
     * aberrantes. Renvoie null tant que la fenêtre n'est pas pleine.
     */
    private fun pushAndMedian(hpa: Double): Double? {
        window[windowIndex] = hpa
        windowIndex = (windowIndex + 1) % MEDIAN_WINDOW
        if (windowFill < MEDIAN_WINDOW) {
            windowFill++
            return null
        }
        return window.copyOf().also { it.sort() }[MEDIAN_WINDOW / 2]
    }

    /**
     * Étage 2 : hystérésis. Tant qu'on n'a pas bougé de plus de 2 m, on
     * considère qu'on n'a pas bougé du tout. C'est ce qui évite d'attribuer un
     * D+ de 400 m à une nuit de sommeil pendant qu'une dépression passe.
     */
    private fun integrateAltitude(medianHpa: Double) {
        val altitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            medianHpa.toFloat(),
        ).toDouble()
        lastAltitudeM = altitude

        if (refAltitudeM.isNaN()) {
            refAltitudeM = altitude
            return
        }
        val delta = altitude - refAltitudeM
        if (abs(delta) >= HYSTERESIS_M) {
            if (delta > 0) ascentM += delta else descentM += -delta
            refAltitudeM = altitude
        }
    }

    /** Calcule la pente de pression sur une base glissante d'une minute. */
    private fun updatePressureRate(hpa: Double) {
        val now = System.currentTimeMillis()
        if (ratePressureRef.isNaN()) {
            ratePressureRef = hpa
            rateTimeRefMs = now
            return
        }
        val dtMin = (now - rateTimeRefMs) / MS_PER_MINUTE
        if (dtMin >= MIN_RATE_INTERVAL_MIN) {
            pressureRateHpaPerMin = (hpa - ratePressureRef) / dtMin
            ratePressureRef = hpa
            rateTimeRefMs = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshotAndReset(): Pair<Double, Double> {
        val a = ascentM
        val d = descentM
        ascentM = 0.0
        descentM = 0.0
        return a to d
    }

    fun reset() {
        ascentM = 0.0
        descentM = 0.0
        refAltitudeM = Double.NaN
        windowFill = 0
        windowIndex = 0
        pressureRateHpaPerMin = 0.0
        ratePressureRef = Double.NaN
    }

    private companion object {
        const val MEDIAN_WINDOW = 9
        /** 1 Hz suffit largement pour du dénivelé, et coûte très peu de batterie. */
        const val SAMPLE_PERIOD_US = 1_000_000
        const val HYSTERESIS_M = 2.0
        const val MS_PER_MINUTE = 60_000.0

        /** On ne recalcule la pente de pression que toutes les 30 secondes. */
        const val MIN_RATE_INTERVAL_MIN = 0.5
    }
}
