package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.ble.BleScanCallbackDecoder
import jp.rimtty.codematch.scanner.ble.BleScanPayloadDecoder

/**
 * Fail-closed decoder for FF01 data observed while no SDK command is active.
 *
 * Plain scanner text and the two known scan envelopes are accepted. Any other
 * JSON is treated as delayed or unknown protocol traffic and discarded.
 */
internal object InateckScanCallbackDecoder : BleScanCallbackDecoder {
    override fun decode(callbackValue: String): String? {
        val candidate = BleScanPayloadDecoder.normalizeTransportTerminators(callbackValue)
        if (candidate.isEmpty()) return null
        val first = candidate.firstOrNull { !it.isWhitespace() }
        val parsed = runCatching { JsonParser.parseString(candidate) }.getOrNull()
        if (parsed == null) {
            return if (first == '{' || first == '[') null else candidate.asSupportedScan()
        }
        if (!parsed.isJsonObject) {
            return if (first == '{' || first == '[') null else candidate.asSupportedScan()
        }
        val root = parsed.asJsonObject
        if (!root.has("source_code") && !root.has("notify_type")) return null
        return BleScanPayloadDecoder.decode(candidate)?.asSupportedScan()
    }

    /**
     * BCST-36 emits a one-character control acknowledgement after a settings
     * transaction has completed. The SDK can deliver it after its task has
     * already become idle, so it shares FF01 with real scans. Neither of this
     * app's accepted business formats can be a single character; discard that
     * command residue before it reaches scan validation or feedback.
     */
    private fun String.asSupportedScan(): String? = takeUnless { it.length == 1 }
}
