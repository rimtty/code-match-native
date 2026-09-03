package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonParser
import jp.rimtty.codematch.scanner.inateck.InateckStrictJsonValues.strictByteArray
import jp.rimtty.codematch.scanner.inateck.InateckStrictJsonValues.strictInteger

/**
 * Decodes the JSON returned by
 * {@code inateck_scanner_cmd_get_hid_output(1)} into an FF04 command.
 */
internal object InateckHidOutputCommandDecoder {
    private val REQUIRED_KEYS = setOf("status", "data")

    /** Returns the command only for an official success result (status 0). */
    fun decodeSdkOutput(json: String?): ByteArray? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(json) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        if (root.keySet() != REQUIRED_KEYS) return null
        val status = root["status"]?.strictInteger() ?: return null
        if (status != 0) return null
        val data = root["data"]?.strictByteArray() ?: return null
        // An empty successful result cannot be a write command.
        return data.takeUnless(ByteArray::isEmpty)?.clone()
    }

    /** Alias kept explicit for callers that do not want to mention SDK mode. */
    fun decode(json: String?): ByteArray? = decodeSdkOutput(json)
}

/** JNA-backed source for the output command, with JSON decoding at the seam. */
internal class InateckJnaHidOutputCommandProvider(
    private val api: InateckScannerCmdJna.Api = InateckScannerCmdJna.load(),
) {
    fun commandForSdkOutput(): ByteArray? = runCatching {
        InateckHidOutputCommandDecoder.decodeSdkOutput(
            InateckScannerCmdJna.hidOutputResult(api, SDK_OUTPUT_TYPE),
        )
    }.getOrNull()

    /** Uses the official response checker; raw response bytes never leave this seam. */
    fun isSuccessfulResponse(bytes: ByteArray): Boolean = runCatching {
        InateckScannerCmdJna.checkResult(api, bytes)
    }.getOrDefault(false)

    private companion object {
        const val SDK_OUTPUT_TYPE = 1
    }
}
