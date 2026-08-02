package app.cairn.domain

import java.time.LocalDate

/**
 * La série de jours où l'objectif a été atteint.
 *
 * C'est le seul mécanisme de motivation que Cairn s'autorise, et il est
 * volontairement solitaire : pas de classement, pas de comparaison avec des
 * inconnus, pas de notification qui culpabilise. Vous contre vous.
 *
 * Une nuance de conception qui compte : **la journée en cours ne casse jamais
 * la série.** Regarder son compteur à 9 h du matin ne doit pas afficher « série
 * perdue » alors qu'il reste quinze heures pour marcher. Tant que la journée
 * n'est pas terminée, elle est en sursis, pas en échec.
 */
object Streak {

    data class State(
        /** Jours consécutifs atteints, journée en cours comprise si déjà validée. */
        val current: Int,
        /** Meilleure série jamais réalisée. */
        val best: Int,
        /** L'objectif du jour est déjà atteint. */
        val todayDone: Boolean,
        /** Pas restants pour valider aujourd'hui. */
        val remainingToday: Int,
    )

    /**
     * [days] doit être trié du plus ancien au plus récent et se terminer par
     * [today]. Un objectif nul désactive complètement le mécanisme.
     */
    fun compute(days: List<DayStat>, goal: Int, today: LocalDate = LocalDate.now()): State {
        if (goal <= 0 || days.isEmpty()) return State(0, 0, false, 0)

        val byDay = days.associateBy { it.day }
        val todayKey = today.toString()
        val todaySteps = byDay[todayKey]?.steps ?: 0
        val todayDone = todaySteps >= goal

        // Série courante : on remonte le temps. Si aujourd'hui n'est pas encore
        // atteint, on démarre la veille — la journée reste jouable.
        var cursor = if (todayDone) today else today.minusDays(1)
        var current = 0
        while ((byDay[cursor.toString()]?.steps ?: 0) >= goal) {
            current++
            cursor = cursor.minusDays(1)
        }

        var best = 0
        var run = 0
        days.forEach { d ->
            if (d.steps >= goal) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }

        return State(
            current = current,
            best = maxOf(best, current),
            todayDone = todayDone,
            remainingToday = (goal - todaySteps).coerceAtLeast(0),
        )
    }
}

/**
 * Comparaison d'une période avec la précédente.
 *
 * La motivation la plus honnête ne demande personne d'autre : elle demande ce
 * que vous faisiez le mois dernier.
 */
data class PeriodComparison(
    val currentSteps: Long,
    val previousSteps: Long,
    val currentDistanceM: Double,
    val previousDistanceM: Double,
) {
    /** Variation des pas en pourcentage. null si la période précédente est vide. */
    val stepsDelta: Double?
        get() = if (previousSteps <= 0) null
        else (currentSteps - previousSteps) * 100.0 / previousSteps

    val distanceDelta: Double?
        get() = if (previousDistanceM <= 0) null
        else (currentDistanceM - previousDistanceM) * 100.0 / previousDistanceM
}
