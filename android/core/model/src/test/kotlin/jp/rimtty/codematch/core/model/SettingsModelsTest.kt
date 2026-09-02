package jp.rimtty.codematch.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModelsTest {
    @Test
    fun defaultsMatchProductContract() {
        val settings = AppSettings()

        assertEquals(false, settings.autoAdvanceEnabled)
        assertEquals(3, settings.autoAdvanceDelaySeconds)
        assertEquals(1.0f, settings.feedbackVolume)
        assertEquals(SuccessSound.POS_BEEP, settings.successSound)
        assertEquals(FailureSound.ALARM, settings.failureSound)
        assertEquals(AppLanguage.JAPANESE, settings.language)
    }

    @Test
    fun invalidDelayFallsBackToThreeSeconds() {
        assertEquals(AutoAdvanceDelay.THREE_SECONDS, AppSettings(autoAdvanceDelaySeconds = 2).autoAdvanceDelay)
        assertEquals(3, AppSettings(autoAdvanceDelaySeconds = 2).autoAdvanceDelay.seconds)
    }

    @Test
    fun unknownPreferenceValuesUseSafeDefaults() {
        assertEquals(SuccessSound.POS_BEEP, SuccessSound.fromStorageValue("future"))
        assertEquals(FailureSound.ALARM, FailureSound.fromStorageValue("future"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromCode("future"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromCode(null))
    }
}
