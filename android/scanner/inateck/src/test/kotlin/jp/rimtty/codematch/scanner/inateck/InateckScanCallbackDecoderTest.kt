package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InateckScanCallbackDecoderTest {
    @Test
    fun plainTextAndKnownSuccessfulEnvelopeAreAccepted() {
        assertEquals("plain-code", InateckScanCallbackDecoder.decode("plain-code\r"))
        assertEquals(
            "wrapped-code",
            InateckScanCallbackDecoder.decode(
                "{\"source_code\":\"scanner\",\"status\":0,\"code\":\"wrapped-code\"}",
            ),
        )
    }

    @Test
    fun unknownMalformedAndFailedJsonTrafficIsRejected() {
        assertNull(InateckScanCallbackDecoder.decode("{\"status\":0}"))
        assertNull(InateckScanCallbackDecoder.decode("{broken"))
        assertNull(
            InateckScanCallbackDecoder.decode(
                "{\"source_code\":\"scanner\",\"status\":1,\"code\":\"not-a-scan\"}",
            ),
        )
    }
}
