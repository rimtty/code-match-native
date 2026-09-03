package jp.rimtty.codematch.scanner.inateck

import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InateckNotificationResultTest {
    @Test
    fun decoderAcceptsOnlyTheOfficialNotificationShapeAndUnsignedBytes() {
        val decoded = InateckNotificationResultDecoder.decode(
            """{"notify_type":0,"notify_status":1,"notify_data":[0,127,255]}""",
        )

        assertEquals(0, decoded?.notifyType)
        assertEquals(1, decoded?.notifyStatus)
        assertArrayEquals(byteArrayOf(0, 127, -1), decoded?.notifyData)
    }

    @Test
    fun decoderRejectsUnknownTypesStatusesFieldsAndNonIntegerBytes() {
        val invalidResults = listOf(
            "null",
            "[]",
            "{broken",
            "{}",
            """{"notify_type":"0","notify_status":1,"notify_data":[1]}""",
            """{"notify_type":0,"notify_status":1.0,"notify_data":[1]}""",
            """{"notify_type":2,"notify_status":1,"notify_data":[1]}""",
            """{"notify_type":0,"notify_status":3,"notify_data":[1]}""",
            """{"notify_type":0,"notify_status":1,"notify_data":[-1]}""",
            """{"notify_type":0,"notify_status":1,"notify_data":[256]}""",
            """{"notify_type":0,"notify_status":1,"notify_data":[1.5]}""",
            """{"notify_type":0,"notify_status":1,"notify_data":["1"]}""",
            """{"notify_type":0,"notify_status":1,"notify_data":[],"extra":true}""",
        )

        invalidResults.forEach { json ->
            assertNull("unexpectedly accepted: $json", InateckNotificationResultDecoder.decode(json))
        }
    }

    @Test
    fun statusTwoIsDecodedSoAccumulatorCanResetWithoutEmitting() {
        val decoded = InateckNotificationResultDecoder.decode(
            """{"notify_type":1,"notify_status":2,"notify_data":[]}""",
        )

        assertEquals(1, decoded?.notifyType)
        assertEquals(2, decoded?.notifyStatus)
        assertTrue(decoded?.notifyData?.isEmpty() == true)
    }
}

class InateckNotificationAccumulatorTest {
    @Test
    fun statusZeroKeepsNativeReturnedDataAsTheNextPrefix() {
        val calls = mutableListOf<ByteArray>()
        val responses = ArrayDeque(
            listOf(
                result(type = 0, status = 0, data = byteArrayOf(10, 11)),
                result(type = 0, status = 1, data = byteArrayOf(99, 100)),
            ),
        )
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser { data ->
                calls += data
                responses.removeFirst()
            },
        )

        assertTrue(accumulator.append(byteArrayOf(1, 2)).isIncomplete())
        val outcome = accumulator.append(byteArrayOf(3))

        assertByteArrayListsEqual(
            listOf(byteArrayOf(1, 2), byteArrayOf(10, 11, 3)),
            calls,
            "native parser receives the returned incomplete data plus the next chunk",
        )
        assertTrue(outcome is InateckNotificationOutcome.Scan)
        assertArrayEquals(byteArrayOf(99, 100), (outcome as InateckNotificationOutcome.Scan).bytes)
        assertEquals(0, outcome.notifyType)
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun typeOneCompleteIsRetainedForBcst36IdleNotificationCompatibility() {
        val payload = "TEST-CODE-128".toByteArray()
        val frameWithoutChecksum = byteArrayOf(0x02, 0x00) + payload
        val frame = frameWithoutChecksum + byteArrayOf(frameWithoutChecksum.checksum())
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser {
                result(type = 1, status = 1, data = frame)
            },
        )

        val outcome = accumulator.append(byteArrayOf(1))

        assertTrue(outcome is InateckNotificationOutcome.Scan)
        outcome as InateckNotificationOutcome.Scan
        assertEquals(1, outcome.notifyType)
        assertArrayEquals(payload, outcome.bytes)
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun typeOneRejectsBadChecksumAndTooShortFrame() {
        val responses = ArrayDeque(
            listOf(
                result(type = 1, status = 1, data = byteArrayOf(2, 0, 65, 0)),
                result(type = 1, status = 1, data = byteArrayOf(2, 0, 2)),
            ),
        )
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser { responses.removeFirst() },
        )

