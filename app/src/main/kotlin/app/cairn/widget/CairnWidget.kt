package app.cairn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.cairn.MainActivity
import app.cairn.R
import app.cairn.data.CairnRepository
import app.cairn.data.Settings
import app.cairn.domain.Streak
import java.time.LocalDate

/**
 * Le widget d'écran d'accueil, écrit en RemoteViews.
 *
 * ============================================================================
 * POURQUOI PAS GLANCE
 * ============================================================================
 *
 * La première version utilisait Jetpack Glance, qui permet d'écrire un widget
 * en Compose. C'était plus agréable à écrire, et c'était un mauvais choix ici.
 *
 * `glance-appwidget` dépend de WorkManager, qui dépend lui-même de Room. La
 * fusion de manifestes ajoutait alors à Cairn onze composants d'arrière-plan
 * que personne n'avait écrits — dont un récepteur `BOOT_COMPLETED`, des
 * services d'alarme et de tâche planifiée, un service Room — et trois
 * permissions, dont **ACCESS_NETWORK_STATE**.
 *
 * C'est le contrôle automatique de l'APK, en intégration continue, qui a
 * attrapé la régression avant publication. Une application qui affirme n'avoir
 * aucun accès réseau et aucun démarrage au boot ne peut pas embarquer tout cela
 * pour dessiner un nombre et une barre de progression.
 *
 * RemoteViews demande un peu plus de code et une mise en page en XML. En
 * échange : zéro dépendance ajoutée, zéro composant d'arrière-plan, zéro
 * permission. Sur ce projet, l'arbitrage n'est même pas serré.
 *
 * ============================================================================
 *
 * Le widget lit la base locale au moment du rendu. Android le redessine quand
 * l'écran d'accueil s'affiche, et l'application appelle [refresh] après chaque
 * relevé du podomètre.
 */
class CairnWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        runCatching {
            val views = render(context)
            appWidgetIds.forEach { manager.updateAppWidget(it, views) }
        }
        pending.finish()
    }

    private fun render(context: Context): RemoteViews {
        val repo = CairnRepository.get(context)
        val goal = Settings.get(context).state.value.dailyGoal
        val today = LocalDate.now()
        val steps = repo.dayStat(today.toString()).steps
        val streak = Streak.compute(repo.allDays(), goal, today)

        return RemoteViews(context.packageName, R.layout.widget_cairn).apply {
            setTextViewText(R.id.widget_steps, grouped(steps))

            if (goal > 0) {
                setViewVisibility(R.id.widget_gauge, View.VISIBLE)
                setViewVisibility(R.id.widget_status, View.VISIBLE)
                setProgressBar(
                    R.id.widget_gauge,
                    GAUGE_MAX,
                    (steps.toLong() * GAUGE_MAX / goal).toInt().coerceIn(0, GAUGE_MAX),
                    false,
                )
                setTextViewText(
                    R.id.widget_status,
                    when {
                        streak.todayDone && streak.current > 1 ->
                            "Objectif atteint · série de ${streak.current} jours"
                        streak.todayDone -> "Objectif atteint"
                        else -> "encore ${grouped(streak.remainingToday)} pas"
                    },
                )
                setTextColor(
                    R.id.widget_status,
                    context.getColor(if (streak.todayDone) R.color.widget_accent else R.color.widget_muted),
                )
            } else {
                setViewVisibility(R.id.widget_gauge, View.GONE)
                setViewVisibility(R.id.widget_status, View.GONE)
            }

            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    private fun grouped(v: Int): String {
        val digits = v.toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % GROUP_SIZE == 0) sb.append(NARROW_NBSP)
            sb.append(ch)
        }
        return sb.toString()
    }

    companion object {
        private const val GAUGE_MAX = 100
        private const val GROUP_SIZE = 3

        /** Espace fine insécable : le séparateur de milliers typographique français. */
        private const val NARROW_NBSP = ' '

        /** Redessine le widget après un relevé. Sans effet s'il n'est pas posé. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, CairnWidget::class.java))
                if (ids.isNotEmpty()) {
                    context.sendBroadcast(
                        Intent(context, CairnWidget::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                    )
                }
            }
        }
    }
}
