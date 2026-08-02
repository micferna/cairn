package app.cairn.sensing

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Podomètre matériel.
 *
 * `TYPE_STEP_COUNTER` est traité par un coprocesseur basse consommation dédié :
 * il compte les pas même quand le processeur principal dort, pour un coût
 * énergétique quasi nul. C'est ce qui permet à Cairn de mesurer une journée
 * entière de marche sans allumer le GPS une seule fois — et donc de fonctionner
 * complètement sans jamais demander la permission de localisation.
 *
 * Le capteur renvoie un compteur cumulé depuis le démarrage de l'appareil, ce
 * qui impose deux précautions : mémoriser un point d'ancrage, et détecter le
 * redémarrage (le compteur repart alors de zéro).
 */
class StepSensor(private val sensorManager: SensorManager) : SensorEventListener {

    private val counter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isAvailable: Boolean get() = counter != null

    private var lastRaw: Long = -1L
    private var pendingDelta: Int = 0

    fun start() {
        val s = counter ?: return
        sensorManager.registerListener(this, s, SAMPLE_PERIOD_US)
    }

    fun stop() {
        if (counter != null) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values[0].toLong()

        if (lastRaw < 0) {
            lastRaw = raw
            return
        }
        // Compteur en recul : l'appareil a redémarré, il repart de zéro.
        val delta = if (raw < lastRaw) raw.toInt() else (raw - lastRaw).toInt()
        lastRaw = raw
        if (delta <= 0) return

        pendingDelta += delta
    }

    /** Renvoie les pas depuis le dernier appel et remet le compteur de fenêtre à zéro. */
    @Synchronized
    fun consumeWindowDelta(): Int {
        val d = pendingDelta
        pendingDelta = 0
        return d
    }

    fun resetSession() {
        pendingDelta = 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val SAMPLE_PERIOD_US = 1_000_000
    }
}
