package app.cairn.data

import android.content.ContentValues
import android.content.Context
import androidx.core.database.sqlite.transaction
import app.cairn.domain.DayStat
import app.cairn.domain.DistanceSource
import app.cairn.domain.LedgerKind
import app.cairn.domain.Mode
import app.cairn.domain.Session
import app.cairn.domain.Shape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Cumuls d'un mode de déplacement sur une période. */
data class ModeTotals(
    val mode: Mode,
    val distanceM: Double,
    val durationS: Long,
    val sessions: Int,
) {
    val co2Grams: Double get() = mode.gramsCo2PerKm * (distanceM / 1000.0)
}

/**
 * Point d'accès unique aux données. Tout est local, tout est synchrone sur
 * SQLite : il n'y a ni couche réseau, ni file d'attente d'envoi, ni identifiant
 * d'appareil, parce qu'il n'y a rien à envoyer nulle part.
 */
class CairnRepository private constructor(context: Context) {

    private val db = CairnDb(context.applicationContext)
    val ledger = Ledger(db)

    private val _revision = MutableStateFlow(0L)

    /** Change à chaque mutation, pour rafraîchir les écrans. */
    val revision: StateFlow<Long> = _revision

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    // ---------------------------------------------------------------- écriture

    fun insertSession(session: Session): Long {
        val shapeJson = session.shape?.let { encodeShape(it) }
        val values = ContentValues().apply {
            put("day", session.day)
            put("hour_bucket", session.hourBucket)
            put("mode", session.mode.name)
            put("duration_s", session.durationS)
            put("distance_m", session.distanceM)
            put("steps", session.steps)
            put("ascent_m", session.ascentM)
            put("descent_m", session.descentM)
            put("avg_speed_ms", session.avgSpeedMs)
            put("max_speed_ms", session.maxSpeedMs)
            put("confidence", session.confidence)
            put("source", session.source.name)
            put("reason", session.reason)
            put("shape_json", shapeJson)
        }
        val id = db.writableDatabase.insert("session", null, values)

        val approxBytes = ROW_OVERHEAD_BYTES + session.reason.length + (shapeJson?.length ?: 0)
        ledger.record(
            LedgerKind.WRITE,
            "Session ${session.mode.label} — " +
                "${"%.2f".format(session.distanceM / METERS_PER_KM)} km, " +
                "${session.durationS / SECONDS_PER_MINUTE} min, " +
                "D+${session.ascentM.toInt()} m " +
                "(jour ${session.day}, tranche ${session.hourBucket}h)",
            approxBytes,
        )
        recomputeDay(session.day)
        bump()
        return id
    }

