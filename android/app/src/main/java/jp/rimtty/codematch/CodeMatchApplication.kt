package jp.rimtty.codematch

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import jp.rimtty.codematch.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class CodeMatchApplication : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        val language = runBlocking(Dispatchers.IO) {
            settingsRepository.settings.first().language
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val requestedLocales = LocaleList.forLanguageTags(language.code)
            if (localeManager.applicationLocales != requestedLocales) {
                localeManager.applicationLocales = requestedLocales
            }
        } else {
            val requestedLocales = LocaleListCompat.forLanguageTags(language.code)
            if (AppCompatDelegate.getApplicationLocales() != requestedLocales) {
                AppCompatDelegate.setApplicationLocales(requestedLocales)
            }
        }
    }
}
