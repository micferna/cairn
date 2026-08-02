package app.cairn.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.cairn.data.CairnRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Le comptage qui marche même quand vous n'ouvrez pas l'application.
 *
 * ============================================================================
 * POURQUOI CETTE CLASSE EXISTE
 * ============================================================================
 *
 * Un traceur de sortie sportive, on le lance exprès. Un compteur de pas doit
 * être là quand on y pense, pas quand on l'a anticipé. Tant que Cairn ne
 * comptait qu'entre « Démarrer » et « Arrêter », il ratait l'essentiel d'une
 * journée ordinaire — celle où on ne pense pas à lui.
 *
 * `TYPE_STEP_COUNTER` rend la chose possible sans rien concéder. Ce n'est pas
 * un capteur qu'on écoute : c'est un **compteur matériel**. Un coprocesseur
 * basse consommation incrémente une valeur depuis le démarrage de l'appareil,
 * que Cairn tourne ou non, qu'il soit installé ou non. Il suffit de lire cette
 * valeur de temps en temps et de la comparer à la précédente pour retrouver
 * tous les pas manqués.
 *
 * Conséquences, qui vont toutes dans le même sens :
 *  - **aucun service en arrière-plan** n'est nécessaire ;
 *  - **aucune consommation** : on ne réveille rien, on lit un compteur ;
 *  - **aucune information de lieu**, comme toujours avec le podomètre.
 *
 * ============================================================================
 * LA LIMITE, ÉNONCÉE FRANCHEMENT
 * ============================================================================
 *
 * Le compteur repart de zéro à chaque redémarrage du téléphone. Les pas faits
 * entre un redémarrage et la lecture suivante sont donc perdus : on sait qu'il
 * y a eu reboot (le compteur a reculé), on ne sait pas ce qu'on a raté. Cairn
 * ne devine pas et n'invente rien — il consigne la perte dans le registre.
 *
 * De même, un relevé qui enjambe minuit ne peut pas être réparti entre les
 * deux journées : l'écart est attribué au jour courant, et c'est dit.
 *
 * ============================================================================
 * TOUS LES APPAREILS NE RESPECTENT PAS LA SPÉCIFICATION
 * ============================================================================
 *
 * Android impose que `TYPE_STEP_COUNTER` accumule en continu, indépendamment
 * de tout abonnement. Certains SoC d'entrée de gamme ne le font pas : leur
 * compteur démarre à zéro au premier `registerListener` et ne progresse que
 * tant qu'une application écoute. Observé sur un Moto g14 (Unisoc), dont le
 * compteur renvoie 0 après cinq semaines sans redémarrage.
 *
 * Le code ci-dessous est correct au regard de la spécification et fonctionne
 * sur un appareil conforme. Sur un appareil qui ne l'est pas, le comptage
 * passif ne rattrapera rien : [looksInert] permet de le détecter et de le dire
 * à l'utilisateur plutôt que de lui afficher éternellement zéro.
 */
class PassiveSteps(private val context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val counter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isAvailable: Boolean get() = counter != null

    /** Résultat d'un relevé, pour que l'appelant puisse en informer l'utilisateur. */
    data class Reading(
        val addedSteps: Int,
        val dayTotal: Int,
        val rebootDetected: Boolean,
        val dayRolledOver: Boolean,
        /** Le compteur matériel ne semble pas accumuler hors abonnement. */
        val sensorLooksInert: Boolean = false,
    )

    /**
     * Lit le compteur matériel une seule fois, met à jour le total du jour et
     * referme immédiatement l'écoute. Renvoie null si l'appareil n'a pas de
     * podomètre ou si le capteur ne répond pas.
     */
    suspend fun sync(
        repo: CairnRepository,
        strideM: Double,
        today: LocalDate = LocalDate.now(),
    ): Reading? {
        val raw = readOnce() ?: return null
        val day = today.toString()
        val store = repo.passive
        val anchor = store.anchor()

        if (anchor == null) {
            // Première lecture : on pose le point de référence sans rien
            // attribuer. On ignore d'où vient le compteur, il serait malhonnête
            // de créditer l'utilisateur de pas qu'on n'a pas vu faire.
            store.setAnchor(day, raw, 0)
            store.recordAnchorCreated(raw)
            return Reading(
                addedSteps = 0,
                dayTotal = 0,
                rebootDetected = false,
                dayRolledOver = false,
                sensorLooksInert = looksInert(raw, android.os.SystemClock.elapsedRealtime()),
            )
        }

        val (anchorDay, anchorRaw, anchorDaySteps) = anchor
        val rebooted = raw < anchorRaw
        val added = if (rebooted) raw.toInt() else (raw - anchorRaw).toInt()
        val rolledOver = anchorDay != day

        val dayTotal = if (rolledOver) added else anchorDaySteps.toInt() + added
        store.setAnchor(day, raw, dayTotal.toLong())

        if (added > 0) store.upsertDay(day, dayTotal, strideM)
        if (rebooted) store.recordReboot()
        return Reading(
            addedSteps = added,
            dayTotal = dayTotal,
            rebootDetected = rebooted,
            dayRolledOver = rolledOver,
            sensorLooksInert = looksInert(raw, android.os.SystemClock.elapsedRealtime()),
        )
    }

    /**
     * Vrai quand le compteur matériel semble ne pas accumuler hors abonnement.
     *
     * Un appareil allumé depuis des heures et dont le compteur reste à zéro ne
     * respecte pas la spécification : mieux vaut le signaler que laisser
     * l'utilisateur croire que l'application ne fonctionne pas.
     */
    fun looksInert(rawCounter: Long, uptimeMs: Long): Boolean =
        rawCounter == 0L && uptimeMs > INERT_UPTIME_MS

    /**
     * Une seule valeur, puis on se désabonne.
     *
     * `SENSOR_DELAY_FASTEST` ne coûte rien ici : le compteur est déjà tenu à
     * jour par le matériel, on demande simplement qu'il nous soit livré tout de
     * suite plutôt que dans une seconde.
     */
    private suspend fun readOnce(): Long? {
        val sensor = counter ?: return null
        val result = CompletableDeferred<Long>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    result.complete(event.values[0].toLong())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) { result.await() }
        } finally {
            sensorManager.unregisterListener(listener)
        }
    }

    private companion object {
        /** Au-delà, le capteur ne répondra pas : inutile de faire attendre l'écran. */
        const val READ_TIMEOUT_MS = 3_000L

        /** Six heures d'allumage sans un seul pas compté : le capteur ne suit pas. */
        const val INERT_UPTIME_MS = 6 * 60 * 60 * 1_000L
    }
}
