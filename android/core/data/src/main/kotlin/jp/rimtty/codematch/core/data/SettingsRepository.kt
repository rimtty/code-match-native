package jp.rimtty.codematch.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import jp.rimtty.codematch.core.model.AppLanguage
import jp.rimtty.codematch.core.model.AppSettings
import jp.rimtty.codematch.core.model.AutoAdvanceDelay
import jp.rimtty.codematch.core.model.FailureSound
import jp.rimtty.codematch.core.model.SuccessSound
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Preferences DataStore facade for the small set of app settings.
 *
 * History belongs in Room because it needs relations and cascade deletion;
 * these scalar settings remain in Preferences DataStore and are exposed as one
 * immutable [AppSettings] value for the UI.
 */
class SettingsRepository {
    private val dataStore: DataStore<Preferences>

    constructor(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
    }

    constructor(context: Context) : this(context.codeMatchSettingsDataStore)

    /** Current settings, with defaults used when a key is absent or invalid. */
    val settings: Flow<AppSettings>
        get() = dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { preferences -> preferences.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val updated = transform(preferences.toAppSettings())
            preferences[Keys.AUTO_ADVANCE_ENABLED] = updated.autoAdvanceEnabled
            preferences[Keys.AUTO_ADVANCE_DELAY_SECONDS] =
                updated.autoAdvanceDelay.seconds
            preferences[Keys.FEEDBACK_VOLUME] = updated.feedbackVolume.clampedVolume()
            preferences[Keys.SUCCESS_SOUND] = updated.successSound.storageValue
            preferences[Keys.FAILURE_SOUND] = updated.failureSound.storageValue
            preferences[Keys.LANGUAGE] = updated.language.code
        }
    }

    suspend fun setAutoAdvanceEnabled(enabled: Boolean) =
        update { it.copy(autoAdvanceEnabled = enabled) }

    suspend fun setAutoAdvanceDelay(delay: AutoAdvanceDelay) =
        update { it.copy(autoAdvanceDelaySeconds = delay.seconds) }

    suspend fun setFeedbackVolume(volume: Float) =
        update { it.copy(feedbackVolume = volume.clampedVolume()) }

    suspend fun setSuccessSound(sound: SuccessSound) =
        update { it.copy(successSound = sound) }

    suspend fun setFailureSound(sound: FailureSound) =
        update { it.copy(failureSound = sound) }

    suspend fun setLanguage(language: AppLanguage) =
        update { it.copy(language = language) }

    private object Keys {
        val AUTO_ADVANCE_ENABLED = booleanPreferencesKey("autoAdvanceOnMatch")
        val AUTO_ADVANCE_DELAY_SECONDS =
            androidx.datastore.preferences.core.intPreferencesKey("autoAdvanceDelaySeconds")
        val FEEDBACK_VOLUME = floatPreferencesKey("feedbackVolume")
        val SUCCESS_SOUND = stringPreferencesKey("successSound")
        val FAILURE_SOUND = stringPreferencesKey("failureSound")
        val LANGUAGE = stringPreferencesKey("language")
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val default = AppSettings()
        return AppSettings(
            autoAdvanceEnabled = this[Keys.AUTO_ADVANCE_ENABLED]
                ?: default.autoAdvanceEnabled,
            autoAdvanceDelaySeconds = AutoAdvanceDelay.fromSeconds(
                this[Keys.AUTO_ADVANCE_DELAY_SECONDS] ?: default.autoAdvanceDelaySeconds
            )?.seconds ?: default.autoAdvanceDelaySeconds,
            feedbackVolume = (this[Keys.FEEDBACK_VOLUME] ?: default.feedbackVolume)
                .clampedVolume(),
            successSound = SuccessSound.fromStorageValue(this[Keys.SUCCESS_SOUND]),
            failureSound = FailureSound.fromStorageValue(this[Keys.FAILURE_SOUND]),
            language = AppLanguage.fromCode(this[Keys.LANGUAGE]),
        )
    }

    private fun Float.clampedVolume(): Float =
        if (isNaN()) 1.0f else coerceIn(0.0f, 1.0f)
}
