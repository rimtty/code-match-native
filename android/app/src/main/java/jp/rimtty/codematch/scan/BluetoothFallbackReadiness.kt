package jp.rimtty.codematch.scan

import jp.rimtty.codematch.scanner.api.ExternalScanner
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat

/** Result screens intentionally stop input; verified baseline readiness is sufficient there. */
internal fun bluetoothFallbackCanClear(
    source: InputSource?,
    expectedFormat: ScanFormat?,
    scanner: ExternalScanner,
): Boolean = source == InputSource.BLUETOOTH && if (expectedFormat == null) {
    scanner.isReadyToStartSession
} else {
    scanner.isReadyForScanning
}
