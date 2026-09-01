package jp.rimtty.codematch.core.model

/** Success feedback choices persisted by the settings repository. */
enum class SuccessSound(val storageValue: String) {
    SAMPLE_1("sample1"),
    SAMPLE_2("sample2"),
    POS_BEEP("posBeep"),
    DOUBLE_BEEP("doubleBeep"),
    CHIME("chime"),
    ;

    companion object {
        fun fromStorageValue(value: String?): SuccessSound =
            entries.firstOrNull { it.storageValue == value } ?: POS_BEEP
    }
}

/** Failure feedback choices persisted by the settings repository. */
enum class FailureSound(val storageValue: String) {
    FAIL_SAMPLE("failSample"),
    BUZZER("buzzer"),
    ALARM("alarm"),
    DESCEND("descend"),
    ;

    companion object {
        fun fromStorageValue(value: String?): FailureSound =
            entries.firstOrNull { it.storageValue == value } ?: ALARM
    }
}

/** App-local language preference; Japanese is the product default. */
enum class AppLanguage(val code: String) {
    JAPANESE("ja"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromCode(value: String?): AppLanguage =
            entries.firstOrNull { it.code == value } ?: JAPANESE
    }
}

/**
 * Preferences exposed to settings and scanner features.
 *
 * The data class is immutable so it can safely be emitted by DataStore as a
 * value in a Flow and passed across the UI boundary.
 */
data class AppSettings(
    val autoAdvanceEnabled: Boolean = false,
    val autoAdvanceDelaySeconds: Int = 3,
    val feedbackVolume: Float = 1.0f,
    val successSound: SuccessSound = SuccessSound.POS_BEEP,
    val failureSound: FailureSound = FailureSound.ALARM,
    val language: AppLanguage = AppLanguage.JAPANESE,
) {
    val autoAdvanceDelay: AutoAdvanceDelay
        get() = AutoAdvanceDelay.fromSeconds(autoAdvanceDelaySeconds)
            ?: AutoAdvanceDelay.THREE_SECONDS
}
