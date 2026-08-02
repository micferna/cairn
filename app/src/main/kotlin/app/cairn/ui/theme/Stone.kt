package app.cairn.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette « pierre et lichen ».
 *
 * Cairn ne ressemble pas à une application de sport : pas de néon, pas de
 * dégradé fluo, pas de carte. L'écran doit évoquer un caillou posé sur un
 * sentier au crépuscule — sombre, minéral, avec une seule touche chaude.
 */
object Stone {
    val Void = Color(0xFF0B0E0D)
    val Surface = Color(0xFF141917)
    val Raised = Color(0xFF1C2320)
    val Hairline = Color(0xFF263029)

    val Ink = Color(0xFFE8EDE9)
    val Muted = Color(0xFF8B968F)
    val Faint = Color(0xFF5A6660)

    /** L'ocre du cairn au soleil couchant. Un seul accent, utilisé avec parcimonie. */
    val Ochre = Color(0xFFD98B4E)
    val Lichen = Color(0xFF7FA88A)
    val Alert = Color(0xFFC46F6F)
}
