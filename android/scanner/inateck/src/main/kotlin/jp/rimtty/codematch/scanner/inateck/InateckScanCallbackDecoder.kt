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
            return if (first == '{' || first == '[') null else candidate
        }
        if (!parsed.isJsonObject) {
            return if (first == '{' || first == '[') null else candidate
        }
        val root = parsed.asJsonObject
        if (!root.has("source_code") && !root.has("notify_type")) return null
        return BleScanPayloadDecoder.decode(candidate)
    }
}
