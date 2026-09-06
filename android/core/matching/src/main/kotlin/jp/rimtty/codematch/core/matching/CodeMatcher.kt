package jp.rimtty.codematch.core.matching

import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.MatchResult
import java.util.Locale

/**
 * Platform-independent part-number extraction and comparison.
 *
 * The two labels intentionally carry different payloads: a slip QR is a
 * fixed-length record whose part number sits at a fixed position, whereas a
 * product-tag Code 128 starts with a hyphenated part number followed by an
 * optional management code. The record layout depends on the destination
 * ([Destination.SAWAI]: 66 characters with a card number, [Destination.MOLTEN]:
 * 61 characters), so extraction detects the destination first. Keep this object
 * free of camera, Bluetooth, and Android dependencies so the exact same rules
 * can be exercised in local JVM tests and by the application layer.
 */
object CodeMatcher {
    /**
     * Uppercase a payload and retain only ASCII letters and digits.
     *
     * In particular, non-ASCII letters are discarded rather than transliterated;
     * this mirrors Swift's `isASCII && (isLetter || isNumber)` filter.
     */
    fun normalize(raw: String): String =
        raw.uppercase(Locale.ROOT).filter { character ->
            character in 'A'..'Z' || character in '0'..'9'
        }

    /**
     * Normalize a complete scanner payload for identity comparisons.
     *
     * Unlike [normalize], this deliberately preserves internal spaces and
     * punctuation because those fields distinguish one box QR from another.
     * Only surrounding whitespace and letter case are ignored, matching the
     * iOS history contract.
     */
    fun payloadIdentity(raw: String): String = raw.trim().uppercase(Locale.ROOT)

    /**
     * Remove only the leading and trailing transport terminators (CR, LF, NUL).
     *
     * Spaces are deliberately kept: a Molten record is space padded, so its
     * leading and trailing spaces are part of the payload.
     */
    fun stripTransportTerminators(raw: String): String =
        raw.trim { it == '\r' || it == '\n' || it == '\u0000' }

    /**
     * Decide which destination a QR payload belongs to, or null when it is
     * neither destination's record.
     */
    fun detectDestination(qrPayload: String): Destination? {
        val payload = stripTransportTerminators(qrPayload)
        if (isSawaiRecord(payload)) return Destination.SAWAI
        if (MoltenQrRecord.isValidScanPayload(payload)) return Destination.MOLTEN

        // A Molten record is 61 characters including its surrounding spaces, so
        // spaces can never be trimmed before that check. A Sawai record has no
        // padding at its edges, so a scanner that adds whitespace must not turn a
        // valid slip into a mismatch: retry it trimmed once Molten is ruled out.
        return if (isSawaiRecord(payload.trim())) Destination.SAWAI else null
    }

    /** The complete QR record length of a destination. */
    fun expectedQrLength(destination: Destination): Int = when (destination) {
        Destination.SAWAI -> KanbanQrRecord.REQUIRED_SCAN_PAYLOAD_LENGTH
        Destination.MOLTEN -> MoltenQrRecord.RECORD_LENGTH
    }

    /**
     * Normalize a QR payload for identity comparisons.
     *
     * A Molten payload is padded back to its full record length so a scan that
     * dropped the trailing spaces still identifies the same slip.
     */
    fun canonicalQrPayload(qrPayload: String): String =
        when (detectDestination(qrPayload)) {
            Destination.MOLTEN ->
                MoltenQrRecord.canonicalPayload(qrPayload) ?: payloadIdentity(qrPayload)
            else -> payloadIdentity(qrPayload)
        }

    /**
     * The key that tells one physical box from another within a session.
     *
     * A Sawai slip carries a card number, so its QR alone identifies the box. A
     * Molten slip repeats for every box of the part, so the tag's management
     * code has to be part of the key; without a tag there is no box identity.
     * Returns null when the QR is not a valid record of either destination.
     */
    fun boxIdentity(qrPayload: String, barcodePayload: String?): String? =
        when (detectDestination(qrPayload)) {
            Destination.SAWAI -> canonicalQrPayload(qrPayload)
            Destination.MOLTEN -> {
                val tag = payloadIdentity(barcodePayload.orEmpty())
                if (tag.isEmpty()) null else canonicalQrPayload(qrPayload) + "|" + tag
            }
            null -> null
        }

    /** Extract the part number before the first `@` in a Code 128 payload. */
    fun partNumberFromBarcode(raw: String): String? {
        val head = raw.substringBefore('@')
        return normalize(head).takeIf { it.isNotEmpty() }
    }

    /**
     * Extract the item number from a slip QR at its destination's fixed
     * position: characters 11–20 for [Destination.SAWAI], characters 7–16 for
     * [Destination.MOLTEN]. A payload that is neither destination's record
     * returns null and can never produce a match.
     */
    fun partNumberFromQr(raw: String): String? {
        val payload = stripTransportTerminators(raw)
        return when (detectDestination(payload)) {
            Destination.SAWAI -> KanbanQrRecord.parse(payload)?.partNumber
            Destination.MOLTEN -> MoltenQrRecord.parse(payload)?.partNumber
            null -> null
        }
    }

