package jp.rimtty.codematch.locale

import jp.rimtty.codematch.core.model.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageSynchronizerTest {
    @Test
    fun emptyFrameworkUsesStoredLanguageAndAppliesIt() = runBlocking {
        val store = FakeStore(AppLanguage.ENGLISH)
        val framework = FakeFramework(null)

        val effective = synchronizer(store, framework).synchronizeOnStartup()

        assertEquals(AppLanguage.ENGLISH, effective)
        assertEquals(AppLanguage.ENGLISH, framework.currentLanguage())
        assertEquals(listOf(AppLanguage.ENGLISH), framework.applied)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun supportedFrameworkChoiceUpdatesFreshRepositoryWithoutWritingBack() = runBlocking {
        val store = FakeStore(AppLanguage.JAPANESE)
        val framework = FakeFramework(AppLanguage.ENGLISH)

        val effective = synchronizer(store, framework).synchronizeOnStartup()

        assertEquals(AppLanguage.ENGLISH, effective)
        assertEquals(AppLanguage.ENGLISH, store.currentLanguage())
        assertEquals(listOf(AppLanguage.ENGLISH), store.saved)
        assertTrue(framework.applied.isEmpty())
    }

    @Test
    fun matchingValuesDoNotCauseARecreationLoop() = runBlocking {
        val store = FakeStore(AppLanguage.ENGLISH)
        val framework = FakeFramework(AppLanguage.ENGLISH)

        synchronizer(store, framework).synchronizeOnStartup()
        synchronizer(store, framework).synchronizeOnStartup()

        assertTrue(store.saved.isEmpty())
        assertTrue(framework.applied.isEmpty())
    }

    @Test
    fun inAppChangePersistsAndAppliesOnce() = runBlocking {
        val store = FakeStore(AppLanguage.JAPANESE)
        val framework = FakeFramework(AppLanguage.JAPANESE)
        val synchronizer = synchronizer(store, framework)

        synchronizer.setLanguage(AppLanguage.ENGLISH)
        synchronizer.setLanguage(AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, store.currentLanguage())
        assertEquals(listOf(AppLanguage.ENGLISH), store.saved)
        assertEquals(listOf(AppLanguage.ENGLISH), framework.applied)
    }

    @Test
    fun aNewSynchronizerSeesAnExternalFrameworkChange() = runBlocking {
        val store = FakeStore(AppLanguage.JAPANESE)
        val firstFramework = FakeFramework(null)
        synchronizer(store, firstFramework).synchronizeOnStartup()
        assertEquals(listOf(AppLanguage.JAPANESE), firstFramework.applied)

        // A new framework instance represents the Activity/Application after
        // an OS per-app language change. The store is intentionally reused to
        // model the persisted DataStore file.
        val secondFramework = FakeFramework(AppLanguage.ENGLISH)
        val secondStore = FakeStore(store.currentLanguage())
        val second = synchronizer(secondStore, secondFramework)

        assertEquals(AppLanguage.ENGLISH, second.synchronizeOnStartup())
        assertEquals(AppLanguage.ENGLISH, secondStore.currentLanguage())
        assertEquals(listOf(AppLanguage.ENGLISH), secondStore.saved)
        assertTrue(secondFramework.applied.isEmpty())
    }

    @Test
    fun startupCompletesFromSingleThreadRunBlockingCaller() = runBlocking {
        val store = FakeStore(AppLanguage.ENGLISH)
        val framework = FakeFramework(null)
        val synchronizer = AppLanguageSynchronizer(
            store = store,
            framework = framework,
            ioDispatcher = Dispatchers.IO,
        )

        withTimeout(5_000) {
            assertEquals(AppLanguage.ENGLISH, synchronizer.synchronizeOnStartup())
        }
        assertEquals(listOf(AppLanguage.ENGLISH), framework.applied)
    }

    private fun synchronizer(
        store: FakeStore,
        framework: FakeFramework,
    ): AppLanguageSynchronizer = AppLanguageSynchronizer(
        store = store,
        framework = framework,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class FakeStore(
        private var language: AppLanguage,
    ) : AppLanguageStore {
        val saved = mutableListOf<AppLanguage>()

        override suspend fun currentLanguage(): AppLanguage = language

        override suspend fun saveLanguage(language: AppLanguage) {
            this.language = language
            saved += language
        }
    }

    private class FakeFramework(
        private var language: AppLanguage?,
    ) : FrameworkAppLanguagePort {
        val applied = mutableListOf<AppLanguage>()

        override fun currentLanguage(): AppLanguage? = language

        override fun applyLanguage(language: AppLanguage) {
            this.language = language
            applied += language
        }
    }
}
