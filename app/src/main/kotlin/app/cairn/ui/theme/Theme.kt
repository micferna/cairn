package app.cairn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.cairn.domain.Mode

private val FOOT_GREEN = Color(0xFF7FA88A)
private val RUN_CHARTREUSE = Color(0xFFC4D97A)
private val BIKE_GLACIER = Color(0xFF6FA8C4)
private val SCOOTER_VIOLET = Color(0xFFA98BC4)
private val CAR_OCHRE = Color(0xFFD98B4E)
private val TRAIN_BRASS = Color(0xFFC4A24E)
private val PLANE_RUST = Color(0xFFC46F6F)
private val UNKNOWN_SLATE = Color(0xFF5A6660)

/** Une couleur par mode, stable dans toute l'application. */
fun Mode.color(): Color = when (this) {
    Mode.FOOT -> FOOT_GREEN
    Mode.RUN -> RUN_CHARTREUSE
    Mode.BIKE -> BIKE_GLACIER
    Mode.SCOOTER -> SCOOTER_VIOLET
    Mode.CAR -> CAR_OCHRE
    Mode.TRAIN -> TRAIN_BRASS
    Mode.PLANE -> PLANE_RUST
    Mode.UNKNOWN -> UNKNOWN_SLATE
}

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
