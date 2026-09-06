package jp.rimtty.codematch.core.matching

import java.util.Locale

/**
 * A 61-character delivery-slip QR record of destination モルテック (Moltec).
 *
 * Every field sits at a fixed position and text fields are left aligned and
 * padded with spaces, so trailing spaces are significant data and must never be
 * trimmed away before parsing. Scanners occasionally drop the trailing spaces of
 * the last field, which is why [canonicalPayload] pads a short payload back to
 * [RECORD_LENGTH] instead of rejecting it.
 *
 * This mirrors Swift's `MoltecQRRecord`; keep the validation rules identical.
 */
data class MoltecQrRecord(
    /** Characters 1-6: leading marker plus the orderer code, for example `AK6805`. */
    val ordererCode: String,
    /** Characters 7-16: the part number, left aligned with the padding removed. */
    val partNumber: String,
    /** Characters 26-32: the delivery number. */
    val deliveryNumber: String,
    /** Characters 36-38: the delivery destination. */
    val deliveryDestination: String,
    /** Characters 39-41: the TY location, or null when the field is blank. */
    val tyLocation: String?,
    /** Characters 42-46: the supply point. */
    val supplyPoint: String,
    /** Characters 47-53: the pack quantity, stored as a zero-padded integer. */
    val packQuantity: Int,
    /** Characters 54-57: the instructed delivery date (JUMP) as `MMDD`. */
    val instructionDate: String,
    /** Characters 58-61: the instructed time as `HHMM`, or null when blank. */
    val instructionTime: String?,
    /** The uppercased payload padded back to [RECORD_LENGTH]. */
    val canonicalPayload: String,
) {
    companion object {
        const val RECORD_LENGTH = 61
        const val MINIMUM_SCAN_PAYLOAD_LENGTH = 57

        private val charsetPattern = Regex("[A-Z0-9 -]{$RECORD_LENGTH}")
        private val partNumberPattern = Regex("[A-Z0-9]{9}[A-Z0-9 ]")
        private val packQuantityPattern = Regex("[0-9]{7}")
        private val instructionDatePattern = Regex("[0-9]{4}")
        private val instructionTimePattern = Regex("[0-9]{4}| {4}")

        /**
         * Normalize a scanned payload into the canonical 61-character record.
         *
         * Only transport terminators are stripped: leading and trailing spaces
         * belong to the record. A payload shorter than
         * [MINIMUM_SCAN_PAYLOAD_LENGTH], longer than [RECORD_LENGTH], or using
         * characters outside the record's alphabet returns null.
         */
        fun canonicalPayload(payload: String): String? {
            val value = CodeMatcher.stripTransportTerminators(payload).uppercase(Locale.ROOT)
            if (value.length < MINIMUM_SCAN_PAYLOAD_LENGTH || value.length > RECORD_LENGTH) {
                return null
            }
            return value.padEnd(RECORD_LENGTH, ' ').takeIf { charsetPattern.matches(it) }
        }

        /**
         * Accept only a complete Moltec QR record at a scanner boundary.
         *
         * The SDK's string callback carries no symbology, so the fixed length and
         * the required fields together keep a Code 128 payload out of the QR step.
         */
        fun isValidScanPayload(payload: String): Boolean = parse(payload) != null

        /** Parse the fixed-position fields from a QR payload. */
        fun parse(payload: String): MoltecQrRecord? {
            val record = canonicalPayload(payload) ?: return null

            val partField = record.substring(6, 16)
            val quantityField = record.substring(46, 53)
            val dateField = record.substring(53, 57)
            val timeField = record.substring(57, 61)
            if (!partNumberPattern.matches(partField)) return null
            if (!packQuantityPattern.matches(quantityField)) return null
            if (!instructionDatePattern.matches(dateField)) return null
            if (!instructionTimePattern.matches(timeField)) return null
            val packQuantity = quantityField.toIntOrNull() ?: return null

            return MoltecQrRecord(
                ordererCode = record.substring(0, 6),
                partNumber = partField.trim(),
                deliveryNumber = record.substring(25, 32),
                deliveryDestination = record.substring(35, 38).trim(),
                tyLocation = record.substring(38, 41).trim().takeIf { it.isNotEmpty() },
                supplyPoint = record.substring(41, 46).trim(),
                packQuantity = packQuantity,
                instructionDate = dateField,
                instructionTime = timeField.takeIf { it.isNotBlank() },
                canonicalPayload = record,
            )
        }
    }
}
