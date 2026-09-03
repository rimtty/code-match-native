package jp.rimtty.codematch.scanner.inateck

/**
 * Decodes the completed type-1 notification frame returned by the official
 * Inateck native parser.
 *
 * The Android command library stops after reassembling the notification. The
 * official iOS SDK performs one additional step for type 1: it verifies that
 * the last byte is the low eight bits of the preceding-byte sum, then exposes
 * bytes [2, lastIndex) as the scanned code. Keep that compatibility step at
 * this protocol boundary so framed bytes never reach the UTF-8 scan decoder.
 */
internal object InateckNotifyCodeFrameDecoder {
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < MINIMUM_FRAME_BYTES) return null

        var checksum = 0
        for (index in 0 until frame.lastIndex) {
            checksum = (checksum + frame[index].toUnsignedInt()) and 0xff
        }
        if (checksum != frame.last().toUnsignedInt()) return null

        return frame.copyOfRange(PAYLOAD_START_INDEX, frame.lastIndex)
            .takeIf(ByteArray::isNotEmpty)
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

    private const val MINIMUM_FRAME_BYTES = 4
    private const val PAYLOAD_START_INDEX = 2
}
