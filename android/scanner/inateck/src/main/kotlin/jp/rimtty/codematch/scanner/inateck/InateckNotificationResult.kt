package jp.rimtty.codematch.scanner.inateck

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Decoded JSON returned by inateck_scanner_cmd_notify_data_result. */
internal data class InateckNotificationResult(
    val notifyType: Int,
    val notifyStatus: Int,
    val notifyData: ByteArray,
)

/**
 * Strict, fail-closed decoder for the official notification result schema.
 *
 * The parser deliberately accepts numeric JSON values only.  Strings such as
 * {@code "0"}, fractional values, negative bytes, and unknown fields are not
 * valid protocol results and must never reach the scan domain.
 */
internal object InateckNotificationResultDecoder {
    private val REQUIRED_KEYS = setOf("notify_type", "notify_status", "notify_data")

    fun decode(json: String?): InateckNotificationResult? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(json) }
            .getOrNull()
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: return null
        if (root.keySet() != REQUIRED_KEYS) return null

        val notifyType = with(InateckStrictJsonValues) {
            root["notify_type"]?.strictInteger()
        } ?: return null
        if (notifyType !in 0..1) return null
        val notifyStatus = with(InateckStrictJsonValues) {
            root["notify_status"]?.strictInteger()
        } ?: return null
        if (notifyStatus !in 0..2) return null
        val notifyData = with(InateckStrictJsonValues) {
            root["notify_data"]?.strictByteArray()
        } ?: return null
        return InateckNotificationResult(
            notifyType = notifyType,
            notifyStatus = notifyStatus,
            notifyData = notifyData,
        )
    }
}

/** Shared strict numeric/byte conversion for native command JSON decoders. */
internal object InateckStrictJsonValues {
    private val INTEGER_LITERAL = Regex("-?(?:0|[1-9][0-9]*)")

    fun JsonElement.strictInteger(): Int? {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        val literal = asJsonPrimitive.asString
        if (!INTEGER_LITERAL.matches(literal)) return null
        return literal.toLongOrNull()
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
    }

    fun JsonElement.strictByteArray(): ByteArray? {
        if (!isJsonArray) return null
        val values = ArrayList<Int>(asJsonArray.size())
        for (element in asJsonArray) {
            val value = element.strictInteger() ?: return null
            if (value !in 0..255) return null
            values += value
        }
        return ByteArray(values.size) { index -> values[index].toByte() }
    }
}
