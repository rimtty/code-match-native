package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @Test
    fun defaultsAndUpdatesArePersistedAsOneSettingsFlow() {
        runBlocking {
            val file = temporaryFile()
            val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
            val repository = SettingsRepository(dataStore)

            assertEquals(
                AppSettings(),
                repository.settings.first(),
            )

            repository.setAutoAdvanceEnabled(true)
            repository.setAutoAdvanceDelay(AutoAdvanceDelay.FIVE_SECONDS)
            repository.setFeedbackVolume(0.35f)
            repository.setSuccessSound(SuccessSound.CHIME)
            repository.setFailureSound(FailureSound.DESCEND)
            repository.setLanguage(AppLanguage.ENGLISH)

            val updated = repository.settings.first()
            assertEquals(true, updated.autoAdvanceEnabled)
            assertEquals(5, updated.autoAdvanceDelaySeconds)
            assertEquals(0.35f, updated.feedbackVolume)
            assertEquals(SuccessSound.CHIME, updated.successSound)
            assertEquals(FailureSound.DESCEND, updated.failureSound)
            assertEquals(AppLanguage.ENGLISH, updated.language)

            file.delete()
        }
    }

    @Test
    fun volumeIsClampedAndInvalidDelayFallsBackToThreeSeconds() {
        runBlocking {
            val file = temporaryFile()
            val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
            val repository = SettingsRepository(dataStore)

            repository.setFeedbackVolume(-4.0f)
            assertEquals(0.0f, repository.settings.first().feedbackVolume)
            repository.setFeedbackVolume(4.0f)
            assertEquals(1.0f, repository.settings.first().feedbackVolume)
            repository.setFeedbackVolume(Float.NaN)
            assertEquals(1.0f, repository.settings.first().feedbackVolume)

            val delayKey = intPreferencesKey("autoAdvanceDelaySeconds")
            val volumeKey = floatPreferencesKey("feedbackVolume")
            dataStore.edit {
                it[delayKey] = 99
                it[volumeKey] = -0.5f
            }
            val invalid = repository.settings.first()
            assertEquals(3, invalid.autoAdvanceDelaySeconds)
            assertEquals(AutoAdvanceDelay.THREE_SECONDS, invalid.autoAdvanceDelay)
            assertEquals(0.0f, invalid.feedbackVolume)

            file.delete()
        }
    }

    private fun temporaryFile(): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
    }
}