    /** Recalcule l'agrégat d'un jour à partir de ses sessions. */
    fun recomputeDay(day: String) {
        val w = db.writableDatabase
        w.rawQuery(
            """
            SELECT COALESCE(SUM(steps),0)      AS steps,
                   COALESCE(SUM(distance_m),0) AS distance_m,
                   COALESCE(SUM(ascent_m),0)   AS ascent_m,
                   COALESCE(SUM(descent_m),0)  AS descent_m,
                   COALESCE(SUM(duration_s),0) AS active_s
            FROM session WHERE day = ?
            """.trimIndent(),
            arrayOf(day)
        ).use { c ->
            if (c.moveToFirst()) {
                w.insertWithOnConflict(
                    "day_stat", null,
                    ContentValues().apply {
                        put("day", day)
                        put("steps", c.int("steps"))
                        put("distance_m", c.dbl("distance_m"))
                        put("ascent_m", c.dbl("ascent_m"))
                        put("descent_m", c.dbl("descent_m"))
                        put("active_s", c.long("active_s"))
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    // ---------------------------------------------------------------- lecture

    fun dayStat(day: String): DayStat {
        db.readableDatabase.rawQuery(
            "SELECT day, steps, distance_m, ascent_m, descent_m, active_s FROM day_stat WHERE day = ?",
            arrayOf(day)
        ).use { c ->
            if (c.moveToFirst()) return c.toDayStat()
        }
        return DayStat(day, 0, 0.0, 0.0, 0.0, 0)
    }

    private fun android.database.Cursor.toDayStat() = DayStat(
        day = str("day"),
        steps = int("steps"),
        distanceM = dbl("distance_m"),
        ascentM = dbl("ascent_m"),
        descentM = dbl("descent_m"),
        activeS = long("active_s"),
    )

    /** Les [n] derniers jours, du plus ancien au plus récent, trous comblés à zéro. */
    fun lastDays(n: Int, today: LocalDate = LocalDate.now()): List<DayStat> {
        val from = today.minusDays((n - 1).toLong()).toString()
        val byDay = HashMap<String, DayStat>()
        db.readableDatabase.rawQuery(
            "SELECT day, steps, distance_m, ascent_m, descent_m, active_s FROM day_stat WHERE day >= ? ORDER BY day",
            arrayOf(from)
        ).use { c ->
            while (c.moveToNext()) {
                val stat = c.toDayStat()
                byDay[stat.day] = stat
            }
        }
        return (0 until n).map { i ->
            val d = today.minusDays((n - 1 - i).toLong()).toString()
            byDay[d] ?: DayStat(d, 0, 0.0, 0.0, 0.0, 0)
        }
    }

    /** Cumuls par mode. [sinceDay] au format ISO, ou null pour tout l'historique. */
    fun totalsByMode(sinceDay: String? = null): List<ModeTotals> {
        val sql = buildString {
            append("SELECT mode, ")
            append("COALESCE(SUM(distance_m),0) AS distance_m, ")
            append("COALESCE(SUM(duration_s),0) AS duration_s, ")
            append("COUNT(*) AS sessions FROM session")
            if (sinceDay != null) append(" WHERE day >= ?")
            append(" GROUP BY mode")
        }
        val args = if (sinceDay != null) arrayOf(sinceDay) else null
        val out = mutableListOf<ModeTotals>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += ModeTotals(
                    mode = parseMode(c.str("mode")),
                    distanceM = c.dbl("distance_m"),
                    durationS = c.long("duration_s"),
                    sessions = c.int("sessions"),
                )
            }
        }
        return out.sortedByDescending { it.distanceM }
    }

    fun totalDistanceM(): Double {
        db.readableDatabase.rawQuery("SELECT COALESCE(SUM(distance_m),0) FROM session", null).use { c ->
            return if (c.moveToFirst()) c.getDouble(0) else 0.0
        }
    }

    fun totalAscentM(): Double {
        db.readableDatabase.rawQuery("SELECT COALESCE(SUM(ascent_m),0) FROM session", null).use { c ->
            return if (c.moveToFirst()) c.getDouble(0) else 0.0
        }
    }

    fun totalSteps(): Long {
        db.readableDatabase.rawQuery("SELECT COALESCE(SUM(steps),0) FROM session", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun sessionCount(): Long =
        android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, "session")

    fun recentSessions(limit: Int = 50): List<Session> {
        val out = mutableListOf<Session>()
        db.readableDatabase.rawQuery(
            """
            SELECT id, day, hour_bucket, mode, duration_s, distance_m, steps, ascent_m, descent_m,
                   avg_speed_ms, max_speed_ms, confidence, source, reason, shape_json
            FROM session ORDER BY id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += c.toSession()
        }
        return out
    }

    fun sessionsWithShapes(limit: Int = 60): List<Session> {
        val out = mutableListOf<Session>()
        db.readableDatabase.rawQuery(
            """
            SELECT id, day, hour_bucket, mode, duration_s, distance_m, steps, ascent_m, descent_m,
                   avg_speed_ms, max_speed_ms, confidence, source, reason, shape_json
            FROM session WHERE shape_json IS NOT NULL ORDER BY id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += c.toSession()
        }
        return out
    }

    private fun android.database.Cursor.toSession() = Session(
        id = long("id"),
        day = str("day"),
        hourBucket = int("hour_bucket"),
        mode = parseMode(str("mode")),
        durationS = long("duration_s"),
        distanceM = dbl("distance_m"),
        steps = int("steps"),
        ascentM = dbl("ascent_m"),
        descentM = dbl("descent_m"),
        avgSpeedMs = dbl("avg_speed_ms"),
        maxSpeedMs = dbl("max_speed_ms"),
        confidence = dbl("confidence"),
        source = runCatching { DistanceSource.valueOf(str("source")) }
            .getOrDefault(DistanceSource.PEDOMETER),
        reason = strOrNull("reason").orEmpty(),
        shape = strOrNull("shape_json")?.let(::decodeShape),
    )

    /** Un mode inconnu en base (ancienne version, fichier bricolé) ne doit pas planter. */
    private fun parseMode(raw: String): Mode =
        runCatching { Mode.valueOf(raw) }.getOrDefault(Mode.UNKNOWN)

    // ------------------------------------------------------- export / effacement

    /**
     * Export intégral, lisible, au format JSON. Ce fichier EST la totalité de
     * ce que Cairn sait de vous : s'il vous paraît inoffensif, c'est que la
     * promesse est tenue.
     */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("application", "Cairn")
        root.put("format_version", 1)
        root.put("exported_at", LocalDateTime.now().toString())
        root.put(
            "note",
            "Ce fichier contient l'intégralité des données détenues par Cairn. " +
                "Aucune coordonnée géographique n'y figure car aucune n'a jamais été enregistrée."
        )

        val sessions = JSONArray()
        db.readableDatabase.rawQuery(
            """
            SELECT id, day, hour_bucket, mode, duration_s, distance_m, steps, ascent_m, descent_m,
                   avg_speed_ms, max_speed_ms, confidence, source, reason, shape_json
            FROM session ORDER BY id
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.toSession()
                sessions.put(
                    JSONObject().apply {
                        put("day", s.day)
                        put("hour_bucket", s.hourBucket)
                        put("mode", s.mode.name)
                        put("duration_s", s.durationS)
                        put("distance_m", s.distanceM)
                        put("steps", s.steps)
                        put("ascent_m", s.ascentM)
                        put("descent_m", s.descentM)
                        put("avg_speed_ms", s.avgSpeedMs)
                        put("max_speed_ms", s.maxSpeedMs)
                        put("confidence", s.confidence)
                        put("source", s.source.name)
                        put("reason", s.reason)
                        put("shape", s.shape?.let { sh ->
                            JSONArray().apply {
                                sh.points.forEach { p ->
                                    put(JSONArray().apply { put(p.first.toDouble()); put(p.second.toDouble()) })
                                }
                            }
                        })
                    }
                )
            }
        }
        root.put("sessions", sessions)

        val days = JSONArray()
        db.readableDatabase.rawQuery(
            "SELECT day, steps, distance_m, ascent_m, descent_m, active_s FROM day_stat ORDER BY day", null
        ).use { c ->
            while (c.moveToNext()) {
                val d = c.toDayStat()
                days.put(
                    JSONObject().apply {
                        put("day", d.day)
                        put("steps", d.steps)
                        put("distance_m", d.distanceM)
                        put("ascent_m", d.ascentM)
                        put("descent_m", d.descentM)
                        put("active_s", d.activeS)
                    }
                )
            }
        }
        root.put("days", days)

        val out = root.toString(2)
        ledger.record(LedgerKind.EXPORT, "Export JSON demandé par l'utilisateur", out.toByteArray().size)
        return out
    }

    /** Efface tout, sans exception et sans corbeille. */
    fun wipeAll() {
        val n = sessionCount()
        val w = db.writableDatabase
        w.transaction {
            execSQL("DELETE FROM session")
            execSQL("DELETE FROM day_stat")
            execSQL("DELETE FROM sqlite_sequence WHERE name IN ('session')")
        }
        // VACUUM ne peut pas tourner dans une transaction : c'est lui qui
        // réécrit le fichier et fait disparaître les pages libérées du disque.
        w.execSQL("VACUUM")
        ledger.record(LedgerKind.DELETE, "Effacement total : $n sessions détruites, base compactée")
        bump()
    }

    /** Efface aussi le registre lui-même. Retour à l'état d'installation. */
    fun wipeEverythingIncludingLedger() {
        wipeAll()
        db.writableDatabase.execSQL("DELETE FROM ledger")
        db.writableDatabase.execSQL("VACUUM")
        ledger.record(LedgerKind.DELETE, "Registre de transparence remis à zéro")
        bump()
    }

    // ------------------------------------------------------------------ codec

    /** Trois décimales suffisent pour une forme sans échelle, et allègent le JSON. */
    private fun roundToShapePrecision(v: Float): Double =
        Math.round(v * SHAPE_PRECISION) / SHAPE_PRECISION.toDouble()

    private fun encodeShape(shape: Shape): String {
        val a = JSONArray()
        shape.points.forEach { (x, y) ->
            a.put(JSONArray().apply {
                put(roundToShapePrecision(x))
                put(roundToShapePrecision(y))
            })
        }
        return a.toString()
    }

    private fun decodeShape(json: String): Shape? = runCatching {
        val a = JSONArray(json)
        val pts = ArrayList<Pair<Float, Float>>(a.length())
        for (i in 0 until a.length()) {
            val p = a.getJSONArray(i)
            pts += p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
        }
        Shape(pts)
    }.getOrNull()

    companion object {
        private const val ROW_OVERHEAD_BYTES = 96
        private const val METERS_PER_KM = 1_000.0
        private const val SECONDS_PER_MINUTE = 60
        private const val SHAPE_PRECISION = 1_000f

        @Volatile
        private var instance: CairnRepository? = null

        fun get(context: Context): CairnRepository =
            instance ?: synchronized(this) {
                instance ?: CairnRepository(context).also { instance = it }
            }
    }
}