    /**
     * Compare the item number from a QR with the part number from Code 128.
     * Both payloads must parse as their record; there is no partial match.
     */
    fun compare(qrPayload: String, barcodePayload: String): MatchResult {
        val barcodePart = partNumberFromBarcode(barcodePayload)
            ?: return MatchResult.MISMATCH
        val qrPart = partNumberFromQr(qrPayload)
            ?: return MatchResult.MISMATCH

        return if (qrPart == barcodePart) MatchResult.MATCH else MatchResult.MISMATCH
    }

    /**
     * Format a part number the way the product tag prints it: 4-2-4 for a
     * ten-character number, 4-2-3 for the nine-character Molten form. Values of
     * any other length are returned unchanged.
     */
    fun formatPartNumber(partNumber: String): String {
        if (partNumber.length !in SHORT_PART_NUMBER_LENGTH..STANDARD_PART_NUMBER_LENGTH) {
            return partNumber
        }

        val head = partNumber.substring(0, 4)
        val middle = partNumber.substring(4, 6)
        val tail = partNumber.substring(6)
        return "$head-$middle-$tail"
    }

    private fun isSawaiRecord(payload: String): Boolean =
        payload.length == KanbanQrRecord.REQUIRED_SCAN_PAYLOAD_LENGTH &&
            KanbanQrRecord.parse(payload) != null

    private const val STANDARD_PART_NUMBER_LENGTH = 10
    private const val SHORT_PART_NUMBER_LENGTH = 9
}

/**
 * A 66-character delivery-slip/kanban QR record.
 *
 * Numeric quantities are encoded as integer hundredths (for example `00001200`
 * becomes `12.0`). Parsing is intentionally tolerant of an incomplete payload
 * because [parse] is also useful for displaying old saved entries; the scanner
 * boundary should use [isValidScanPayload] when it needs the strict 66-character
 * acceptance rule.
 */
data class KanbanQrRecord(
    val cardNumber: String,
    val partNumber: String,
    val partSuffix: String?,
    val deliveryQuantity: Double?,
    val instructedQuantity: Double?,
    val factoryCode: String?,
    val warehouseCode: String?,
    val supplyPointCode: String?
) {
    companion object {
        const val REQUIRED_SCAN_PAYLOAD_LENGTH = 66

        private val cardNumberPattern = Regex("[A-Z]{4}[0-9]{6}")
        private val partNumberPattern = Regex("[A-Z0-9]{10}")

        /** Accept only a complete standard QR record at a scanner boundary. */
        fun isValidScanPayload(payload: String): Boolean {
            val record = payload.trim()
            return record.length == REQUIRED_SCAN_PAYLOAD_LENGTH && parse(record) != null
        }

        /** Parse the fixed-position fields from a QR payload. */
        fun parse(payload: String): KanbanQrRecord? {
            val record = payload.trim().uppercase(Locale.ROOT)
            if (record.length < MINIMUM_PARSE_LENGTH) return null

            fun slice(start: Int, end: Int): String? =
                if (record.length >= end) record.substring(start, end) else null

            fun trimmedOrNull(value: String?): String? =
                value?.trim()?.takeIf { it.isNotEmpty() }

            fun quantity(value: String?): Double? =
                value?.trim()?.toDoubleOrNull()?.div(100.0)

            val cardNumber = slice(0, 10)
            val partNumber = slice(10, 20)
            if (cardNumber == null || !cardNumberPattern.matches(cardNumber)) return null
            if (partNumber == null || !partNumberPattern.matches(partNumber)) return null

            return KanbanQrRecord(
                cardNumber = cardNumber,
                partNumber = partNumber,
                partSuffix = trimmedOrNull(slice(20, 22)),
                deliveryQuantity = quantity(slice(22, 30)),
                instructedQuantity = quantity(slice(30, 38)),
                factoryCode = trimmedOrNull(slice(38, 39)),
                warehouseCode = trimmedOrNull(slice(51, 56)),
                supplyPointCode = trimmedOrNull(slice(56, 61))
            )
        }

        private const val MINIMUM_PARSE_LENGTH = 20
    }
}

/** A product-tag Code 128 payload (`PART-NO@management-code`). */
data class TagBarcodeRecord(
    val partNumber: String,
    val managementCode: String?
) {
    companion object {
        private val sawaiFormatPattern =
            Regex("[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{4}@[A-Z0-9]+")
        private val moltenFormatPattern =
            Regex("[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{3,4}@[A-Z0-9]+")

        /**
         * Strict scanner-boundary validation for the product tag format of one
         * destination: Sawai part numbers always end in a four-character block,
         * Molten part numbers end in three or four. Lowercase input is accepted
         * just as Swift's uppercase-before-regex implementation accepts it.
         */
        fun isValidScanPayload(payload: String, destination: Destination): Boolean {
            val value = payload.trim().uppercase(Locale.ROOT)
            return when (destination) {
                Destination.SAWAI -> sawaiFormatPattern.matches(value)
                Destination.MOLTEN -> moltenFormatPattern.matches(value)
            }
        }

        /** Parse a tag payload while preserving its original case. */
        fun parse(payload: String): TagBarcodeRecord? {
            val trimmed = payload.trim()
            if (trimmed.isEmpty()) return null

            val delimiter = trimmed.indexOf('@')
            val part = (if (delimiter >= 0) {
                trimmed.substring(0, delimiter)
            } else {
                trimmed
            }).trim()
            if (part.isEmpty()) return null

            val managementCode = if (delimiter >= 0) {
                trimmed.substring(delimiter + 1).trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            return TagBarcodeRecord(partNumber = part, managementCode = managementCode)
        }
    }
}
