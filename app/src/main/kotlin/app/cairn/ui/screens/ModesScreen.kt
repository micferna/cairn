package app.cairn.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.data.ModeTotals
import app.cairn.domain.Equivalences
import app.cairn.domain.Mode
import app.cairn.ui.CairnViewModel
import app.cairn.ui.Fmt
import app.cairn.ui.components.BigStat
import app.cairn.ui.components.EmptyCairn
import app.cairn.ui.components.MidStat
import app.cairn.ui.components.Panel
import app.cairn.ui.components.SectionLabel
import app.cairn.ui.theme.Stone
import app.cairn.ui.theme.color
import app.cairn.ui.theme.glyph

@Composable
fun ModesScreen(data: CairnViewModel.Snapshot) {

    val year = data.year.filter { it.distanceM > 0 }
    val total = year.sumOf { it.distanceM }
    val co2 = year.sumOf { it.co2Grams }
    val avoided = Equivalences.avoidedCarbonGrams(
        data.recent.filter { it.mode.isHumanPowered }
    )

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("Modes", color = Stone.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("comment vous vous déplacez, cette année", color = Stone.Faint, fontSize = 11.sp)
            }
        }

        if (year.isEmpty()) {
            item {
                EmptyCairn(
                    "Aucun déplacement enregistré cette année.\n" +
                        "Cairn reconnaît la marche, la course, le vélo, la trottinette, " +
                        "la voiture, le train et l'avion."
                )
            }
            return@LazyColumn
        }

        item {
            Panel {
                val (v, u) = Fmt.distance(total)
                BigStat(v, u, "tous modes confondus cette année")
                Spacer(Modifier.height(18.dp))
                StackedModeBar(year, total)
            }
        }

        item {
            Panel {
                SectionLabel("Répartition")
                Spacer(Modifier.height(6.dp))
                year.forEach { m -> ModeRow(m, total) }
            }
        }

        // ------------------------------------------------------------- carbone
        item {
            Panel(accent = if (co2 > 0) Stone.Alert else Stone.Lichen) {
                SectionLabel("Empreinte")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val (cv, cu) = Fmt.co2(co2)
                    MidStat(cv, "${cu} CO₂e", "émis", Stone.Alert)
                    val (av, au) = Fmt.co2(avoided)
                    MidStat(av, "${au} CO₂e", "évités à la force du mollet", Stone.Lichen)
                }
                Spacer(Modifier.height(14.dp))
                if (co2 > 0) {
                    Text(
                        "Soit ${Equivalences.carbonPhrase(co2)}.",
                        color = Stone.Muted, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Ordres de grandeur ADEME, par passager. Calculés sur l'appareil, " +
                        "à partir de distances agrégées : aucun trajet identifiable n'est nécessaire " +
                        "pour connaître son empreinte.",
                    color = Stone.Faint, fontSize = 11.sp,
                )
            }
        }

        // ------------------------------------------------------- vitesse de vie
        item {
            val totalDuration = data.allTime.sumOf { it.durationS }
            val totalDist = data.allTime.sumOf { it.distanceM }
            if (totalDuration > 0) {
                Panel {
                    SectionLabel("La statistique inutile et irrésistible")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Votre vitesse moyenne, tous déplacements confondus : " +
                            "${Fmt.speedKmh(totalDist / totalDuration)} km/h.",
                        color = Stone.Ink, fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Mesurée sur ${Fmt.duration(totalDuration)} de mouvement.",
                        color = Stone.Faint, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** Une seule barre, tous les modes empilés. Compact et immédiatement lisible. */
@Composable
private fun StackedModeBar(totals: List<ModeTotals>, total: Double) {
    if (total <= 0) return
    Canvas(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
    ) {
        var x = 0f
        totals.forEach { m ->
            val w = (m.distanceM / total).toFloat() * size.width
            drawRect(
                color = m.mode.color(),
                topLeft = Offset(x, 0f),
                size = Size(w.coerceAtLeast(0f), size.height),
            )
            x += w
        }
    }
}

@Composable
private fun ModeRow(m: ModeTotals, total: Double) {
    val share = if (total > 0) m.distanceM / total else 0.0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .let { it }
        ) {
            Canvas(Modifier.size(10.dp)) {
                drawRoundRect(
                    color = m.mode.color(),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(m.mode.glyph(), fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.mode.label, color = Stone.Ink, fontSize = 14.sp)
            Text(
                "${m.sessions} déplacement${if (m.sessions > 1) "s" else ""} · " +
                    Fmt.duration(m.durationS) +
                    if (m.co2Grams > 0) " · ${Fmt.co2(m.co2Grams).let { "${it.first} ${it.second}" }} CO₂e"
                    else "",
                color = Stone.Faint, fontSize = 11.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${Fmt.km(m.distanceM)} km", color = Stone.Ink, fontSize = 13.sp)
            Text("${(share * 100).toInt()} %", color = Stone.Faint, fontSize = 11.sp)
        }
    }
}
