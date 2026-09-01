package jp.rimtty.codematch.scanner.ble

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BlePayloadTest {
    private val qrPayload = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"

    @Test
    fun decoderAcceptsDirectTextAndPinnedSdkEnvelope() {
        assertEquals("DIRECT-CODE", BleScanPayloadDecoder.decode("DIRECT-CODE\r\n\u0000"))
        val callback = """
            {"code":"$qrPayload\n","source_code":"44434C50","status":0}
        """.trimIndent()

        assertEquals(qrPayload, BleScanPayloadDecoder.decode(callback))
    }

    @Test
    fun decoderAcceptsNotificationBytesAndRejectsNonScanNotifications() {
        val root = JsonObject().apply {
            addProperty("notify_type", 1)
            addProperty("notify_status", 1)
            add("notify_data", JsonArray().apply {
                qrPayload.toByteArray().forEach { add(it.toInt()) }
                add(10)
                add(0)
            })
        }
        assertEquals(qrPayload, BleScanPayloadDecoder.decode(root.toString()))
        assertNull(BleScanPayloadDecoder.decode("""{"notify_type":0,"notify_status":1,"notify_data":[1]}"""))
        assertNull(BleScanPayloadDecoder.decode("""{"notify_type":1,"notify_status":0,"notify_data":[1]}"""))
        assertNull(BleScanPayloadDecoder.decode("""{"code":"BAD","source_code":"424144","status":1}"""))
        assertNull(BleScanPayloadDecoder.decode("""{"code":"BAD","source_code":424144,"status":0}"""))
        assertNull(BleScanPayloadDecoder.decode("""{"notify_type":1,"notify_status":1,"notify_data":[256]}"""))
        assertNull(BleScanPayloadDecoder.decode("""{"notify_type":1,"notify_status":1,"notify_data":[195,40]}"""))
    }

    @Test
    fun normalizerRemovesOnlyTrailingTransportTerminators() {
        assertEquals(
            "QR DATA   0*",
            BleScanPayloadDecoder.normalizeTransportTerminators("QR DATA   0*\r\n\u0000"),
        )
        assertEquals("  KEEP  ", BleScanPayloadDecoder.normalizeTransportTerminators("  KEEP  "))
        assertEquals("A\rB", BleScanPayloadDecoder.normalizeTransportTerminators("A\rB"))
    }

    @Test
    fun duplicateGateUsesStrictLessThanSevenHundredFiftyMillis() {
        val gate = BleScanPayloadGate()

        assertEquals("ABC", gate.accept("ABC\r", timestampMillis = 1_000L))
        assertNull(gate.accept("ABC\n", timestampMillis = 1_749L))
        assertEquals("ABC", gate.accept("ABC", timestampMillis = 1_750L))
        assertNull(gate.accept("ABC", timestampMillis = 1_751L))
        gate.reset()
        assertEquals("ABC", gate.accept("ABC", timestampMillis = 1_751L))
    }

    @Test
    fun malformedStructuralJsonIsRejectedWhileValidObjectTextRemainsCompatible() {
        assertNull(BleScanPayloadDecoder.decode("{bad\r"))
        assertNull(BleScanPayloadDecoder.decode("  [bad\n"))
        assertEquals(
            "{\"already_unwrapped\":\"DIRECT-CODE\"}",
            BleScanPayloadDecoder.decode("{\"already_unwrapped\":\"DIRECT-CODE\"}\r"),
        )
        assertFalse(BleScanPayloadDecoder.decode("\r\n")?.isNotEmpty() == true)
    }

    @Test
    fun rawCallbackFactoryCreatesTypedEventOnlyAfterDecoder() {
        val device = ScannerDevice("scanner-1", "BCST-47")
        val callback = BleTransportEvent.ScanReceived.fromRawCallback(
            callbackValue = "{\"code\":\"VALUE\",\"source_code\":\"534F55524345\",\"status\":0}",
            source = InputSource.BLUETOOTH,
            format = ScanFormat.CODE_128,
            timestampMillis = 42L,
            device = device,
        )
        assertEquals("VALUE", callback?.payload?.value)
        assertEquals(InputSource.BLUETOOTH, callback?.payload?.source)
        assertEquals(ScanFormat.CODE_128, callback?.payload?.format)
        assertEquals(42L, callback?.payload?.timestampMillis)
        assertEquals(device, callback?.device)
        assertNull(
            BleTransportEvent.ScanReceived.fromRawCallback(
                callbackValue = "{bad",
                source = InputSource.BLUETOOTH,
                format = ScanFormat.QR,
            ),
        )

        var decodeCalls = 0
        val custom = BleScanPayloadFactory.fromRawCallback(
            callbackValue = "opaque-wire-value",
            source = InputSource.BLUETOOTH,
            format = ScanFormat.QR,
            decoder = BleScanCallbackDecoder {
                decodeCalls++
                "decoded-value"
            },
        )
        assertEquals(1, decodeCalls)
        assertEquals("decoded-value", custom?.value)
    }
}
