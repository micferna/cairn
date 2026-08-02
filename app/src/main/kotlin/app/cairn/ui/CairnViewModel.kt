package app.cairn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cairn.data.CairnRepository
import app.cairn.data.ModeTotals
import app.cairn.data.Settings
import app.cairn.domain.DayStat
import app.cairn.domain.LedgerEntry
import app.cairn.domain.Session
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
                )
            }
            _data.value = snap
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
