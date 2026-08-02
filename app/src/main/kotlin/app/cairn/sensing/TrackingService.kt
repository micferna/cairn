package app.cairn.sensing

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.cairn.CairnApp
import app.cairn.MainActivity
import app.cairn.R
import app.cairn.data.CairnRepository
import app.cairn.data.Settings
import app.cairn.domain.DistanceSource
import app.cairn.domain.LedgerKind
import app.cairn.domain.Mode
import app.cairn.domain.ModeClassifier
import app.cairn.domain.ModeTracker
import app.cairn.domain.MotionWindow
import app.cairn.domain.Session
import app.cairn.domain.ShapeArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Le service de mesure.
 *
 * Il tourne exclusivement au premier plan, avec une notification permanente que
 * l'utilisateur ne peut pas manquer. Cairn n'a délibérément aucune mesure
 * silencieuse en arrière-plan : si l'application compte vos pas, vous le voyez
 * dans la barre d'état. Pas de démarrage au boot, pas de réveil périodique
 * discret, pas de `ACCESS_BACKGROUND_LOCATION`.
 *
 * Toutes les 30 secondes, il résume l'état des capteurs en une [MotionWindow],
 * demande un verdict au classifieur, et accumule. Quand le mode change, le
 * segment en cours est clos et écrit en base sous forme d'agrégat.
 */
class TrackingService : Service(), LocationListener {

    private lateinit var repo: CairnRepository
    private lateinit var settings: Settings
    private lateinit var sensorManager: SensorManager

    private lateinit var steps: StepSensor
    private lateinit var altimeter: Altimeter
    private lateinit var motion: MotionSensor
    private val distance = EphemeralDistance()
    private var shapeRecorder: ShapeArt.Recorder? = null

    private val tracker = ModeTracker()
    private var scope: CoroutineScope? = null

    private var locationManager: LocationManager? = null
    private var gpsActive = false

    /** L'accéléromètre n'est allumé que quand il peut trancher (voir tuneMotionSensor). */
    private var motionRunning = false

    // --- segment en cours ----------------------------------------------------
    private var segStartMs = 0L
    private var segSteps = 0
    private var segDistanceM = 0.0
    private var segAscentM = 0.0
    private var segDescentM = 0.0
    private var segSpeedSum = 0.0
    private var segSpeedN = 0
    private var segMaxSpeed = 0.0

    // Échantillons de vitesse de la fenêtre courante, pour l'écart-type.
    private val windowSpeeds = ArrayList<Double>(64)
    private var windowStops = 0
    private var windowSpeedSamples = 0

    override fun onCreate() {
        super.onCreate()
        repo = CairnRepository.get(this)
        settings = Settings.get(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        steps = StepSensor(sensorManager)
        altimeter = Altimeter(sensorManager)
        motion = MotionSensor(sensorManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        if (_state.value.running) return

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            foregroundTypes(),
        )

        steps.resetSession()
        altimeter.reset()
        motion.reset()
        distance.reset()
        tracker.reset()
        resetSegment()

        steps.start()
        altimeter.start()
        motion.start()
        motionRunning = true

        repo.ledger.record(
            LedgerKind.SENSOR_OPEN,
            buildString {
                append("Podomètre ")
                append(if (steps.isAvailable) "actif" else "absent de cet appareil")
                append(" · Baromètre ")
                append(if (altimeter.isAvailable) "actif à 1 Hz" else "absent de cet appareil")
                append(" · Accéléromètre ")
                append(if (motion.isAvailable) "actif à 25 Hz" else "absent")
            },
        )

        maybeStartGps()

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            while (true) {
                delay(WINDOW_MS)
                runCatching { tick() }
            }
        }

        _state.value = LiveState(
            running = true,
            mode = Mode.UNKNOWN,
            reason = "démarrage",
            gpsActive = gpsActive,
        )
    }

    private fun maybeStartGps() {
        if (!settings.state.value.useGps) return
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        try {
            // On utilise LocationManager, pas le Fused Location Provider de
            // Google : celui-ci imposerait Google Play Services dans le
            // processus, donc une bibliothèque tierce disposant du réseau.
            lm.requestLocationUpdates(provider, GPS_INTERVAL_MS, GPS_MIN_DISTANCE_M, this)
            gpsActive = true
            if (settings.state.value.keepShapes) shapeRecorder = ShapeArt.Recorder()
            repo.ledger.record(
                LedgerKind.SENSOR_OPEN,
                "Localisation ouverte ($provider) — les coordonnées servent à calculer " +
                    "un écart entre deux points puis sont écrasées, jamais écrites sur le disque",
            )
        } catch (_: SecurityException) {
            gpsActive = false
        }
    }

    override fun onLocationChanged(location: Location) {
        val added = distance.accept(
            location.latitude, location.longitude, location.time, location.accuracy,
        )
        shapeRecorder?.add(location.latitude, location.longitude)

        val speed = if (location.hasSpeed()) location.speed.toDouble() else distance.lastSpeedMs
        synchronized(windowSpeeds) {
            windowSpeeds += speed
            windowSpeedSamples++
            if (speed < STOPPED_SPEED_MS) windowStops++
        }
        if (added > 0) segDistanceM += added
        // `location` sort de portée ici. Rien n'en est conservé.
    }

