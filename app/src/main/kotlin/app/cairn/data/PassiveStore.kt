package app.cairn.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.cairn.domain.DistanceSource
import app.cairn.domain.LedgerKind
import app.cairn.domain.Mode

/**
 * Tenue de compte du podomètre matériel.
 *
 * Le compteur du coprocesseur est cumulé depuis le démarrage de l'appareil et
 * ne connaît ni les journées ni les redémarrages. Cette classe fait la
 * traduction : elle mémorise un point d'ancrage, en déduit les pas d'une
 * journée, et tient à jour une ligne unique par jour.
 *
 * Séparée du dépôt principal parce que c'est une comptabilité à part entière —
 * avec ses cas tordus (minuit, redémarrage, première installation) — et qu'on
 * doit pouvoir la lire d'un bloc pour vérifier qu'elle ne triche pas.
 */
class PassiveStore(
    private val db: CairnDb,
    private val ledger: Ledger,
    private val recomputeDay: (String) -> Unit,
    private val onChanged: () -> Unit,
) {

    /** Dernier point d'ancrage : (jour, compteur brut, pas déjà attribués au jour). */
    fun anchor(): Triple<String, Long, Long>? {
        db.readableDatabase.rawQuery(
            "SELECT day, raw_counter, day_steps FROM step_anchor WHERE id = 1", null,
        ).use { c ->
            return if (c.moveToFirst()) {
                Triple(c.str("day"), c.long("raw_counter"), c.long("day_steps"))
            } else null
        }
    }

    fun setAnchor(day: String, rawCounter: Long, daySteps: Long) {
        db.writableDatabase.insertWithOnConflict(
            "step_anchor", null,
            ContentValues().apply {
                put("id", ANCHOR_ROW)
                put("day", day)
                put("raw_counter", rawCounter)
                put("day_steps", daySteps)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Enregistre — ou met à jour — la session passive du jour.
     *
     * Une seule ligne par journée, remplacée à chaque relevé plutôt que
     * dupliquée : sur une base qu'on demande aux gens d'auditer, mieux vaut une
     * ligne juste que cinquante lignes à recoller.
     */
    fun upsertDay(day: String, totalSteps: Int, strideM: Double) {
        val values = ContentValues().apply {
            put("day", day)
            put("hour_bucket", 0)
            put("mode", Mode.FOOT.name)
            put("duration_s", 0L)
            put("distance_m", totalSteps * strideM)
            put("steps", totalSteps)
            put("ascent_m", 0.0)
            put("descent_m", 0.0)
            put("avg_speed_ms", 0.0)
            put("max_speed_ms", 0.0)
            put("confidence", PASSIVE_CONFIDENCE)
            put("source", DistanceSource.PEDOMETER.name)
            put("reason", "compté par le podomètre matériel, sans que l'application tourne")
            put("passive", 1)
        }
        val w = db.writableDatabase
        val updated = w.update("session", values, "day = ? AND passive = 1", arrayOf(day))
        if (updated == 0) {
            w.insert("session", null, values)
            ledger.record(
                LedgerKind.WRITE,
                "Comptage passif ouvert pour le $day — les pas du jour sont relevés " +
                    "sur le compteur matériel à chaque ouverture de l'application",
                ROW_BYTES,
            )
        }
        recomputeDay(day)
        onChanged()
    }

    fun recordAnchorCreated(rawCounter: Long) {
        ledger.record(
            LedgerKind.WRITE,
            "Point de référence du podomètre posé (compteur matériel à $rawCounter). " +
                "Les pas antérieurs ne sont pas crédités : ils n'ont pas été observés.",
            ANCHOR_BYTES,
        )
    }

    fun recordReboot() {
        ledger.record(
            LedgerKind.DISCARD,
            "Redémarrage de l'appareil détecté : le compteur matériel est reparti de zéro. " +
                "Les pas entre le redémarrage et ce relevé sont perdus, Cairn ne les invente pas.",
        )
    }

    private companion object {
        const val ANCHOR_ROW = 1
        const val ROW_BYTES = 128
        const val ANCHOR_BYTES = 32

        /** Un compteur matériel ne se trompe pas de mode : c'est de la marche. */
        const val PASSIVE_CONFIDENCE = 1.0
    }
}
