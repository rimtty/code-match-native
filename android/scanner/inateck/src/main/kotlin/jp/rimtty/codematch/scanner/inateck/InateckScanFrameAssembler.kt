package jp.rimtty.codematch.scanner.inateck

/**
 * Reassembles scanner notifications without retaining or logging their text.
 *
 * Inateck's FF01 notification can be split at the BLE MTU boundary. CR, LF,
 * and NUL terminate a frame. Devices that do not append a terminator are
 * supported by [flushPending] after a short idle period owned by the gateway.
 */
internal class InateckScanFrameAssembler(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    private val pending = ArrayList<Byte>()
    private var discardingOversizedFrame = false

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }

    val hasPendingBytes: Boolean
        get() = pending.isNotEmpty() || discardingOversizedFrame

    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val completed = mutableListOf<ByteArray>()
        for (value in chunk) {
            if (value.isTerminator()) {
                if (discardingOversizedFrame) {
                    discardingOversizedFrame = false
                    pending.clear()
                    continue
                }
                takePending()?.let(completed::add)
                continue
            }
            if (discardingOversizedFrame) continue
            if (pending.size >= maxFrameBytes) {
                // Discard the entire oversized candidate. A suffix of a
                // truncated payload must never be treated as a valid scan.
                pending.clear()
                discardingOversizedFrame = true
                continue
            }
            pending += value
        }
        return completed
    }

    fun flushPending(): ByteArray? {
        if (discardingOversizedFrame) {
            // Idle is not a trustworthy boundary for an oversized frame: a
            // delayed suffix could otherwise be accepted as a new barcode.
            // Quarantine until a real terminator or link/command reset.
            return null
        }
        return takePending()
    }

    fun reset() {
        pending.clear()
        discardingOversizedFrame = false
    }

    private fun takePending(): ByteArray? {
        if (pending.isEmpty()) return null
        return ByteArray(pending.size) { index -> pending[index] }.also {
            pending.clear()
        }
    }

    private fun Byte.isTerminator(): Boolean =
        this == '\r'.code.toByte() || this == '\n'.code.toByte() || this == 0.toByte()

    private companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 4_096
    }
}
