package jp.rimtty.codematch.scan

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import jp.rimtty.codematch.core.data.CodeMatchDatabase
import jp.rimtty.codematch.core.data.CodeMatchDatabaseFactory
import jp.rimtty.codematch.core.data.HistoryRepository
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.MatchSession
import jp.rimtty.codematch.feedback.FeedbackPlayer
import jp.rimtty.codematch.feature.scan.ScanPhase
import jp.rimtty.codematch.feature.scan.ScanUiAction
import jp.rimtty.codematch.feature.scan.ScanUiState
import jp.rimtty.codematch.scanner.fake.FakeExternalScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for name edits racing the asynchronous ViewModel
 * repository initialization. The gated DataStore keeps initialization paused
 * while the test queues real public UI actions, so the old overwrite is
 * reproduced deterministically without a timing sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelSessionNameTest {
    @Test
    fun preInitializationStartUsesNewestTrimmedName() = runTestWithMain {
        val fixture = Fixture(this)
        try {
            val viewModel = fixture.createViewModel()
            viewModel.onAction(ScanUiAction.SessionNameChanged("  first  "))
            viewModel.onAction(ScanUiAction.SessionNameChanged("  newest  "))
            viewModel.onAction(ScanUiAction.StartSession)
            runCurrent()

            // The repository gate is still closed, but typing remains visible
            // immediately and StartSession is waiting behind initialization.
            assertEquals("  newest  ", viewModel.state.value.sessionNameDraft)
            fixture.releaseInitialization()

            fixture.awaitStarted(viewModel)
            val active = fixture.awaitActiveSession()
            assertEquals("newest", active.name)
            assertEquals(1, fixture.history.sessions.first().size)
            assertEquals("newest", viewModel.state.value.sessionName)
            assertEquals(ScanPhase.WAITING_QR, viewModel.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preInitializationEmptyEditWinsOverOlderName() = runTestWithMain {
        val fixture = Fixture(this)
        try {
            val viewModel = fixture.createViewModel()
            viewModel.onAction(ScanUiAction.SessionNameChanged("  first  "))
            viewModel.onAction(ScanUiAction.SessionNameChanged("   "))
            viewModel.onAction(ScanUiAction.StartSession)
            runCurrent()

            assertEquals("   ", viewModel.state.value.sessionNameDraft)
            fixture.releaseInitialization()

            fixture.awaitStarted(viewModel)
            val active = fixture.awaitActiveSession()
            assertNull(active.name)
            assertEquals(1, fixture.history.sessions.first().size)
            assertNull(viewModel.state.value.sessionName)
            assertEquals(ScanPhase.WAITING_QR, viewModel.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun existingActiveSessionRestoresNameAndRepeatedStartDoesNotDuplicate() = runTestWithMain {
        val fixture = Fixture(this)
        try {
            val restoredId = fixture.history.beginSession(name = "restored")
            val viewModel = fixture.createViewModel()
            viewModel.onAction(ScanUiAction.SessionNameChanged(" stale edit "))
            viewModel.onAction(ScanUiAction.StartSession)
            viewModel.onAction(ScanUiAction.StartSession)
            runCurrent()

            fixture.releaseInitialization()

            fixture.awaitStarted(viewModel)
            val active = fixture.awaitActiveSession()
            assertEquals(restoredId, active.id)
            assertEquals("restored", active.name)
            assertEquals(1, fixture.history.sessions.first().size)
            assertEquals(restoredId, fixture.history.activeSession.first()?.id)
            assertEquals("restored", viewModel.state.value.sessionName)
            assertEquals(ScanPhase.WAITING_QR, viewModel.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    private fun runTestWithMain(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private class Fixture(private val testScope: TestScope) {
        private val context: Context = ApplicationProvider.getApplicationContext()
        private val initializationGate = CompletableDeferred<Unit>()
        private val dataStore = GatedPreferencesDataStore(initializationGate)
        private val database: CodeMatchDatabase = CodeMatchDatabaseFactory.inMemory(context)
        val history = HistoryRepository(database)
        private val settings = SettingsRepository(dataStore)
        private val scanner = FakeExternalScanner()
        private val owner = TestViewModelOwner()
        private var viewModel: ScanViewModel? = null

        fun createViewModel(): ScanViewModel {
            testScope.launch(Dispatchers.Main) {
                viewModel = ViewModelProvider(owner, Factory())[ScanViewModel::class.java]
            }
            testScope.runCurrent()
            return requireNotNull(viewModel)
        }

        fun releaseInitialization() {
            check(initializationGate.complete(Unit)) { "initialization was already released" }
            testScope.advanceUntilIdle()
        }

        suspend fun awaitActiveSession(): MatchSession {
            return withContext(Dispatchers.IO) {
                withTimeout(5_000L) {
                    history.activeSession.first { session -> session != null }
                } ?: error("active session disappeared")
            }
        }

        suspend fun awaitStarted(viewModel: ScanViewModel): ScanUiState =
            withContext(Dispatchers.IO) {
                withTimeout(5_000L) {
                    viewModel.state.first { state ->
                        state.sessionActive && state.phase == ScanPhase.WAITING_QR
                    }
                }
            }

        fun close() {
            testScope.launch(Dispatchers.Main) { owner.viewModelStore.clear() }
            testScope.runCurrent()
            database.close()
        }

        private inner class Factory : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ScanViewModel::class.java)
                return ScanViewModel(
                    historyRepository = history,
                    settingsRepository = settings,
                    scanner = scanner,
                    feedbackPlayer = FeedbackPlayer(context),
                ) as T
            }
        }

        private class TestViewModelOwner : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }

    private class GatedPreferencesDataStore(
        private val gate: CompletableDeferred<Unit>,
    ) : DataStore<Preferences> {
        private val current = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = flow {
            gate.await()
            emitAll(current)
        }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(current.value)
            current.value = updated
            return updated
        }
    }
}
