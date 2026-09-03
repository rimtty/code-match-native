package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InateckHidOutputCommandDecoderTest {
    @Test
    fun sdkOutputSuccessBecomesTheExactUnsignedByteCommand() {
        val command = InateckHidOutputCommandDecoder.decodeSdkOutput(
            """{"status":0,"data":[243,3,127,94,1,212]}""",
        )

        assertArrayEquals(byteArrayOf(-13, 3, 127, 94, 1, -44), command)
    }

    @Test
    fun failedOrMalformedHidResultsAreRejected() {
        listOf(
            null,
            "",
            "{}",
            "[]",
            "not-json",
            """{"status":1,"data":[243,3]}""",
            """{"status":"0","data":[243,3]}""",
            """{"status":0.0,"data":[243,3]}""",
            """{"status":0,"data":[]}""",
            """{"status":0,"data":[-1]}""",
            """{"status":0,"data":[256]}""",
            """{"status":0,"data":[1.5]}""",
            """{"status":0,"data":["1"]}""",
            """{"status":0,"data":[1],"extra":true}""",
        ).forEach { json ->
            assertNull("unexpectedly accepted: $json", InateckHidOutputCommandDecoder.decodeSdkOutput(json))
        }
    }

    @Test
    fun decodeAliasUsesTheSameStrictSdkOutputContract() {
        assertArrayEquals(
            byteArrayOf(0, 255.toByte()),
            InateckHidOutputCommandDecoder.decode(
                """{"status":0,"data":[0,255]}""",
            ),
        )
    }
}
