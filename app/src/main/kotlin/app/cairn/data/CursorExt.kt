package app.cairn.data

import android.database.Cursor

/**
 * Lecture d'un curseur par nom de colonne plutôt que par position.
 *
 * Les index numériques (`getString(13)`) sont à la fois illisibles et fragiles :
 * insérer une colonne au milieu d'un SELECT décale silencieusement tout le
 * reste, et le bug ne se voit qu'à l'exécution. Sur une base qu'on demande aux
 * gens d'auditer, autant que le code de lecture se lise comme le schéma.
 */
internal fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name))

internal fun Cursor.strOrNull(name: String): String? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

internal fun Cursor.dbl(name: String): Double = getDouble(getColumnIndexOrThrow(name))
