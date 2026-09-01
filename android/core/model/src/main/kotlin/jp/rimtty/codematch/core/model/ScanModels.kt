package jp.rimtty.codematch.core.model

/**
 * The delays offered by the automatic next-match action.
 *
 * The value is deliberately represented in seconds so it can be stored by any
 * platform preferences implementation without making this model depend on
 * Android's DataStore (or on iOS' UserDefaults).
 */
enum class AutoAdvanceDelay(val seconds: Int) {
    ONE_SECOND(1),
    THREE_SECONDS(3),
    FIVE_SECONDS(5),
    ;

    companion object {
        fun fromSeconds(value: Int): AutoAdvanceDelay? =
            entries.firstOrNull { it.seconds == value }
    }
}

/** Keys and defaults shared by the settings and scanning features. */
object AutoAdvanceSettings {
    const val ENABLED_KEY: String = "autoAdvanceOnMatch"
    const val DELAY_SECONDS_KEY: String = "autoAdvanceDelaySeconds"
    const val DEFAULT_ENABLED: Boolean = false
    val DEFAULT_DELAY: AutoAdvanceDelay = AutoAdvanceDelay.THREE_SECONDS
}

/** The outcome shown after QR and Code 128 values are compared. */
enum class MatchResult {
    MATCH,
    MISMATCH,
}

/** The input type expected by the current scan step. */
enum class ExpectedCode {
    QR,
    BARCODE,
    ;

    val isQr: Boolean
        get() = this == QR
}

/**
 * The three logical stages of one comparison.
 *
 * This type intentionally contains no camera/Bluetooth state. Input adapters
 * can reject values that do not correspond to the active input type before
 * creating the next state.
 */
sealed class ScanStep {
    abstract val progress: Int

    data object QR : ScanStep() {
        override val progress: Int = 1
    }

    data object BARCODE : ScanStep() {
        override val progress: Int = 2
    }

    data class Result(val result: MatchResult) : ScanStep() {
        override val progress: Int = 3
    }
}
