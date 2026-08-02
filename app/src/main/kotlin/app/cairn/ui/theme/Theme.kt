package app.cairn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.cairn.domain.Mode

/**
 * Une couleur par mode, stable dans toute l'application.
 *
 * La valeur vient de [Mode.hex] plutôt que d'être redéfinie ici : l'écran et
 * l'image partagée doivent afficher exactement la même teinte, et deux tables
 * de couleurs finissent toujours par diverger.
 */
fun Mode.color(): Color = Color(hex().toColorInt())

fun Mode.glyph(): String = when (this) {
    Mode.FOOT -> "🚶"
    Mode.RUN -> "🏃"
    Mode.BIKE -> "🚲"
    Mode.SCOOTER -> "🛴"
    Mode.CAR -> "🚗"
    Mode.TRAIN -> "🚆"
    Mode.PLANE -> "✈"
    Mode.UNKNOWN -> "•"
}

private val CairnColors = darkColorScheme(
    primary = Stone.Ochre,
    onPrimary = Stone.Void,
    secondary = Stone.Lichen,
    onSecondary = Stone.Void,
    background = Stone.Void,
    onBackground = Stone.Ink,
    surface = Stone.Surface,
    onSurface = Stone.Ink,
    surfaceVariant = Stone.Raised,
    onSurfaceVariant = Stone.Muted,
    outline = Stone.Hairline,
    error = Stone.Alert,
)

/**
 * Les grands nombres sont l'élément graphique principal de l'application :
 * ils sont serrés, lourds, et occupent l'espace qu'une carte prendrait ailleurs.
 */
val NumeralHuge = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Light,
    fontSize = 64.sp,
    lineHeight = 64.sp,
    letterSpacing = (-3).sp,
)

val NumeralLarge = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 32.sp,
    lineHeight = 34.sp,
    letterSpacing = (-1).sp,
)

private val CairnTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun CairnTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Cairn est sombre en toutes circonstances : c'est une identité, pas un réglage.
    MaterialTheme(
        colorScheme = CairnColors,
        typography = CairnTypography,
        content = content,
    )
}
