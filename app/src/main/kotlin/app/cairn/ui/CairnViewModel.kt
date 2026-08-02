package app.cairn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.graphics.toColorInt
import app.cairn.data.Archive
import app.cairn.data.CairnRepository
import app.cairn.data.ModeTotals
import app.cairn.data.Settings
import app.cairn.domain.DayStat
import app.cairn.domain.LedgerEntry
import app.cairn.domain.Mode
import app.cairn.domain.PeriodComparison
import app.cairn.domain.Session
import app.cairn.domain.Equivalences
import app.cairn.domain.ShareCard
import app.cairn.domain.Streak
import app.cairn.sensing.PassiveSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val WEEK_DAYS = 7
private const val RECENT_SESSIONS = 30
private const val SHAPE_GALLERY_SIZE = 40
private const val LEDGER_PAGE = 150

/**
 * État agrégé de l'application. Une seule source, lue depuis SQLite et
 * recalculée à chaque mutation. Il n'y a pas de cache réseau ni de
 * synchronisation à orchestrer : c'est un des bénéfices collatéraux du
 * « tout local ».
 */
class CairnViewModel(app: Application) : AndroidViewModel(app) {

    val repo = CairnRepository.get(app)
    val settings = Settings.get(app)
    private val passiveSteps = PassiveSteps(app)

    val hasPedometer: Boolean get() = passiveSteps.isAvailable

    private val _pedometerInert = MutableStateFlow(false)

    /**
     * Le podomètre de cet appareil ne compte pas hors abonnement.
     *
     * Certains SoC d'entrée de gamme ne respectent pas la spécification : mieux
     * vaut le dire que laisser croire à une panne de l'application.
     */
    val pedometerInert: StateFlow<Boolean> = _pedometerInert

    data class Snapshot(
        val today: DayStat = DayStat(LocalDate.now().toString(), 0, 0.0, 0.0, 0.0, 0),
        val week: List<DayStat> = emptyList(),
        val year: List<ModeTotals> = emptyList(),
        val allTime: List<ModeTotals> = emptyList(),
        val totalDistanceM: Double = 0.0,
        val totalAscentM: Double = 0.0,
        val totalSteps: Long = 0,
        val sessionCount: Long = 0,
        val recent: List<Session> = emptyList(),
        val shapes: List<Session> = emptyList(),
        val ledger: List<LedgerEntry> = emptyList(),
        val ledgerCount: Long = 0,
        val bytesWritten: Long = 0,
        val dbBytes: Long = 0,
        val streak: Streak.State = Streak.State(0, 0, false, 0),
        val month: PeriodComparison = PeriodComparison(0, 0, 0.0, 0.0),
    )

    private val _data = MutableStateFlow(Snapshot())
    val data: StateFlow<Snapshot> = _data

