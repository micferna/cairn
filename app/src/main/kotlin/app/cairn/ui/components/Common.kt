package app.cairn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.domain.Shape
import app.cairn.ui.theme.NumeralHuge
import app.cairn.ui.theme.NumeralLarge
import app.cairn.ui.theme.Stone

/** Bloc de contenu : fond légèrement relevé, filet fin, coins doux. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Stone.Surface)
            .border(1.dp, accent?.copy(alpha = 0.28f) ?: Stone.Hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Stone.Faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

/** Le chiffre héros : ce que la carte serait ailleurs. */
@Composable
fun BigStat(
    value: String,
    unit: String,
    caption: String,
    color: Color = Stone.Ink,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumeralHuge, color = color)
            Spacer(Modifier.width(6.dp))
            Text(
                unit,
                color = Stone.Muted,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Text(caption, color = Stone.Muted, fontSize = 13.sp)
    }
}

@Composable
fun MidStat(value: String, unit: String, caption: String, color: Color = Stone.Ink) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumeralLarge, color = color)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Stone.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(caption, color = Stone.Faint, fontSize = 11.sp)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Stone.Ink) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = Stone.Muted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Barre de progression fine, sans arrondi ostentatoire. */
@Composable
fun ProgressRail(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        drawLine(
            color = Stone.Hairline,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        if (progress > 0f) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Dessine une forme anonymisée.
 *
 * Ce composant reçoit des coordonnées comprises entre -1 et 1, sans unité et
 * sans orientation réelle. Il ne pourrait pas afficher un fond de carte même
 * si on le lui demandait : l'information nécessaire n'existe plus.
 */
@Composable
fun ShapeCanvas(shape: Shape, color: Color, modifier: Modifier = Modifier, strokeDp: Float = 2f) {
    Canvas(modifier) {
        if (shape.points.size < 2) return@Canvas
        val pad = size.minDimension * 0.12f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val scale = minOf(w, h) / 2f

        val path = Path()
        shape.points.forEachIndexed { i, (x, y) ->
            val px = size.width / 2 + x * scale
            val py = size.height / 2 - y * scale
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeDp.dp.toPx(), cap = StrokeCap.Round),
        )
        // Un point de départ, mais lequel ? Il a été pivoté au hasard.
        val (sx, sy) = shape.points.first()
        drawCircle(
            color = color,
            radius = strokeDp.dp.toPx() * 1.6f,
            center = Offset(size.width / 2 + sx * scale, size.height / 2 - sy * scale),
        )
    }
}

/** Empilement de pierres, en guise d'état vide. */
@Composable
fun EmptyCairn(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(64.dp, 56.dp)) {
            val stones = listOf(0.62f to 0.16f, 0.48f to 0.15f, 0.36f to 0.14f, 0.24f to 0.13f)
            var y = size.height
            stones.forEach { (wFrac, hFrac) ->
                val sw = size.width * wFrac
                val sh = size.height * hFrac
                drawRoundRect(
                    color = Stone.Hairline,
                    topLeft = Offset((size.width - sw) / 2, y - sh),
                    size = androidx.compose.ui.geometry.Size(sw, sh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(sh / 2.4f),
                )
                y -= sh + size.height * 0.045f
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            color = Stone.Faint,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** Pastille discrète, utilisée pour les états de confidentialité. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
