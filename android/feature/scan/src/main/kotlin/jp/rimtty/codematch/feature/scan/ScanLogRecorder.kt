package jp.rimtty.codematch.feature.scan

import jp.rimtty.codematch.core.model.ScanLogEvent

/**
 * Sink for the scan log written by [ScanSessionCoordinator].
 *
 * The coordinator is the only place that holds both the raw payload and the
 * verdict the reducer produced for it, so it builds the events; where they are
 * stored (and which session id they belong to) is the host's business. A null
 * recorder disables logging entirely, which is what every pure reducer test
 * uses.
 */
fun interface ScanLogRecorder {
    fun record(event: ScanLogEvent)
}