    init {
        viewModelScope.launch {
            combine(repo.revision, repo.ledger.revision) { a, b -> a + b }
                .collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val snap = withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                val jan1 = LocalDate.of(today.year, 1, 1).toString()
                Snapshot(
                    today = repo.dayStat(today.toString()),
                    week = repo.lastDays(WEEK_DAYS, today),
                    year = repo.totalsByMode(jan1),
                    allTime = repo.totalsByMode(null),
                    totalDistanceM = repo.totalDistanceM(),
                    totalAscentM = repo.totalAscentM(),
                    totalSteps = repo.totalSteps(),
                    sessionCount = repo.sessionCount(),
                    recent = repo.recentSessions(RECENT_SESSIONS),
                    shapes = repo.sessionsWithShapes(SHAPE_GALLERY_SIZE),
                    ledger = repo.ledger.recent(LEDGER_PAGE),
                    ledgerCount = repo.ledger.count(),
                    bytesWritten = repo.ledger.totalBytesWritten(),
                    dbBytes = dbSizeBytes(),
                    streak = Streak.compute(repo.allDays(), settings.state.value.dailyGoal, today),
                    month = monthComparison(today),
                )
            }
            _data.value = snap
        }
    }

    /**
     * Le mois en cours face au précédent, sur la même portion de mois.
     *
     * Comparer un 3 du mois à un mois complet n'aurait aucun sens et
     * découragerait pour rien : on ne retient du mois passé que ses N premiers
     * jours, N étant le jour où l'on se trouve.
     */
    private fun monthComparison(today: LocalDate): PeriodComparison {
        val startThis = today.withDayOfMonth(1)
        val startPrev = startThis.minusMonths(1)
        val dayOfMonth = today.dayOfMonth
        val endPrev = startPrev.withDayOfMonth(
            minOf(dayOfMonth, startPrev.lengthOfMonth())
        )
        val (curSteps, curDist) = repo.rangeTotals(startThis.toString(), today.toString())
        val (prevSteps, prevDist) = repo.rangeTotals(startPrev.toString(), endPrev.toString())
        return PeriodComparison(curSteps, prevSteps, curDist, prevDist)
    }

    /**
     * Relève le podomètre matériel et rattrape les pas faits pendant que
     * l'application ne tournait pas. Appelé à chaque retour au premier plan.
     */
    fun syncPassiveSteps() {
        if (!settings.state.value.passiveSteps) return
        viewModelScope.launch {
            val stride = settings.state.value.strideM.toDouble()
            val reading = withContext(Dispatchers.IO) { passiveSteps.sync(repo, stride) }
            _pedometerInert.value = reading?.sensorLooksInert ?: false
            reload()
            // Le widget affiche les pas du jour : il doit suivre le relevé.
            withContext(Dispatchers.IO) {
                app.cairn.widget.CairnWidget.refresh(getApplication())
            }
        }
    }

    fun importJson(json: String, onDone: (Archive.ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repo.importJson(json) }
            onDone(result)
        }
    }

    fun correctMode(sessionId: Long, mode: Mode) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.correctMode(sessionId, mode) } }
    }

    // -------------------------------------------------------------- partage

    /**
     * Carte de la journée : le chiffre, l'équivalence, et la plus belle forme
     * du jour si elle existe.
     */
    fun shareToday(context: android.content.Context, data: Snapshot) {
        val (value, unit) = Fmt.distance(data.today.distanceM)
        val shape = data.shapes.firstOrNull { it.day == data.today.day }?.shape
        renderAndShare(
            context,
            ShareCard.Content(
                headline = value,
                unit = unit,
                caption = "parcourus aujourd'hui",
                equivalence = Equivalences.forDistance(data.today.distanceM)?.text,
                stats = listOfNotNull(
                    Fmt.int(data.today.steps) to "pas",
                    (Fmt.int(data.today.ascentM.toInt()) to "m de D+")
                        .takeIf { data.today.ascentM >= 1 },
                    ("${data.streak.current} j" to "de série").takeIf { data.streak.current > 0 },
                ),
                shape = shape,
            ),
            subject = "$value $unit aujourd'hui",
        )
    }

    /** Carte d'un parcours : la forme est la vedette. */
    fun shareShape(context: android.content.Context, session: Session) {
        renderAndShare(
            context,
            ShareCard.Content(
                headline = Fmt.km(session.distanceM),
                unit = "km",
                caption = "${session.mode.label.lowercase()} · ${Fmt.dayLabel(session.day)}",
                equivalence = Equivalences.forDistance(session.distanceM)?.text,
                stats = listOfNotNull(
                    Fmt.duration(session.durationS) to "durée",
                    (Fmt.int(session.ascentM.toInt()) to "m de D+").takeIf { session.ascentM >= 1 },
                    ("${Fmt.speedKmh(session.maxSpeedMs)}" to "km/h max")
                        .takeIf { session.maxSpeedMs > 0 },
                ),
                shape = session.shape,
                accent = session.mode.hex().toColorInt(),
            ),
            subject = "${Fmt.km(session.distanceM)} km en ${session.mode.label.lowercase()}",
        )
    }

    private fun renderAndShare(
        context: android.content.Context,
        content: ShareCard.Content,
        subject: String,
    ) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { ShareCard.render(content) }
            Sharing.shareCard(context, bitmap, subject)
        }
    }

    /** Taille réelle du fichier de base. Affichée telle quelle dans l'écran Confidentialité. */
    private fun dbSizeBytes(): Long = runCatching {
        getApplication<Application>().getDatabasePath(app.cairn.data.CairnDb.NAME).length()
    }.getOrDefault(0L)

    fun export(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { repo.exportJson() }
            onReady(json)
        }
    }

    fun wipe(includingLedger: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (includingLedger) repo.wipeEverythingIncludingLedger() else repo.wipeAll()
            }
        }
    }
}
