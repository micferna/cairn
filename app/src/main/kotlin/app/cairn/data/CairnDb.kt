package app.cairn.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base SQLite écrite à la main, sans ORM et sans génération de code.
 *
 * C'est un choix produit autant que technique : sur une application dont
 * l'argument est "on ne collecte presque rien", il faut que n'importe qui
 * puisse lire l'intégralité du schéma en une minute et constater qu'aucune
 * colonne ne peut contenir une position. Room générerait ce fichier à la
 * compilation ; ici il est sous vos yeux.
 *
 * Cherchez une colonne latitude ou longitude. Il n'y en a pas, dans aucune
 * table, et il n'y en a jamais eu.
 */
class CairnDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE session (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              day          TEXT    NOT NULL,          -- '2026-08-02', date locale
              hour_bucket  INTEGER NOT NULL,          -- 0..23, heure arrondie
              mode         TEXT    NOT NULL,
              duration_s   INTEGER NOT NULL,
              distance_m   REAL    NOT NULL,
              steps        INTEGER NOT NULL,
              ascent_m     REAL    NOT NULL,
              descent_m    REAL    NOT NULL,
              avg_speed_ms REAL    NOT NULL,
              max_speed_ms REAL    NOT NULL,
              confidence   REAL    NOT NULL,
              source       TEXT    NOT NULL,
              reason       TEXT    NOT NULL DEFAULT '',
              shape_json   TEXT                       -- forme sans échelle ni orientation
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session_day ON session(day)")
        db.execSQL("CREATE INDEX idx_session_mode ON session(mode)")

        db.execSQL(
            """
            CREATE TABLE day_stat (
              day        TEXT PRIMARY KEY,
              steps      INTEGER NOT NULL DEFAULT 0,
              distance_m REAL    NOT NULL DEFAULT 0,
              ascent_m   REAL    NOT NULL DEFAULT 0,
              descent_m  REAL    NOT NULL DEFAULT 0,
              active_s   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Le registre de transparence. Chaque écriture disque et chaque
        // ouverture de capteur y laisse une ligne, consultable dans l'app.
        db.execSQL(
            """
            CREATE TABLE ledger (
              id     INTEGER PRIMARY KEY AUTOINCREMENT,
              at_ms  INTEGER NOT NULL,
              kind   TEXT    NOT NULL,
              detail TEXT    NOT NULL,
              bytes  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ledger_at ON ledger(at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 : rien à migrer pour l'instant.
    }

    companion object {
        const val NAME = "cairn.db"
        const val VERSION = 1
    }
}
