package jp.rimtty.codematch.scan

import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.fake.FakeExternalScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothFallbackReadinessTest {
    private fun scanner(baseline: Boolean, reading: Boolean): ExternalScanner =
        object : ExternalScanner by FakeExternalScanner() {
            override val isReadyToStartSession = baseline
            override val isReadyForScanning = reading
        }

    @Test fun resultClearsFallbackAfterVerifiedBaselineWithoutStartingInput() {
        assertTrue(bluetoothFallbackCanClear(InputSource.BLUETOOTH, null, scanner(true, false)))
    }

    @Test fun waitingForCodeStillRequiresReadingReadiness() {
        for (format in ScanFormat.entries) {
            assertFalse(bluetoothFallbackCanClear(InputSource.BLUETOOTH, format, scanner(true, false)))
            assertTrue(bluetoothFallbackCanClear(InputSource.BLUETOOTH, format, scanner(true, true)))
        }
    }

    @Test fun failedRecoveryOrCameraSourceDoesNotClearFallback() {
        assertFalse(bluetoothFallbackCanClear(InputSource.BLUETOOTH, null, scanner(false, false)))
        assertFalse(bluetoothFallbackCanClear(InputSource.CAMERA, null, scanner(true, true)))
        assertFalse(bluetoothFallbackCanClear(null, null, scanner(true, true)))
    }
}