    @Deprecated("Requis par LocationListener sur les anciennes versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        gpsActive = false
    }

    // ------------------------------------------------------------------ boucle

    private fun tick() {
        val windowSteps = steps.consumeWindowDelta()
        val (ascent, descent) = altimeter.snapshotAndReset()
        val feats = motion.consumeWindow()

        val speeds: List<Double>
        val stops: Int
        val samples: Int
        synchronized(windowSpeeds) {
            speeds = ArrayList(windowSpeeds)
            stops = windowStops
            samples = windowSpeedSamples
            windowSpeeds.clear()
            windowStops = 0
            windowSpeedSamples = 0
        }

        val meanSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        val stdSpeed = if (speeds.size > 1) {
            sqrt(speeds.sumOf { (it - meanSpeed) * (it - meanSpeed) } / speeds.size)
        } else 0.0
        val maxSpeed = speeds.maxOrNull() ?: 0.0

        val window = MotionWindow(
            cadenceSpm = windowSteps * SECONDS_PER_MINUTE / WINDOW_S,
            speedMs = meanSpeed,
            speedStdMs = stdSpeed,
            stopRatio = if (samples > 0) stops.toDouble() / samples.toDouble() else 0.0,
            accelStd = feats.accelStd,
            oscillationHz = feats.oscillationHz,
            pressureRateHpaPerMin = altimeter.pressureRateHpaPerMin,
            hasGps = gpsActive && samples > 0,
        )

        val verdict = ModeClassifier.classify(window)
        val modeChanged = tracker.offer(verdict)

        if (modeChanged) {
            flushSegment(previousMode = _state.value.mode)
            distance.breakSegment()
        }

        tuneMotionSensor(window)

        // Accumulation dans le segment courant.
        segSteps += windowSteps
        segAscentM += ascent
        segDescentM += descent
        if (meanSpeed > 0) {
            segSpeedSum += meanSpeed
            segSpeedN++
            segMaxSpeed = max(segMaxSpeed, maxSpeed)
        }

        _state.value = LiveState(
            running = true,
            mode = tracker.mode,
            reason = tracker.reason,
            confidence = tracker.confidence,
            steps = segSteps,
            distanceM = currentSegmentDistance(),
            ascentM = segAscentM,
            descentM = segDescentM,
            elapsedS = (System.currentTimeMillis() - segStartMs) / MS_PER_SECOND,
            speedMs = meanSpeed,
            gpsActive = gpsActive,
            altitudeM = altimeter.lastAltitudeM,
        )
        notifyUpdate()
    }

    /**
     * Coupe l'accéléromètre quand il n'apporte rien.
     *
     * C'est le poste de consommation dominant du service : 25 Hz en continu,
     * là où le podomètre et le baromètre se contentent de 1 Hz traité par un
     * coprocesseur. Or il ne sert qu'à une chose — distinguer un vélo d'une
     * trottinette par l'oscillation du pédalage.
     *
     * Deux situations le rendent donc inutile :
     *  - **la cadence est franche** : quand le podomètre compte 110 pas/min, le
     *    mode est déjà établi, chercher une oscillation ne changera rien ;
     *  - **on est à l'arrêt** : il n'y a pas de vibration à analyser.
     *
     * Le classifieur reçoit alors `accelStd` et `oscillationHz` à zéro, ce qui
     * n'est pas un mensonge : ces règles ne sont de toute façon pas atteintes
     * dans ces cas-là, les règles de cadence et d'immobilité passent avant.
     */
    private fun tuneMotionSensor(w: MotionWindow) {
        val walking = w.cadenceSpm >= CADENCE_UNAMBIGUOUS
        val still = w.speedMs < STOPPED_SPEED_MS && w.cadenceSpm < 1
        val needed = motion.isAvailable && !walking && !still

        if (needed && !motionRunning) {
            motion.start()
            motionRunning = true
        } else if (!needed && motionRunning) {
            motion.stop()
            motionRunning = false
        }
    }

    /**
     * Distance du segment : mesurée par le podomètre quand c'est possible
     * (aucune information de lieu), par les écarts GPS sinon.
     */
    private fun currentSegmentDistance(): Double =
        if (tracker.mode.measurableWithoutGps) segSteps * settings.state.value.strideM.toDouble()
        else segDistanceM

