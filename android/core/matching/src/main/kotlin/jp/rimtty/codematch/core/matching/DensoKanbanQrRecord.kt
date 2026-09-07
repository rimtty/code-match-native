package jp.rimtty.codematch.core.matching

import java.util.Locale

/** One item of a Denso kanban QR. The item id is always three digits. */
data class DensoKanbanItem(
    val id: String,
    val value: String,
)

/**
 * A Denso kanban QR in the JAMA self-describing format.
 *
 * Record layout:
 * `JAMA` + one version digit + a four-digit header length (L) + a ten-character
 * preamble + (three-digit item id + two-digit length) * N + the data section.
 *
 * The header length spans from the header-length field itself to the end of the
 * item definitions, so the header body is `payload[9 until 5 + L]` and the data
 * section is `payload[5 + L ..]`. The declared item lengths must add up to the
 * data section's length exactly.
 *
 * The parser deliberately does not hardcode the observed 221-character payload:
 * the real kanban is L = 119 with 21 items over 97 data characters, but a
 * kanban with a different item layout parses by the very same rules. Keep this
 * identical to the Swift `DensoKanbanRecord`.
 */
data class DensoKanbanQrRecord(
    /** The single version digit after `JAMA`. */
    val version: String,
    /** The first ten header characters, kept raw and never validated. */
    val preamble: String,
    /** 100: form type. */
    val formType: String?,
    /** 104: part number, without hyphens. */
    val partNumber: String,
    /** 111: packaging code. */
    val packagingCode: String?,
    /** 112: pack quantity. */
    val packQuantity: Int,
    /** 121: next process. */
    val nextProcess: String?,
    /** 124 (plus `-` plus 141): instruction code. */
    val instructionCode: String?,
    /** 152: kanban serial, unique per box. */
    val kanbanSerial: String,
    /** 402: management number. */
    val managementNumber: String?,
    /** 519: delivery date, `YYYYMMDD`. */
    val deliveryDate: String?,
    /** 520: delivery run. */
    val deliveryRun: String?,
    /** 521: instructed quantity. */
    val instructedQuantity: Int?,
    /** 523: item number. */
    val itemNumber: String?,
    /** 401: receiving code. */
    val receivingCode: String?,
    /** Every item's raw value, in the order the header declares them. */
    val orderedItems: List<DensoKanbanItem>,
    /** Every item's raw value, keyed by item id. */
    val items: Map<String, String>,
    /** Terminator-stripped, uppercased payload; the length is left as read. */
    val canonicalPayload: String,
) {
    /** The delivery date for display: `20260908` becomes `2026/09/08`. */
    val formattedDeliveryDate: String?
        get() {
            val date = deliveryDate ?: return null
            if (date.length != DELIVERY_DATE_LENGTH) return date
            return "${date.substring(0, 4)}/${date.substring(4, 6)}/${date.substring(6)}"
        }

    companion object {
        /** The fixed prefix of the format. */
        const val FORMAT_PREFIX = "JAMA"

        /** `JAMA` plus the version digit plus the four-digit header length. */
        private const val HEADER_FIELDS_LENGTH = 9
        private const val PREAMBLE_LENGTH = 10
        private const val DESCRIPTOR_LENGTH = 5
        private const val DELIVERY_DATE_LENGTH = 8

        private val partNumberPattern = Regex("[A-Z0-9]{1,18}")

        /** Accept a scanner payload as a Denso kanban QR. */
        fun isValidScanPayload(payload: String): Boolean = parse(payload) != null

        /** The canonical form of a payload, or null when it does not parse. */
        fun canonicalPayload(payload: String): String? = parse(payload)?.canonicalPayload

        fun parse(payload: String): DensoKanbanQrRecord? {
            val record = CodeMatcher.stripTransportTerminators(payload).uppercase(Locale.ROOT)
            if (record.length < HEADER_FIELDS_LENGTH) return null
            if (!record.startsWith(FORMAT_PREFIX)) return null

            val version = record.substring(4, 5)
            if (!version.isAsciiDigits()) return null

            val headerLengthField = record.substring(5, HEADER_FIELDS_LENGTH)
            if (!headerLengthField.isAsciiDigits()) return null
            val headerLength = headerLengthField.toIntOrNull() ?: return null

            // The header length spans the header-length field itself, so the data
            // section starts at 5 + L.
            val dataStart = 5 + headerLength
            if (record.length < dataStart) return null

            val headerBody = record.substring(HEADER_FIELDS_LENGTH, dataStart)
            if (headerBody.length < PREAMBLE_LENGTH) return null
            val preamble = headerBody.substring(0, PREAMBLE_LENGTH)
            val descriptors = headerBody.substring(PREAMBLE_LENGTH)
            if (descriptors.isEmpty() || descriptors.length % DESCRIPTOR_LENGTH != 0) return null

            val declared = mutableListOf<Pair<String, Int>>()
            val seenIds = mutableSetOf<String>()
            var cursor = 0
            while (cursor < descriptors.length) {
                val id = descriptors.substring(cursor, cursor + 3)
                val lengthField = descriptors.substring(cursor + 3, cursor + DESCRIPTOR_LENGTH)
                if (!id.isAsciiDigits() || !lengthField.isAsciiDigits()) return null
                val length = lengthField.toIntOrNull() ?: return null
                // A repeated item id has no defined interpretation.
                if (!seenIds.add(id)) return null
                declared += id to length
                cursor += DESCRIPTOR_LENGTH
            }

            val data = record.substring(dataStart)
            if (declared.sumOf { it.second } != data.length) return null

            val orderedItems = mutableListOf<DensoKanbanItem>()
            val items = mutableMapOf<String, String>()
            var offset = 0
            declared.forEach { (id, length) ->
                val value = data.substring(offset, offset + length)
                orderedItems += DensoKanbanItem(id = id, value = value)
                items[id] = value
                offset += length
            }

            // Required items: part number, pack quantity and kanban serial. A
            // payload missing any of them is not treated as a kanban.
            val partNumber = items["104"]?.trim().orEmpty()
            if (!partNumberPattern.matches(partNumber)) return null
            val packQuantityField = items["112"]?.trim().orEmpty()
            if (!packQuantityField.isAsciiDigits()) return null
            val packQuantity = packQuantityField.toIntOrNull() ?: return null
            val kanbanSerial = items["152"]?.trim().orEmpty()
            if (kanbanSerial.isEmpty()) return null

            val instructionBase = items["124"].trimmedOrNull()
            val instructionSuffix = items["141"].trimmedOrNull()
            val instructionCode = instructionBase?.let { base ->
                if (instructionSuffix != null) "$base-$instructionSuffix" else base
            }

            return DensoKanbanQrRecord(
                version = version,
                preamble = preamble,
                formType = items["100"].trimmedOrNull(),
                partNumber = partNumber,
                packagingCode = items["111"].trimmedOrNull(),
                packQuantity = packQuantity,
                nextProcess = items["121"].trimmedOrNull(),
                instructionCode = instructionCode,
                kanbanSerial = kanbanSerial,
                managementNumber = items["402"].trimmedOrNull(),
                deliveryDate = items["519"].trimmedOrNull(),
                deliveryRun = items["520"].trimmedOrNull(),
                instructedQuantity = items["521"]?.trim()?.toIntOrNull(),
                itemNumber = items["523"].trimmedOrNull(),
                receivingCode = items["401"].trimmedOrNull(),
                orderedItems = orderedItems.toList(),
                items = items.toMap(),
                canonicalPayload = record,
            )
        }

        private fun String.isAsciiDigits(): Boolean =
            isNotEmpty() && all { it in '0'..'9' }

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
