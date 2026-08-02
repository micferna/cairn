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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cairn.domain.Equivalences
import app.cairn.domain.Mode
import app.cairn.domain.Session
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
fun JourneyScreen(
    data: CairnViewModel.Snapshot,
    onCorrectMode: (Long, Mode) -> Unit,
) {

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
        item { MonthPanel(data) }
        item { TranslationPanel(data) }
        item { MilestonesPanel(data) }
        item { TrophiesPanel(data) }
        item { RecentPanel(data, onCorrectMode) }
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

/**
 * Le mois en cours face au précédent, à jour égal.
 *
 * La comparaison la plus motivante ne demande personne d'autre : elle demande
 * ce que vous faisiez le mois dernier à la même date.
 */
@Composable
private fun MonthPanel(data: CairnViewModel.Snapshot) {
    val delta = data.month.stepsDelta ?: return
    val better = delta >= 0
    val color = if (better) Stone.Lichen else Stone.Ochre

    Panel(accent = color) {
        SectionLabel("Ce mois-ci, à la même date")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                (if (better) "+" else "") + "${delta.toInt()} %",
                color = color, fontSize = 34.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "de pas par rapport au mois dernier",
                color = Stone.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${Fmt.int(data.month.currentSteps)} pas contre " +
                "${Fmt.int(data.month.previousSteps)} sur la même portion du mois précédent.",
            color = Stone.Faint, fontSize = 11.sp,
        )
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
private fun RecentPanel(data: CairnViewModel.Snapshot, onCorrectMode: (Long, Mode) -> Unit) {
    var editing by remember { mutableStateOf<Session?>(null) }

    Panel {
        SectionLabel("Derniers déplacements")
        Spacer(Modifier.height(2.dp))
        Text(
            "Touchez un déplacement pour corriger son mode.",
            color = Stone.Faint, fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        data.recent.take(RECENT_SHOWN).forEach { s ->
            SessionRow(s) { if (!s.passive) editing = s }
        }
    }

    editing?.let { session ->
        ModePicker(
            session = session,
            onDismiss = { editing = null },
            onPick = { mode ->
                onCorrectMode(session.id, mode)
                editing = null
            },
        )
    }
}

/**
 * Le classifieur affiche son raisonnement ; le corollaire honnête est de
 * pouvoir lui répondre qu'il se trompe. Une session corrigée cesse d'être
 * présentée comme une déduction de la machine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModePicker(session: Session, onDismiss: () -> Unit, onPick: (Mode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Stone.Raised,
        title = { Text("C'était quoi ?", color = Stone.Ink) },
        text = {
            Column {
                if (session.reason.isNotBlank()) {
                    Text(
                        "Cairn a conclu « ${session.mode.label.lowercase()} » : ${session.reason}.",
                        color = Stone.Muted, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Mode.entries.filter { it != Mode.UNKNOWN }.forEach { mode ->
                        val selected = mode == session.mode
                        Box(Modifier.clickable { onPick(mode) }) {
                            Pill(
                                "${mode.glyph()}  ${mode.label}",
                                if (selected) mode.color() else Stone.Faint,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = Stone.Muted) }
        },
    )
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
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
            if (s.corrected) "corrigé" else "${(s.confidence * PERCENT).toInt()} %",
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