        assertTrue(accumulator.append(byteArrayOf(1)).isError())
        assertEquals(0, accumulator.bufferedBytes)
        assertTrue(accumulator.append(byteArrayOf(2)).isError())
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun typeOneChecksumUsesUnsignedBytesAndLowEightBits() {
        val payload = byteArrayOf(0xff.toByte(), 0x80.toByte())
        val frameWithoutChecksum = byteArrayOf(0xfe.toByte(), 0xfd.toByte()) + payload
        val frame = frameWithoutChecksum + byteArrayOf(frameWithoutChecksum.checksum())
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser {
                result(type = 1, status = 1, data = frame)
            },
        )

        val outcome = accumulator.append(byteArrayOf(1))

        assertTrue(outcome is InateckNotificationOutcome.Scan)
        assertArrayEquals(payload, (outcome as InateckNotificationOutcome.Scan).bytes)
    }

    @Test
    fun nativeErrorAndMalformedResultResetThePendingPrefix() {
        val responses = ArrayDeque(
            listOf(
                result(type = 0, status = 0, data = byteArrayOf(20)),
                result(type = 0, status = 2, data = byteArrayOf()),
                "not-json",
            ),
        )
        val calls = mutableListOf<ByteArray>()
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser { data ->
                calls += data
                responses.removeFirst()
            },
        )

        assertTrue(accumulator.append(byteArrayOf(1)).isIncomplete())
        assertTrue(accumulator.append(byteArrayOf(2)).isError())
        assertEquals(0, accumulator.bufferedBytes)
        assertTrue(accumulator.append(byteArrayOf(3)).isError())
        assertByteArrayListsEqual(
            listOf(byteArrayOf(1), byteArrayOf(20, 2), byteArrayOf(3)),
            calls,
            "native error resets the prefix before the next native invocation",
        )
    }

    @Test
    fun overflowFailsClosedAndDropsBothInputAndPendingData() {
        var parserCalls = 0
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser {
                parserCalls++
                result(type = 0, status = 1, data = byteArrayOf(42))
            },
            maxBufferBytes = 4,
        )

        assertTrue(accumulator.append(byteArrayOf(1, 2, 3, 4, 5)).isError())
        assertEquals(0, parserCalls)
        assertEquals(0, accumulator.bufferedBytes)
        assertTrue(accumulator.append(byteArrayOf(6)).isScan())
        assertEquals(1, parserCalls)
    }

    @Test
    fun oversizedNativeIncompleteResultFailsClosed() {
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser {
                result(type = 0, status = 0, data = byteArrayOf(1, 2, 3, 4, 5))
            },
            maxBufferBytes = 4,
        )

        assertTrue(accumulator.append(byteArrayOf(9)).isError())
        assertEquals(0, accumulator.bufferedBytes)
    }

    @Test
    fun parserReceivesAStableCopyAndEmptyChunkDoesNotInvokeNative() {
        var parserCalls = 0
        var observed: ByteArray? = null
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser { data ->
                parserCalls++
                observed = data
                result(type = 0, status = 0, data = data)
            },
        )
        val source = byteArrayOf(1, 2)

        assertTrue(accumulator.append(ByteArray(0)).isIncomplete())
        accumulator.append(source)
        source[0] = 99

        assertEquals(1, parserCalls)
        assertArrayEquals(byteArrayOf(1, 2), observed)
        assertEquals(2, accumulator.bufferedBytes)
    }

    @Test
    fun parserExceptionResetsWithoutLeakingExceptionOrRetainingBytes() {
        val accumulator = InateckNotificationAccumulator(
            nativeParser = InateckNotificationNativeParser { error("native failure") },
        )

        assertTrue(accumulator.append(byteArrayOf(1, 2)).isError())
        assertEquals(0, accumulator.bufferedBytes)
    }

    private fun result(type: Int, status: Int, data: ByteArray): String =
        """{"notify_type":$type,"notify_status":$status,"notify_data":[${data.joinToString(",") { (it.toInt() and 0xff).toString() }}]}"""

    private fun ByteArray.checksum(): Byte =
        fold(0) { sum, value -> (sum + (value.toInt() and 0xff)) and 0xff }.toByte()

    private fun InateckNotificationOutcome.isIncomplete(): Boolean =
        this === InateckNotificationOutcome.Incomplete

    private fun InateckNotificationOutcome.isError(): Boolean =
        this === InateckNotificationOutcome.Error

    private fun InateckNotificationOutcome.isScan(): Boolean =
        this is InateckNotificationOutcome.Scan

    private fun assertByteArrayListsEqual(
        expected: List<ByteArray>,
        actual: List<ByteArray>,
        message: String,
    ) {
        assertEquals(message, expected.size, actual.size)
        expected.indices.forEach { index ->
            assertArrayEquals(expected[index], actual[index])
        }
    }
}
