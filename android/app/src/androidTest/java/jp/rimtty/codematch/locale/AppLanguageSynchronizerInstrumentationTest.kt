package jp.rimtty.codematch.locale

import android.app.Instrumentation
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.rimtty.codematch.core.model.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android 13+ per-app locale API without touching the
 * product DataStore. The in-memory store models the app-owned preference, and
 * the package locale is restored even when an assertion fails.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageSynchronizerInstrumentationTest {
    @Test
    fun frameworkAndAppLanguageRoundTripDoesNotCreateARecreationLoop() {
        assumeTrue(
            "Per-app locales require Android 13+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val localeManager = requireNotNull(targetContext.getSystemService(LocaleManager::class.java))
        val originalLocales = localeManager.applicationLocales

        try {
            runOnMain(instrumentation) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(AppLanguage.JAPANESE.code)
            }

            val store = RecordingStore(AppLanguage.JAPANESE)
            val framework = CountingFramework(AndroidFrameworkAppLanguagePort(targetContext))
            val synchronizer = AppLanguageSynchronizer(
                store = store,
                framework = framework,
                ioDispatcher = Dispatchers.Unconfined,
            )

            runOnMain(instrumentation) {
                runBlocking {
                    assertEquals(AppLanguage.JAPANESE, synchronizer.synchronizeOnStartup())
                }
            }
            assertTrue(store.saved.isEmpty())
            assertEquals(0, framework.applyCount)

            // Simulate an Android Settings per-app language change. The
            // synchronizer must import it into the app preference and must not
            // write the same locale back to the framework.
            runOnMain(instrumentation) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(AppLanguage.ENGLISH.code)
            }
            runOnMain(instrumentation) {
                runBlocking {
                    assertEquals(AppLanguage.ENGLISH, synchronizer.synchronizeOnStartup())
                }
            }
            assertEquals(listOf(AppLanguage.ENGLISH), store.saved)
            assertEquals(0, framework.applyCount)

            // A second startup pass represents Activity/Application resume
            // after the framework-triggered recreation and must be a no-op.
            runOnMain(instrumentation) {
                runBlocking {
                    assertEquals(AppLanguage.ENGLISH, synchronizer.synchronizeOnStartup())
                }
            }
            assertEquals(listOf(AppLanguage.ENGLISH), store.saved)
            assertEquals(0, framework.applyCount)

            var effectiveAfterInAppChange: AppLanguage? = null
            runOnMain(instrumentation) {
                runBlocking {
                    synchronizer.setLanguage(AppLanguage.JAPANESE)
                    effectiveAfterInAppChange = framework.currentLanguage()
                }
            }
            assertEquals(AppLanguage.JAPANESE, effectiveAfterInAppChange)
            assertEquals(
                listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE),
                store.saved,
            )
            assertEquals(1, framework.applyCount)

            runOnMain(instrumentation) {
                runBlocking {
                    assertEquals(AppLanguage.JAPANESE, synchronizer.synchronizeOnStartup())
                }
            }
            assertEquals(1, framework.applyCount)
        } finally {
            runOnMain(instrumentation) {
                if (localeManager.applicationLocales != originalLocales) {
                    localeManager.applicationLocales = originalLocales
                }
            }
        }
    }

    private fun runOnMain(
        instrumentation: Instrumentation,
        block: () -> Unit,
    ) {
        instrumentation.runOnMainSync(block)
    }

    private class RecordingStore(
        private var language: AppLanguage,
    ) : AppLanguageStore {
        val saved = mutableListOf<AppLanguage>()

        override suspend fun currentLanguage(): AppLanguage = language

        override suspend fun saveLanguage(language: AppLanguage) {
            this.language = language
            saved += language
        }
    }

    private class CountingFramework(
        private val delegate: FrameworkAppLanguagePort,
    ) : FrameworkAppLanguagePort {
        var applyCount = 0

        override fun currentLanguage(): AppLanguage? = delegate.currentLanguage()

        override fun applyLanguage(language: AppLanguage) {
            applyCount += 1
            delegate.applyLanguage(language)
        }
    }
}
