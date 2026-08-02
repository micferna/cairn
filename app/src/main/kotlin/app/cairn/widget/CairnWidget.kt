package app.cairn.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.cairn.MainActivity
import app.cairn.data.CairnRepository
import app.cairn.data.Settings
import app.cairn.domain.Streak
import java.time.LocalDate

private val VOID = Color(0xFF0B0E0D)
private val INK = Color(0xFFE8EDE9)
private val MUTED = Color(0xFF8B968F)
private val FAINT = Color(0xFF5A6660)
private val OCHRE = Color(0xFFD98B4E)
private val HAIRLINE = Color(0xFF263029)

private const val GAUGE_SEGMENTS = 12
private const val GROUP_SIZE = 3

/** Espace fine insécable : le séparateur de milliers typographique français. */
private const val NARROW_NBSP = ' '

/**
 * Le widget d'écran d'accueil.
 *
 * C'est ce qui fait passer Cairn d'une application qu'on pense à ouvrir à une
 * information qu'on croise. Il n'affiche que deux choses — les pas du jour et
 * ce qu'il reste avant l'objectif — parce qu'un widget qu'on doit lire n'est
 * pas un widget.
 *
 * Il lit la base locale au moment du rendu. Aucun service, aucune tâche
 * périodique : Android le redessine quand l'écran d'accueil s'affiche, et
 * l'application le rafraîchit après chaque relevé du podomètre.
 */
class CairnWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = CairnRepository.get(context)
        val goal = Settings.get(context).state.value.dailyGoal
        val today = LocalDate.now()
        val stat = repo.dayStat(today.toString())
        val streak = Streak.compute(repo.allDays(), goal, today)

        provideContent {
            GlanceTheme {
                WidgetBody(steps = stat.steps, goal = goal, streak = streak)
            }
        }
    }

    @Composable
    private fun WidgetBody(steps: Int, goal: Int, streak: Streak.State) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(VOID)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                "PAS AUJOURD'HUI",
                style = TextStyle(color = androidx.glance.unit.ColorProvider(FAINT), fontSize = 10.sp),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                grouped(steps),
                style = TextStyle(
                    color = androidx.glance.unit.ColorProvider(INK),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (goal > 0) {
                Spacer(GlanceModifier.height(10.dp))
                Gauge(progress = steps.toFloat() / goal)
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    when {
                        streak.todayDone -> "Objectif atteint"
                        else -> "encore ${grouped(streak.remainingToday)} pas"
                    },
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(
                            if (streak.todayDone) OCHRE else MUTED
                        ),
                        fontSize = 12.sp,
                    ),
                )
                if (streak.current > 0) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        "série de ${streak.current} jour${if (streak.current > 1) "s" else ""}",
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(FAINT),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Jauge segmentée : Glance ne dispose pas de Canvas, on compose donc la
     * barre à partir de petits blocs colorés. C'est aussi plus lisible qu'une
     * barre continue sur un écran d'accueil chargé.
     */
    @Composable
    private fun Gauge(progress: Float) {
        val filled = (progress.coerceIn(0f, 1f) * GAUGE_SEGMENTS).toInt()
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            repeat(GAUGE_SEGMENTS) { i ->
                Spacer(
                    GlanceModifier
                        .width(10.dp)
                        .height(5.dp)
                        .cornerRadius(3.dp)
                        .background(if (i < filled) OCHRE else HAIRLINE)
                )
                if (i < GAUGE_SEGMENTS - 1) Spacer(GlanceModifier.width(3.dp))
            }
        }
    }

    private fun grouped(v: Int): String {
        val s = v.toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % GROUP_SIZE == 0) sb.append(NARROW_NBSP)
            sb.append(ch)
        }
        return sb.toString()
    }

    companion object {
        /** Redessine le widget après un relevé. Sans effet s'il n'est pas posé. */
        suspend fun refresh(context: Context) {
            runCatching { CairnWidget().updateAll(context) }
        }
    }
}

class CairnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CairnWidget()
}
