package jp.rimtty.codematch.scanner.ble

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload

/**
 * Unwraps the callback envelopes observed from the iOS scanner integration.
 *
 * This class returns only the normalized scan text. It has no diagnostic
 * dependency and therefore cannot leak payloads into the BLE diagnostic log.
 * A non-envelope JSON object is treated as direct text for compatibility with
 * scanner SDK revisions that already unwrap the value.
 */
object BleScanPayloadDecoder {
    fun decode(callbackValue: String): String? {
        val candidate = normalizeTransportTerminators(callbackValue)
        if (candidate.isEmpty()) return null
        val parsed = runCatching { JsonParser.parseString(candidate) }.getOrNull()
        if (parsed == null) {
            // A malformed structural payload is not safe to treat as a scan.
            // Plain text remains supported for adapters that already unwrap
            // the callback before it reaches this decoder.
            val firstNonWhitespace = candidate.firstOrNull { !it.isWhitespace() }
            return if (firstNonWhitespace == '{' || firstNonWhitespace == '[') null else candidate
        }
        if (!parsed.isJsonObject) {
            // Gson accepts some unquoted values as lenient primitives. Keep
            // those legacy direct strings, while a JSON-looking array or
            // object that could not be decoded above remains rejected.
            val firstNonWhitespace = candidate.firstOrNull { !it.isWhitespace() }
            return if (firstNonWhitespace == '[' || firstNonWhitespace == '{') {
                null
            } else {
                candidate
            }
        }
        val root = parsed.asJsonObject

        if (root.has("source_code")) {
            val sourceCode = root.get("source_code")
            if (!sourceCode.isJsonPrimitive || !sourceCode.asJsonPrimitive.isString) return null
            val status = root.intValue("status") ?: return null
            if (status != 0) return null
            return root.stringValue("code")?.let(::normalizeTransportTerminators)
        }

        if (root.has("notify_type")) {
            val notifyType = root.intValue("notify_type") ?: return null
            val notifyStatus = root.intValue("notify_status") ?: return null
            if (notifyType != 1 || notifyStatus != 1) return null
            val bytes = root.get("notify_data")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.map { it.intValue() ?: return null }
                ?.takeIf { values -> values.all { it in 0..255 } }
                ?: return null
            val decoded = decodeUtf8(bytes.map { it.toByte() }.toByteArray()) ?: return null
            return normalizeTransportTerminators(decoded)
        }

        return normalizeTransportTerminators(callbackValue)
    }

    /** Removes only trailing CR, LF, and NUL transport terminators. */
    fun normalizeTransportTerminators(rawValue: String): String {
        var value = rawValue
        while (value.isNotEmpty()) {
            when (value.last()) {
                '\r', '\n', '\u0000' -> value = value.dropLast(1)
                else -> break
            }
        }
        return value
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
    }

    private fun JsonObject.intValue(name: String): Int? = get(name)?.intValue()

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun JsonElement.intValue(): Int? = runCatching {
        if (!isJsonPrimitive) return@runCatching null
        if (asJsonPrimitive.isNumber) asInt
        else if (asJsonPrimitive.isString) asString.toIntOrNull()
        else null
    }.getOrNull()
}

/** A raw adapter callback decoder that produces only normalized scan text. */
fun interface BleScanCallbackDecoder {
    fun decode(callbackValue: String): String?
}

/**
 * Safe boundary for platform adapters turning raw callback text into a typed
 * [ScanPayload]. Adapters should use this factory (or an equivalent explicit
 * [BleScanCallbackDecoder]) before emitting [BleTransportEvent.ScanReceived].
 */
object BleScanPayloadFactory {
    /** Decoder for the callback envelopes observed by the iOS integration. */
    val observedIosDecoder: BleScanCallbackDecoder = BleScanCallbackDecoder {
        BleScanPayloadDecoder.decode(it)
    }

    fun fromRawCallback(
        callbackValue: String,
        source: InputSource,
        format: ScanFormat,
        timestampMillis: Long = 0L,
        decoder: BleScanCallbackDecoder = observedIosDecoder,
    ): ScanPayload? = decoder.decode(callbackValue)
        ?.takeIf(String::isNotEmpty)
        ?.let { value ->
            ScanPayload(
                value = value,
                source = source,
                format = format,
                timestampMillis = timestampMillis,
            )
        }
}

/**
 * Suppresses repeated normalized callbacks inside a strict, injected window.
 * The gate is reset when a transport link/session is recreated.
 */
class BleScanPayloadGate(
    private val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(duplicateWindowMillis >= 0) { "duplicateWindowMillis must not be negative" }
    }

    private var lastValue: String? = null
    private var lastAtMillis: Long? = null

    fun accept(rawValue: String, timestampMillis: Long = nowMillis()): String? {
        val normalized = BleScanPayloadDecoder.normalizeTransportTerminators(rawValue)
        if (normalized.isEmpty()) return null
        val previousAt = lastAtMillis
        if (lastValue == normalized && previousAt != null) {
            val elapsed = timestampMillis - previousAt
            if (elapsed >= 0 && elapsed < duplicateWindowMillis) return null
        }
        lastValue = normalized
        lastAtMillis = timestampMillis
        return normalized
    }

    fun reset() {
        lastValue = null
        lastAtMillis = null
    }

    private companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 750L
    }
}
