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

    /**
     * Création du schéma, écrite pour être rejouable sans dommage.
     *
     * `IF NOT EXISTS` n'est pas décoratif : si un fichier `cairn.db` se retrouve
     * en place sans que sa `user_version` ait été renseignée — restauration
     * partielle, copie manuelle, outil externe — `SQLiteOpenHelper` le croit
     * vierge et rappelle cette méthode sur des tables déjà présentes. Sans cette
     * clause, l'application se termine sur une SQLiteException au démarrage,
     * sans aucun moyen pour l'utilisateur de s'en sortir autrement qu'en
     * désinstallant. Le coût de la robustesse est ici de trois mots.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS session (
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_day ON session(day)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_mode ON session(mode)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS day_stat (
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
            CREATE TABLE IF NOT EXISTS ledger (
              id     INTEGER PRIMARY KEY AUTOINCREMENT,
              at_ms  INTEGER NOT NULL,
              kind   TEXT    NOT NULL,
              detail TEXT    NOT NULL,
              bytes  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_at ON ledger(at_ms DESC)")

        createV2(db)
    }

    /**
     * Ajouts de la version 2 : comptage passif et correction manuelle du mode.
     *
     * Isolé dans sa propre méthode pour être appelé aussi bien à la création
     * d'une base neuve qu'à la migration d'une base existante — sans quoi les
     * deux chemins divergent et finissent par ne plus produire le même schéma.
     */
    private fun createV2(db: SQLiteDatabase) {
        // Point d'ancrage du podomètre matériel. Le capteur compte les pas dans
        // son coprocesseur, que l'application tourne ou non ; il suffit de
        // mémoriser la dernière valeur lue pour retrouver les pas manqués.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS step_anchor (
              id          INTEGER PRIMARY KEY CHECK (id = 1),
              day         TEXT    NOT NULL,
              raw_counter INTEGER NOT NULL,
              day_steps   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Marque les sessions issues du comptage passif : il n'y en a qu'une
        // par jour, et elle est mise à jour plutôt que dupliquée.
        if (!hasColumn(db, "session", "passive")) {
            db.execSQL("ALTER TABLE session ADD COLUMN passive INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_session_passive " +
                "ON session(day) WHERE passive = 1"
        )

        // Mode corrigé à la main par l'utilisateur : le classifieur explique son
        // raisonnement, il faut donc pouvoir lui dire qu'il s'est trompé.
        if (!hasColumn(db, "session", "corrected")) {
            db.execSQL("ALTER TABLE session ADD COLUMN corrected INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) if (c.getString(nameIndex) == column) return true
            false
        }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < VERSION_2) createV2(db)
    }

    companion object {
        const val NAME = "cairn.db"
        private const val VERSION_2 = 2
        const val VERSION = 2
    }
}
