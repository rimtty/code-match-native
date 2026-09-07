package jp.rimtty.codematch.core.data

import jp.rimtty.codematch.core.model.Destination
import jp.rimtty.codematch.core.model.ScanLogEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Room-backed store for the on-device scan log.
 *
 * Every write trims the table back to [DEFAULT_LIMIT] rows, so the log is
 * bounded without a background job and an operator can leave it enabled for
 * weeks. Nothing here writes a file: the export path in `core/export` turns
 * [export] into a document only when the operator asks for one.
 */
class ScanLogRepository(
    private val database: CodeMatchDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val dao: ScanLogDao = database.scanLogDao()

    /** Number of retained events; the settings screen renders this live. */
    val count: Flow<Int> = dao.count().distinctUntilChanged()

    suspend fun record(event: ScanLogEvent) {
        withContext(dispatcher) {
            dao.insert(event.toEntity())
            dao.trimToLatest(limit)
        }
    }

    /** The whole log, oldest first, ready to be serialized. */
    suspend fun export(): List<ScanLogEvent> = withContext(dispatcher) {
        dao.all().map { it.toModel() }
    }

    suspend fun clear() {
        withContext(dispatcher) { dao.clear() }
    }

    companion object {
        /** Retention cap shared with the iOS store; see the export header. */
        const val DEFAULT_LIMIT: Int = 5_000
    }
}

private fun ScanLogEvent.toEntity(): ScanLogEntity = ScanLogEntity(
    at = atEpochMillis,
    sessionId = sessionId,
    source = source,
    step = step,
    event = event,
    reason = reason,
    destination = destination?.id,
    qrPayload = qrPayload,
    barcodePayload = barcodePayload,
    code = code,
    boxNumber = boxNumber,
    message = message,
)

private fun ScanLogEntity.toModel(): ScanLogEvent = ScanLogEvent(
    atEpochMillis = at,
    sessionId = sessionId,
    source = source,
    step = step,
    event = event,
    reason = reason,
    destination = Destination.fromId(destination),
    qrPayload = qrPayload,
    barcodePayload = barcodePayload,
    code = code,
    boxNumber = boxNumber,
    message = message,
)
