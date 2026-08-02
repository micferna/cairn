package app.cairn.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.domain.DayStat
import app.cairn.domain.Equivalences
import app.cairn.sensing.TrackingService
import app.cairn.ui.Fmt
import app.cairn.ui.components.BigStat
import app.cairn.ui.components.KeyValue
import app.cairn.ui.components.MidStat
import app.cairn.ui.components.Panel
import app.cairn.ui.components.Pill
import app.cairn.ui.components.SectionLabel
import app.cairn.ui.theme.Stone
import app.cairn.ui.theme.color
import app.cairn.ui.theme.glyph
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

private data class Hardware(
    val pedometer: Boolean,
    val barometer: Boolean,
    val accelerometer: Boolean,
)

@Composable
fun TodayScreen(
    today: DayStat,
    week: List<DayStat>,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val live by TrackingService.state.collectAsState()
    val context = LocalContext.current

    // On interroge le matériel réel plutôt que de supposer : tous les
    // téléphones n'ont pas de baromètre, et mieux vaut le dire franchement
    // que d'afficher un dénivelé qui resterait éternellement à zéro.
    val hw = remember {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Hardware(
            pedometer = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
            barometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null,
            accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
        )
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TodayHeader(live) }
        if (live.running) item { LiveSessionPanel(live) }
        item { TodayTotalsPanel(today) }
        item { EquivalencePanel(today) }
        item { WeekPanel(week) }
        item { SensorsPanel(hw, live) }
        item { StartStopButton(live.running, onStart, onStop) }
    }
}

@Composable
private fun TodayHeader(live: TrackingService.LiveState) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Aujourd'hui", color = Stone.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                if (live.gpsActive) "position en mémoire vive uniquement"
                else "sans aucune donnée de localisation",
                color = Stone.Faint, fontSize = 11.sp,
            )
        }
        Pill(
            text = if (live.running) "MESURE" else "EN VEILLE",
            color = if (live.running) Stone.Lichen else Stone.Faint,
        )
    }
}

@Composable
private fun LiveSessionPanel(live: TrackingService.LiveState) {
    Panel(accent = live.mode.color()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(live.mode.glyph(), fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    live.mode.label,
                    color = live.mode.color(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(Fmt.duration(live.elapsedS), color = Stone.Faint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val (d, u) = Fmt.distance(live.distanceM)
            MidStat(d, u, "distance", live.mode.color())
            MidStat(Fmt.int(live.steps), "pas", "podomètre")
            MidStat(Fmt.int(live.ascentM.toInt()), "m", "dénivelé +")
        }
        if (live.reason.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            // L'application explique toujours son raisonnement : une décision
            // opaque prise sur vos données n'est pas acceptable.
            Text(
                "Pourquoi ${live.mode.label.lowercase()} ? ${live.reason}.",
                color = Stone.Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TodayTotalsPanel(today: DayStat) {
    Panel {
        val (value, unit) = Fmt.distance(today.distanceM)
        BigStat(value, unit, "parcourus aujourd'hui")
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MidStat(Fmt.int(today.steps), "pas", "podomètre")
            MidStat(Fmt.int(today.ascentM.toInt()), "m", "dénivelé +", Stone.Ochre)
            MidStat(Fmt.duration(today.activeS), "", "en mouvement")
        }
    }
}

@Composable
private fun EquivalencePanel(today: DayStat) {
    val eqD = Equivalences.forDistance(today.distanceM)
    val eqA = Equivalences.forAscent(today.ascentM)
    if (eqD == null && eqA == null) return

    Panel(accent = Stone.Ochre) {
        SectionLabel("Ce que ça représente")
        Spacer(Modifier.height(12.dp))
        eqD?.let { EquivalenceLine(it.emoji, it.text) }
        if (eqD != null && eqA != null) Spacer(Modifier.height(10.dp))
        eqA?.let { EquivalenceLine(it.emoji, it.text) }
        Spacer(Modifier.height(12.dp))
        Text(
            "Un lieu vous situe. Une équivalence vous parle. Cairn a choisi la seconde.",
            color = Stone.Faint, fontSize = 11.sp,
        )
    }
}

@Composable
private fun EquivalenceLine(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Stone.Ink, fontSize = 15.sp)
    }
}

@Composable
private fun WeekPanel(week: List<DayStat>) {
    Panel {
        SectionLabel("Sept derniers jours")
        Spacer(Modifier.height(16.dp))
        WeekBars(week)
    }
}

@Composable
private fun SensorsPanel(hw: Hardware, live: TrackingService.LiveState) {
    Panel {
        SectionLabel("Capteurs de cet appareil")
        Spacer(Modifier.height(10.dp))
        SensorRow("Podomètre", hw.pedometer, "matériel, sans localisation")
        SensorRow("Baromètre", hw.barometer, "dénivelé au décimètre")
        SensorRow("Accéléromètre", hw.accelerometer, "reconnaissance du mode")
        KeyValue(
            "Localisation",
            if (live.gpsActive) "éphémère, en RAM" else "éteinte",
            if (live.gpsActive) Stone.Ochre else Stone.Faint,
        )
        if (!live.altitudeM.isNaN() && live.running) {
            KeyValue("Altitude estimée", "${live.altitudeM.toInt()} m")
        }
        if (!hw.barometer) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Sans baromètre, le dénivelé restera à zéro : Cairn préfère ne rien " +
                    "afficher plutôt que d'estimer une altitude à partir du GPS, " +
                    "qui serait à la fois imprécise et bavarde.",
                color = Stone.Faint, fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun SensorRow(name: String, present: Boolean, whenPresent: String) {
    KeyValue(
        name,
        if (present) whenPresent else "absent de cet appareil",
        if (present) Stone.Lichen else Stone.Faint,
    )
}

@Composable
private fun StartStopButton(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (running) onStop else onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Stone.Raised else Stone.Ochre,
            contentColor = if (running) Stone.Ink else Stone.Void,
        ),
    ) {
        Text(
            if (running) "Arrêter la mesure" else "Démarrer la mesure",
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Petit histogramme de la semaine. Volontairement sans axes ni grille. */
@Composable
private fun WeekBars(week: List<DayStat>) {
    val maxV = (week.maxOfOrNull { it.distanceM } ?: 0.0).coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEach { day ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val frac = (day.distanceM / maxV).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    val h = (size.height * frac).coerceAtLeast(if (day.distanceM > 0) 4f else 2f)
                    drawRoundRect(
                        color = if (day.distanceM > 0) Stone.Ochre.copy(alpha = 0.35f + frac * 0.65f)
                        else Stone.Hairline,
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(size.width / 3f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(Fmt.weekdayShort(day.day), color = Stone.Faint, fontSize = 10.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Total : ${Fmt.km(week.sumOf { it.distanceM })} km · " +
            "D+ ${Fmt.int(week.sumOf { it.ascentM }.toInt())} m",
        color = Stone.Muted, fontSize = 12.sp,
    )
}
