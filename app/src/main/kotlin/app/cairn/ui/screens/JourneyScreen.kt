package app.cairn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.domain.Equivalences
import app.cairn.ui.CairnViewModel
import app.cairn.ui.Fmt
import app.cairn.ui.components.BigStat
import app.cairn.ui.components.EmptyCairn
import app.cairn.ui.components.MidStat
import app.cairn.ui.components.Panel
import app.cairn.ui.components.Pill
import app.cairn.ui.components.ProgressRail
import app.cairn.ui.components.SectionLabel
import app.cairn.ui.theme.Stone
import app.cairn.ui.theme.color
import app.cairn.ui.theme.glyph

/** En dessous, le dénivelé relève du bruit barométrique plus que du relief. */
private const val MIN_DISPLAYED_ASCENT_M = 5.0
private const val RECENT_SHOWN = 12
private const val PERCENT = 100

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JourneyScreen(data: CairnViewModel.Snapshot) {

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("Parcours", color = Stone.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("depuis le premier jour", color = Stone.Faint, fontSize = 11.sp)
            }
        }

        if (data.sessionCount == 0L) {
            item {
                EmptyCairn(
                    "Rien encore.\nChaque déplacement ajoutera une pierre à l'édifice."
                )
            }
            return@LazyColumn
        }

        item { TotalsPanel(data) }
        item { TranslationPanel(data) }
        item { MilestonesPanel(data) }
        item { TrophiesPanel(data) }
        item { RecentPanel(data) }
    }
}

@Composable
private fun TotalsPanel(data: CairnViewModel.Snapshot) {
    Panel {
        val (v, u) = Fmt.distance(data.totalDistanceM)
        BigStat(v, u, "parcourus au total")
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MidStat(Fmt.int(data.totalAscentM.toInt()), "m", "dénivelé cumulé", Stone.Ochre)
            MidStat(Fmt.int(data.totalSteps), "pas", "au podomètre")
            MidStat(Fmt.int(data.sessionCount), "", "déplacements")
        }
    }
}

@Composable
private fun TranslationPanel(data: CairnViewModel.Snapshot) {
    val eq = Equivalences.forDistance(data.totalDistanceM)
    val eqA = Equivalences.forAscent(data.totalAscentM)
    Panel(accent = Stone.Ochre) {
        SectionLabel("Traduction")
        Spacer(Modifier.height(12.dp))
        eq?.let {
            TranslationLine(it.emoji, it.text)
            Spacer(Modifier.height(10.dp))
        }
        eqA?.let { TranslationLine(it.emoji, it.text) }
    }
}

@Composable
private fun TranslationLine(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Stone.Ink, fontSize = 16.sp)
    }
}

@Composable
private fun MilestonesPanel(data: CairnViewModel.Snapshot) {
    val nextD = Equivalences.nextDistanceMilestone(data.totalDistanceM)
    val nextA = Equivalences.nextAscentMilestone(data.totalAscentM)
    Panel {
        SectionLabel("Prochain palier")
        Spacer(Modifier.height(14.dp))
        nextD?.let { m ->
            MilestoneRow(
                emoji = m.emoji,
                label = m.label,
                progress = m.progress.toFloat(),
                remaining = "encore ${Fmt.km(m.targetM - data.totalDistanceM)} km",
                color = Stone.Ochre,
            )
        }
        if (nextD != null && nextA != null) Spacer(Modifier.height(18.dp))
        nextA?.let { m ->
            MilestoneRow(
                emoji = m.emoji,
                label = m.label,
                progress = m.progress.toFloat(),
                remaining = "encore ${Fmt.int((m.targetM - data.totalAscentM).toInt())} m de D+",
                color = Stone.Lichen,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrophiesPanel(data: CairnViewModel.Snapshot) {
    val unlocked = Equivalences.distanceUnlocked(data.totalDistanceM) +
        Equivalences.ascentUnlocked(data.totalAscentM)
    if (unlocked.isEmpty()) return

    Panel {
        SectionLabel("Franchis")
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            unlocked.forEach { ref -> Pill("${ref.emoji}  ${ref.label}", Stone.Lichen) }
        }
    }
}

@Composable
private fun RecentPanel(data: CairnViewModel.Snapshot) {
    Panel {
        SectionLabel("Derniers déplacements")
        Spacer(Modifier.height(6.dp))
        data.recent.take(RECENT_SHOWN).forEach { s -> SessionRow(s) }
    }
}

@Composable
private fun SessionRow(s: app.cairn.domain.Session) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.mode.glyph(), fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${s.mode.label} · ${Fmt.km(s.distanceM)} km",
                color = Stone.Ink, fontSize = 13.sp,
            )
            Text(
                buildString {
                    append(Fmt.dayLabel(s.day))
                    append(", vers ${s.hourBucket}h · ")
                    append(Fmt.duration(s.durationS))
                    if (s.maxSpeedMs > 0) append(" · max ${Fmt.speedKmh(s.maxSpeedMs)} km/h")
                    if (s.ascentM >= MIN_DISPLAYED_ASCENT_M) {
                        append(" · D+ ${s.ascentM.toInt()} m")
                    }
                },
                color = Stone.Faint, fontSize = 11.sp,
            )
        }
        Text(
            "${(s.confidence * PERCENT).toInt()} %",
            color = s.mode.color().copy(alpha = 0.8f), fontSize = 11.sp,
        )
    }
}

@Composable
private fun MilestoneRow(
    emoji: String,
    label: String,
    progress: Float,
    remaining: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Stone.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()} %", color = color, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        ProgressRail(progress, color)
        Spacer(Modifier.height(6.dp))
        Text(remaining, color = Stone.Faint, fontSize = 11.sp)
    }
}
