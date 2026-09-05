package jp.rimtty.codematch.sdkprobe

/** Display-only, never logged or persisted. Reject control characters and oversized replies. */
internal fun probeValue(value: String?): String? = value?.takeIf {
    it.isNotBlank() && it.length <= 128 && it.all { char -> char.code in 32..126 }
}
