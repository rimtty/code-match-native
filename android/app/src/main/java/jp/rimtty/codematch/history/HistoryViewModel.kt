package jp.rimtty.codematch.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.MatchSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryAppState(
    val sessions: List<MatchSession> = emptyList(),
    val language: AppLanguage = AppLanguage.JAPANESE,
    /** False only for the short stateIn window before Room emits its first value. */
    val loaded: Boolean = false,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<HistoryAppState> = combine(
        historyRepository.sessions,
        settingsRepository.settings,
    ) { sessions, settings ->
        HistoryAppState(
            sessions = sessions,
            language = settings.language,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryAppState(),
    )

    fun renameSession(sessionId: String, name: String?) {
        viewModelScope.launch { historyRepository.renameSession(sessionId, name) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { historyRepository.deleteSession(sessionId) }
    }
}
