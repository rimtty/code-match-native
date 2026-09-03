package jp.rimtty.codematch.scanner.inateck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class InateckScannerCmdNativeTest {
    @Test
    fun officialLibraryGeneratesTheSdkOutputCommandUsedByIos() {
        val json = InateckScannerCmdJna.hidOutputResult(
            InateckScannerCmdJna.load(),
            1,
        )
        val command = InateckHidOutputCommandDecoder.decodeSdkOutput(json)

        assertNotNull(command)
        assertArrayEquals(
            byteArrayOf(0xF3.toByte(), 0x03, 0x7F, 0x5E, 0x01, 0xD4.toByte()),
            command,
        )
    }
}
