package app.cairn.data

import android.content.ContentValues
import app.cairn.domain.LedgerEntry
import app.cairn.domain.LedgerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le registre de transparence.
 *
 * Toutes les applications disent "nous respectons votre vie privée". Aucune ne
 * vous montre la liste exhaustive de ce qu'elle a écrit sur votre disque.
 * Ici, chaque octet persisté et chaque capteur ouvert produit une ligne que
 * l'utilisateur peut lire dans l'écran Confidentialité.
 *
 * Deux conséquences intéressantes :
 *  - la promesse devient vérifiable, pas déclarative ;
 *  - côté développeur, il devient impossible d'ajouter discrètement une
 *    collecte : elle apparaîtrait dans le registre de tous les utilisateurs.
 *
 * Le registre est plafonné à [MAX_ENTRIES]. L'élagage est lui-même consigné.
 */
class Ledger(private val db: CairnDb) {

    private val _revision = MutableStateFlow(0L)

    /** S'incrémente à chaque nouvelle ligne, pour que l'UI se rafraîchisse. */
    val revision: StateFlow<Long> = _revision

    fun record(kind: LedgerKind, detail: String, bytes: Int = 0) {
        val values = ContentValues().apply {
            put("at_ms", System.currentTimeMillis())
            put("kind", kind.name)
            put("detail", detail)
            put("bytes", bytes)
        }
        val w = db.writableDatabase
        w.insert("ledger", null, values)

        val count = android.database.DatabaseUtils.queryNumEntries(w, "ledger")
        if (count > MAX_ENTRIES) {
            val pruned = count - MAX_ENTRIES
            w.execSQL(
                "DELETE FROM ledger WHERE id IN (SELECT id FROM ledger ORDER BY id ASC LIMIT ?)",
                arrayOf(pruned.toString())
            )
            w.insert("ledger", null, ContentValues().apply {
                put("at_ms", System.currentTimeMillis())
                put("kind", LedgerKind.DELETE.name)
                put("detail", "Élagage du registre : $pruned lignes les plus anciennes supprimées")
                put("bytes", 0)
            })
        }
        _revision.value = _revision.value + 1
    }

    fun recent(limit: Int = 200): List<LedgerEntry> {
        val out = mutableListOf<LedgerEntry>()
        db.readableDatabase.rawQuery(
            "SELECT id, at_ms, kind, detail, bytes FROM ledger ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += LedgerEntry(
                    id = c.long("id"),
                    atMs = c.long("at_ms"),
                    kind = runCatching { LedgerKind.valueOf(c.str("kind")) }
                        .getOrDefault(LedgerKind.WRITE),
                    detail = c.str("detail"),
                    bytes = c.int("bytes"),
                )
            }
        }
        return out
    }

    /** Total d'octets déclarés écrits, tous temps confondus. */
    fun totalBytesWritten(): Long {
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(bytes), 0) FROM ledger WHERE kind = ?",
            arrayOf(LedgerKind.WRITE.name)
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    fun count(): Long =
        android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, "ledger")

    private companion object {
        const val MAX_ENTRIES = 2_000L
    }
}
