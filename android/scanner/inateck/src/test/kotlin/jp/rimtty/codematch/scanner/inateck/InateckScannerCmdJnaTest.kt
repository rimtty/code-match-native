package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InateckScannerCmdJnaTest {
    @Test
    fun notificationWrapperUsesStandaloneLibraryContractAndCopiesInput() {
        var observed: ByteArray? = null
        var observedLength = -1L
        val api = fakeApi(
            onNotify = { data, length ->
                observed = data
                observedLength = length
                "{}"
            },
        )
        val source = byteArrayOf(1, 2, 3)

        assertEquals("{}", InateckScannerCmdJna.notifyDataResult(api, source, 2))
        source[0] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), observed)
        assertEquals(2L, observedLength)
        assertEquals("inateck_scanner_cmd", InateckScannerCmdJna.LIBRARY_NAME)
    }

    @Test
    fun hidWrapperPassesDocumentedOutputTypeAndRejectsUnknownType() {
        var observedOutputType: Byte? = null
        val api = fakeApi(
            onHid = { outputType ->
                observedOutputType = outputType
                "{\"status\":0,\"data\":[1]}"
            },
        )

        assertEquals(
            "{\"status\":0,\"data\":[1]}",
            InateckScannerCmdJna.hidOutputResult(api, 1),
        )
        assertEquals(1.toByte(), observedOutputType)
        assertThrows(IllegalArgumentException::class.java) {
            InateckScannerCmdJna.hidOutputResult(api, 2)
        }
    }

    @Test
    fun explicitNotificationLengthMustFitTheProvidedArray() {
        val api = fakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            InateckScannerCmdJna.notifyDataResult(api, byteArrayOf(1), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InateckScannerCmdJna.notifyDataResult(api, byteArrayOf(1), -1)
        }
    }

    @Test
    fun resultCheckerCopiesInputAndAcceptsOnlyNativeZero() {
        var observed: ByteArray? = null
        val success = fakeApi(onCheck = { bytes, length ->
            observed = bytes
            if (length == 2L) 0 else 1
        })
        val source = byteArrayOf(4, 5)

        assertEquals(true, InateckScannerCmdJna.checkResult(success, source))
        source[0] = 99
        assertArrayEquals(byteArrayOf(4, 5), observed)
        assertEquals(false, InateckScannerCmdJna.checkResult(success, byteArrayOf()))
    }

    private fun fakeApi(
        onNotify: (ByteArray, Long) -> String = { _, _ -> "{}" },
        onHid: (Byte) -> String = { "{}" },
        onCheck: (ByteArray, Long) -> Int = { _, _ -> 1 },
    ): InateckScannerCmdJna.Api = object : InateckScannerCmdJna.Api {
        override fun inateck_scanner_cmd_notify_data_result(data: ByteArray, length: Long): String =
            onNotify(data, length)

        override fun inateck_scanner_cmd_get_hid_output(outputType: Byte): String =
            onHid(outputType)

        override fun inateck_scanner_cmd_check_result(data: ByteArray, length: Long): Int =
            onCheck(data, length)
    }
}
