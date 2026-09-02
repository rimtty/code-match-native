package jp.rimtty.codematch.locale

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import jp.rimtty.codematch.core.data.SettingsRepository
import jp.rimtty.codematch.core.model.AppLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The language value exposed by the framework's per-app language settings.
 *
 * A null result deliberately means both "no explicit framework choice" and an
 * unsupported locale. The product supports only Japanese and English, so the
 * caller can safely fall back to its persisted value (which itself defaults
 * to Japanese).
 */
interface FrameworkAppLanguagePort {
    fun currentLanguage(): AppLanguage?

    fun applyLanguage(language: AppLanguage)
}

/** Small persistence seam that keeps the synchronizer independent of DataStore. */
interface AppLanguageStore {
    suspend fun currentLanguage(): AppLanguage

    suspend fun saveLanguage(language: AppLanguage)
}

/**
 * Reconciles the app-owned language preference with Android's per-app locale.
 *
 * An explicit supported framework locale represents a user choice made in the
 * OS and therefore updates the repository. An empty/unsupported framework
 * value lets the repository drive the initial locale. Both directions check
 * the current value before writing, which prevents locale recreation loops.
 */
class AppLanguageSynchronizer(
    private val store: AppLanguageStore,
    private val framework: FrameworkAppLanguagePort,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /**
     * Reconcile once during application startup and return the effective
     * language. The caller must be on the main thread for framework access;
     * only DataStore operations are dispatched to [ioDispatcher].
     */
    suspend fun synchronizeOnStartup(): AppLanguage {
        return mutex.withLock {
            // LocaleManager/AppCompatDelegate are main-thread framework APIs;
            // read the framework before switching to IO and apply it back on
            // the caller's main thread. This is important because
            // Application.onCreate invokes this method from main-thread
            // runBlocking during process startup.
            val frameworkLanguage = framework.currentLanguage()
            val stored = withContext(ioDispatcher) {
                store.currentLanguage()
            }
            val effective = if (frameworkLanguage != null) {
                if (stored != frameworkLanguage) {
                    withContext(ioDispatcher) {
                        store.saveLanguage(frameworkLanguage)
                    }
                }
                frameworkLanguage
            } else {
                stored
            }

            if (framework.currentLanguage() != effective) {
                framework.applyLanguage(effective)
            }
            effective
        }
    }

    /** Persist an in-app choice and mirror it to the framework exactly once. */
    suspend fun setLanguage(language: AppLanguage) {
        mutex.withLock {
            withContext(ioDispatcher) {
                if (store.currentLanguage() != language) {
                    store.saveLanguage(language)
                }
            }
            // Persist before requesting Activity recreation. This guarantees
            // that a framework-triggered cancellation cannot lose the user's
            // in-app selection.
            if (framework.currentLanguage() != language) {
                framework.applyLanguage(language)
            }
        }
    }
}

/** SettingsRepository adapter kept at the app boundary. */
class SettingsRepositoryAppLanguageStore(
    private val repository: SettingsRepository,
) : AppLanguageStore {
    override suspend fun currentLanguage(): AppLanguage =
        repository.settings.first().language

    override suspend fun saveLanguage(language: AppLanguage) {
        repository.setLanguage(language)
    }
}

/** Android framework adapter; no Android locale details escape the app layer. */
class AndroidFrameworkAppLanguagePort(
    private val context: Context,
) : FrameworkAppLanguagePort {
    override fun currentLanguage(): AppLanguage? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
        } else {
            AppCompatDelegate.getApplicationLocales()
                .takeIf { !it.isEmpty }
                ?.get(0)
        }
        return locale?.let { supportedLanguage(it.language) }
    }

    override fun applyLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val locales = LocaleList.forLanguageTags(language.code)
            if (manager.applicationLocales != locales) {
                manager.applicationLocales = locales
            }
        } else {
            val locales = LocaleListCompat.forLanguageTags(language.code)
            if (AppCompatDelegate.getApplicationLocales() != locales) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

    private fun supportedLanguage(language: String): AppLanguage? =
        AppLanguage.entries.firstOrNull { it.code == language }
}
