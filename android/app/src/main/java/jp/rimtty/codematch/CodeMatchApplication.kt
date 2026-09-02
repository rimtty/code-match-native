package jp.rimtty.codematch

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import jp.rimtty.codematch.locale.AppLanguageSynchronizer
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class CodeMatchApplication : Application() {
    @Inject
    lateinit var appLanguageSynchronizer: AppLanguageSynchronizer

    override fun onCreate() {
        super.onCreate()
        runBlocking { appLanguageSynchronizer.synchronizeOnStartup() }
    }
}