    private fun flushSegment(previousMode: Mode) {
        val durationS = (System.currentTimeMillis() - segStartMs) / MS_PER_SECOND
        val dist = if (previousMode.measurableWithoutGps) {
            segSteps * settings.state.value.strideM.toDouble()
        } else segDistanceM

        val worthKeeping = durationS >= MIN_SEGMENT_S &&
            (dist >= MIN_SEGMENT_M || segSteps >= MIN_SEGMENT_STEPS || segAscentM >= MIN_SEGMENT_ASCENT_M)

        if (worthKeeping && previousMode != Mode.UNKNOWN) {
            val now = LocalTime.now()
            val quantize = settings.state.value.quantizeTime
            repo.insertSession(
                Session(
                    day = LocalDate.now().toString(),
                    hourBucket = if (quantize) now.hour else now.hour,
                    mode = previousMode,
                    durationS = durationS,
                    distanceM = dist,
                    steps = segSteps,
                    ascentM = segAscentM,
                    descentM = segDescentM,
                    avgSpeedMs = if (segSpeedN > 0) segSpeedSum / segSpeedN else 0.0,
                    maxSpeedMs = segMaxSpeed,
                    confidence = tracker.confidence,
                    source = if (previousMode.measurableWithoutGps) DistanceSource.PEDOMETER
                    else DistanceSource.GPS_DELTA,
                    reason = tracker.reason,
                    shape = if (settings.state.value.keepShapes) shapeRecorder?.build() else null,
                )
            )
        } else if (durationS > 0) {
            repo.ledger.record(
                LedgerKind.DISCARD,
                "Segment ${previousMode.label} de ${durationS}s jeté sans être enregistré " +
                    "(sous le seuil de pertinence)",
            )
        }
        if (settings.state.value.keepShapes && gpsActive) shapeRecorder = ShapeArt.Recorder()
        resetSegment()
    }

    private fun resetSegment() {
        segStartMs = System.currentTimeMillis()
        segSteps = 0
        segDistanceM = 0.0
        segAscentM = 0.0
        segDescentM = 0.0
        segSpeedSum = 0.0
        segSpeedN = 0
        segMaxSpeed = 0.0
    }

    private fun stopTracking() {
        flushSegment(previousMode = tracker.mode)

        steps.stop()
        altimeter.stop()
        if (motionRunning) motion.stop()
        motionRunning = false
        locationManager?.removeUpdates(this)
        locationManager = null

        val discarded = shapeRecorder?.size ?: 0
        shapeRecorder = null

        repo.ledger.record(
            LedgerKind.SENSOR_CLOSE,
            "Tous les capteurs fermés" +
                if (gpsActive) {
                    " · ${distance.acceptedFixes} positions traitées puis écrasées, " +
                        "${distance.rejectedFixes} rejetées comme trop imprécises, " +
                        "$discarded points de forme détruits"
                } else "",
        )
        gpsActive = false

        scope?.cancel()
        scope = null
        _state.value = LiveState(running = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (_state.value.running) stopTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------- notification

    private fun foregroundTypes(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            if (hasLocationPermission() && settings.state.value.useGps) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            t
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            if (hasLocationPermission() && settings.state.value.useGps) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
        else -> 0
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val s = _state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (s.running) "${s.mode.label} en cours" else "Cairn"
        val text = if (s.running) {
            "%.2f km · %d pas · D+ %d m"
                .format(s.distanceM / METERS_PER_KM, s.steps, s.ascentM.toInt())
        } else {
            "Prêt"
        }

        return NotificationCompat.Builder(this, CairnApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cairn)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (gpsActive) "position en mémoire vive uniquement" else "sans localisation")
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun notifyUpdate() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    /** État affichable en direct. Ne quitte jamais le processus. */
    data class LiveState(
        val running: Boolean = false,
        val mode: Mode = Mode.UNKNOWN,
        val reason: String = "",
        val confidence: Double = 0.0,
        val steps: Int = 0,
        val distanceM: Double = 0.0,
        val ascentM: Double = 0.0,
        val descentM: Double = 0.0,
        val elapsedS: Long = 0,
        val speedMs: Double = 0.0,
        val gpsActive: Boolean = false,
        val altitudeM: Double = Double.NaN,
    )

    companion object {
        const val ACTION_STOP = "app.cairn.STOP"
        private const val NOTIF_ID = 1
        private const val WINDOW_MS = 30_000L
        private const val WINDOW_S = WINDOW_MS / 1_000.0
        private const val SECONDS_PER_MINUTE = 60.0
        private const val MS_PER_SECOND = 1_000L
        private const val METERS_PER_KM = 1_000.0

        /** Une position toutes les 3 s, ou tous les 5 m : suffisant et sobre. */
        private const val GPS_INTERVAL_MS = 3_000L
        private const val GPS_MIN_DISTANCE_M = 5f

        /** En dessous, une position est du bruit plutôt qu'un déplacement. */
        private const val STOPPED_SPEED_MS = 0.5

        /**
         * Au-delà de cette cadence, le mode est établi par le podomètre seul :
         * l'accéléromètre n'a plus rien à trancher et peut être éteint.
         */
        private const val CADENCE_UNAMBIGUOUS = 45.0
        private const val MIN_SEGMENT_S = 60L
        private const val MIN_SEGMENT_M = 100.0
        private const val MIN_SEGMENT_STEPS = 120

        /** Un segment sans distance ni pas reste digne d'intérêt s'il a grimpé. */
        private const val MIN_SEGMENT_ASCENT_M = 5.0

        private val _state = MutableStateFlow(LiveState())
        val state: StateFlow<LiveState> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
