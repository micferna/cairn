package app.cairn.data

import android.content.ContentValues
import app.cairn.domain.DayStat
import app.cairn.domain.DistanceSource
import app.cairn.domain.Mode
import app.cairn.domain.Session
import app.cairn.domain.Shape
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Le format d'archive : la totalité de ce que Cairn sait de vous, en clair.
 *
 * Deux raisons de l'isoler du dépôt plutôt que de l'y noyer.
 *
 * La première est technique : ce format est un contrat avec l'extérieur, il
 * doit rester lisible d'un seul tenant. Quelqu'un qui veut écrire un
 * convertisseur depuis Strava, ou vérifier ce qui sort de l'application, doit
 * pouvoir lire un seul fichier.
 *
 * La seconde est produit : ce fichier est l'argument. S'il vous paraît
 * inoffensif quand vous l'ouvrez dans un éditeur de texte, c'est que la
 * promesse est tenue. Cherchez-y une coordonnée géographique — il n'y en a pas,
 * parce qu'aucune n'a jamais été enregistrée.
 *
 * L'import est **additif et idempotent** : chaque session porte assez
 * d'information pour être reconnue comme déjà présente, ce qui permet de
 * réimporter le même fichier ou de fusionner deux exports qui se chevauchent.
 */
class Archive {

    /** Ce qu'un import a réellement fait, pour le dire sans arrondir. */
    data class ImportResult(val imported: Int, val skipped: Int, val error: String? = null)

    /** Sessions prêtes à insérer, ou message d'erreur lisible. */
    data class Parsed(val sessions: List<ContentValues>, val error: String? = null)

    fun encode(sessions: List<Session>, days: List<DayStat>): String {
        val root = JSONObject()
        root.put("application", "Cairn")
        root.put("format_version", FORMAT_VERSION)
        root.put("exported_at", LocalDateTime.now().toString())
        root.put(
            "note",
            "Ce fichier contient l'intégralité des données détenues par Cairn. " +
                "Aucune coordonnée géographique n'y figure car aucune n'a jamais été enregistrée.",
        )
        root.put("sessions", JSONArray().apply { sessions.forEach { put(encodeSession(it)) } })
        root.put("days", JSONArray().apply { days.forEach { put(encodeDay(it)) } })
        return root.toString(INDENT)
    }

    private fun encodeSession(s: Session) = JSONObject().apply {
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
        put("corrected", s.corrected)
        put("passive", s.passive)
        put("shape", s.shape?.let(::encodeShapeArray))
    }

    private fun encodeDay(d: DayStat) = JSONObject().apply {
        put("day", d.day)
        put("steps", d.steps)
        put("distance_m", d.distanceM)
        put("ascent_m", d.ascentM)
        put("descent_m", d.descentM)
        put("active_s", d.activeS)
    }

    fun decode(json: String): Parsed {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Parsed(emptyList(), "Fichier illisible : ce n'est pas du JSON.")
        val array = root.optJSONArray("sessions")
            ?: return Parsed(emptyList(), "Aucune session dans ce fichier.")

        val out = ArrayList<ContentValues>(array.length())
        for (i in 0 until array.length()) {
            decodeSession(array.optJSONObject(i))?.let { out += it }
        }
        if (out.isEmpty()) return Parsed(emptyList(), "Aucune session exploitable dans ce fichier.")
        return Parsed(out)
    }

    /** null si l'entrée est inexploitable : on saute, on ne fait pas échouer tout l'import. */
    private fun decodeSession(o: JSONObject?): ContentValues? {
        val day = o?.optString("day")?.takeIf { it.isNotBlank() } ?: return null
        val mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.UNKNOWN)
        return ContentValues().apply {
            put("day", day)
            put("hour_bucket", o.optInt("hour_bucket"))
            put("mode", mode.name)
            put("duration_s", o.optLong("duration_s"))
            put("distance_m", o.optDouble("distance_m", 0.0))
            put("steps", o.optInt("steps"))
            put("ascent_m", o.optDouble("ascent_m", 0.0))
            put("descent_m", o.optDouble("descent_m", 0.0))
            put("avg_speed_ms", o.optDouble("avg_speed_ms", 0.0))
            put("max_speed_ms", o.optDouble("max_speed_ms", 0.0))
            put("confidence", o.optDouble("confidence", 0.0))
            put("source", o.optString("source", DistanceSource.PEDOMETER.name))
            put("reason", o.optString("reason", ""))
            put("shape_json", o.optJSONArray("shape")?.toString())
            put("corrected", if (o.optBoolean("corrected")) 1 else 0)
            put("passive", if (o.optBoolean("passive")) 1 else 0)
        }
    }

    fun encodeShape(shape: Shape): String = encodeShapeArray(shape).toString()

    private fun encodeShapeArray(shape: Shape) = JSONArray().apply {
        shape.points.forEach { (x, y) ->
            put(JSONArray().apply { put(round(x)); put(round(y)) })
        }
    }

    fun decodeShape(json: String): Shape? = runCatching {
        val a = JSONArray(json)
        val pts = ArrayList<Pair<Float, Float>>(a.length())
        for (i in 0 until a.length()) {
            val p = a.getJSONArray(i)
            pts += p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
        }
        Shape(pts)
    }.getOrNull()

    /** Trois décimales suffisent pour une forme sans échelle, et allègent le fichier. */
    private fun round(v: Float): Double = Math.round(v * PRECISION) / PRECISION.toDouble()

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDENT = 2
        const val PRECISION = 1_000f
    }
}
