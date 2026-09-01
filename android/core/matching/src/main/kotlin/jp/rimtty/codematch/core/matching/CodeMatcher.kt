package jp.rimtty.codematch.core.matching

import jp.rimtty.codematch.core.model.MatchResult
import java.util.Locale

/**
 * Platform-independent part-number extraction and comparison.
 *
 * The two labels intentionally carry different payloads: a kanban QR starts
 * with a card number and then contains a ten-character item number, whereas a
 * product-tag Code 128 starts with a hyphenated part number followed by an
 * optional management code. Keep this object free of camera, Bluetooth, and
 * Android dependencies so the exact same rules can be exercised in local JVM
 * tests and by the application layer.
 */
object CodeMatcher {
    private val cardNumberPattern = Regex("[A-Z]{4}[0-9]{6}")
    private val qrPartNumberPattern = Regex("[A-Z0-9]{10}")

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

    /** Extract the part number before the first `@` in a Code 128 payload. */
    fun partNumberFromBarcode(raw: String): String? {
        val head = raw.substringBefore('@')
        return normalize(head).takeIf { it.isNotEmpty() }
    }

    /**
     * Extract the ten-character item number from a standard kanban QR.
     *
     * The first ten characters are a card number (`AAAA999999`); characters
     * 11–20 are the item number. A non-standard QR returns null here and is
     * handled by [compare]'s conservative containment fallback.
     */
    fun partNumberFromQr(raw: String): String? {
        val payload = raw.trim().uppercase(Locale.ROOT)
        if (payload.length < STANDARD_PART_NUMBER_END) return null

        val cardNumber = payload.substring(0, CARD_NUMBER_LENGTH)
        if (!cardNumberPattern.matches(cardNumber)) return null

        val partNumber = payload.substring(CARD_NUMBER_LENGTH, STANDARD_PART_NUMBER_END)
        return partNumber.takeIf { qrPartNumberPattern.matches(it) }
    }

    /** Compare the item number from a QR with the part number from Code 128. */
    fun compare(qrPayload: String, barcodePayload: String): MatchResult {
        val barcodePart = partNumberFromBarcode(barcodePayload)
            ?: return MatchResult.MISMATCH

        val qrPart = partNumberFromQr(qrPayload)
        if (qrPart != null) {
            return if (qrPart == barcodePart) MatchResult.MATCH else MatchResult.MISMATCH
        }

        // Do not let a short, common token produce an accidental match in an
        // unrecognised QR payload.
        if (barcodePart.length < MINIMUM_FALLBACK_PART_LENGTH) {
            return MatchResult.MISMATCH
        }
        return if (normalize(qrPayload).contains(barcodePart)) {
            MatchResult.MATCH
        } else {
            MatchResult.MISMATCH
        }
    }

    /**
     * Format a ten-character part number as the tag's 4-2-4 representation.
     * Values of any other length are returned unchanged.
     */
    fun formatPartNumber(partNumber: String): String {
        if (partNumber.length != STANDARD_PART_NUMBER_LENGTH) return partNumber

        val head = partNumber.substring(0, 4)
        val middle = partNumber.substring(4, 6)
        val tail = partNumber.substring(partNumber.length - 4)
        return "$head-$middle-$tail"
    }

    private const val CARD_NUMBER_LENGTH = 10
    private const val STANDARD_PART_NUMBER_LENGTH = 10
    private const val STANDARD_PART_NUMBER_END =
        CARD_NUMBER_LENGTH + STANDARD_PART_NUMBER_LENGTH
    private const val MINIMUM_FALLBACK_PART_LENGTH = 6
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
        private val cardNumberPattern = Regex("[A-Z]{4}[0-9]{6}")
        private val partNumberPattern = Regex("[A-Z0-9]{10}")

        /** Accept only a complete standard QR record at a scanner boundary. */
        fun isValidScanPayload(payload: String): Boolean {
            val record = payload.trim()
            return record.length == RECORD_LENGTH && parse(record) != null
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

        private const val RECORD_LENGTH = 66
        private const val MINIMUM_PARSE_LENGTH = 20
    }
}

/** A product-tag Code 128 payload (`PART-NO@management-code`). */
data class TagBarcodeRecord(
    val partNumber: String,
    val managementCode: String?
) {
    companion object {
        private val businessFormatPattern =
            Regex("[A-Z0-9]{4}-[A-Z0-9]{2}-[A-Z0-9]{4}@[A-Z0-9]+")

        /**
         * Strict scanner-boundary validation for the product tag format.
         * Lowercase input is accepted just as Swift's uppercase-before-regex
         * implementation accepts it.
         */
        fun isValidScanPayload(payload: String): Boolean {
            val value = payload.trim().uppercase(Locale.ROOT)
            return businessFormatPattern.matches(value)
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
