package jp.rimtty.codematch.scanner.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCommandQueueTest {
    @Test
    fun onlyOneCommandMayBeInFlight() {
        val dispatches = mutableListOf<BleCommand>()
        val callbacks = mutableListOf<(BleCommandOutcome) -> Unit>()
        val queue = BleCommandQueue(
            dispatch = { command, callback ->
                dispatches += command
                callbacks += callback
                true
            },
        )
        val first = command("first")
        val second = command("second")

        assertEquals(BleCommandSubmitResult.Started, queue.submit(first))
        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.ALREADY_IN_FLIGHT),
            queue.submit(second),
        )
        callbacks.single()(BleCommandOutcome.Succeeded)
        assertFalse(queue.isInFlight)

        assertEquals(BleCommandSubmitResult.Started, queue.submit(second))
        assertEquals(listOf(first, second), dispatches)
    }

    @Test
    fun timeoutBlocksLateCallbackAndFurtherCommandsUntilTransportReset() {
        var now = 10_000L
        val callbacks = mutableListOf<(BleCommandOutcome) -> Unit>()
        val outcomes = mutableListOf<BleCommandOutcome>()
        val queue = BleCommandQueue(
            dispatch = { _, callback ->
                callbacks += callback
                true
            },
            nowMillis = { now },
        )
        val first = command("settings", timeoutMillis = 3_000L)

        assertEquals(
            BleCommandSubmitResult.Started,
            queue.submit(first) { outcomes += it },
        )
        now += 2_999L
        assertEquals(BleCommandTickResult.Noop, queue.tick())
        now += 1L
        assertEquals(BleCommandTickResult.TimedOut(first), queue.tick())
        assertEquals(listOf(BleCommandOutcome.TimedOut), outcomes)
        assertTrue(queue.blockedAfterTimeout)

        // A callback arriving from the old GATT link cannot complete or open
        // the queue, and no replacement command is stacked behind it.
        callbacks.single()(BleCommandOutcome.Succeeded)
        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.BLOCKED_AFTER_TIMEOUT),
            queue.submit(command("replacement")),
        )
        assertFalse(queue.complete(first.id, outcome = BleCommandOutcome.Succeeded))

        queue.resetAfterTransportReset()
        assertFalse(queue.blockedAfterTimeout)
        assertEquals(BleCommandSubmitResult.Started, queue.submit(command("replacement")))
    }

    @Test
    fun dispatchFailureDoesNotLeaveAPhantomInFlightCommand() {
        val outcomes = mutableListOf<BleCommandOutcome>()
        val queue = BleCommandQueue(dispatch = { _, _ -> false })

        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.DISPATCH_FAILED),
            queue.submit(command("failure")) { outcomes += it },
        )
        assertFalse(queue.isInFlight)
        assertEquals(listOf(BleCommandOutcome.Failed("BLE command dispatch failed")), outcomes)
    }

    @Test
    fun synchronousCallbackBeforeFalseDispatchIsDeliveredOnlyOnce() {
        val outcomes = mutableListOf<BleCommandOutcome>()
        val queue = BleCommandQueue(
            dispatch = { _, callback ->
                callback(BleCommandOutcome.Succeeded)
                false
            },
        )

        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.DISPATCH_FAILED),
            queue.submit(command("sync-failure")) { outcomes += it },
        )
        assertEquals(listOf(BleCommandOutcome.Succeeded), outcomes)
        assertFalse(queue.isInFlight)
    }

    @Test
    fun dispatchExceptionDoesNotLeaveACommandInFlight() {
        val outcomes = mutableListOf<BleCommandOutcome>()
        val queue = BleCommandQueue(
            dispatch = { _, _ -> throw IllegalStateException("adapter failure") },
        )

        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.DISPATCH_FAILED),
            queue.submit(command("throwing")) { outcomes += it },
        )
        assertEquals(listOf(BleCommandOutcome.Failed("BLE command dispatch failed")), outcomes)
        assertFalse(queue.isInFlight)
        assertFalse(queue.blockedAfterTimeout)
    }

    @Test
    fun cancellationBlocksReplacementUntilTransportReset() {
        val callbacks = mutableListOf<(BleCommandOutcome) -> Unit>()
        val outcomes = mutableListOf<BleCommandOutcome>()
        val queue = BleCommandQueue(
            dispatch = { _, callback ->
                callbacks += callback
                true
            },
        )
        val first = command("cancelled")

        assertEquals(BleCommandSubmitResult.Started, queue.submit(first) { outcomes += it })
        assertTrue(queue.cancel("owner stopped"))
        assertEquals(listOf(BleCommandOutcome.Cancelled("owner stopped")), outcomes)
        assertTrue(queue.blockedAfterTimeout)
        assertEquals(
            BleCommandSubmitResult.Rejected(RejectionReason.BLOCKED_AFTER_TIMEOUT),
            queue.submit(command("replacement")),
        )
        callbacks.single()(BleCommandOutcome.Succeeded)
        assertEquals(listOf(BleCommandOutcome.Cancelled("owner stopped")), outcomes)

        queue.resetAfterTransportReset()
        assertFalse(queue.blockedAfterTimeout)
        assertEquals(BleCommandSubmitResult.Started, queue.submit(command("replacement")))
    }

    private fun command(
        id: String,
        timeoutMillis: Long = 3_000L,
    ) = BleCommand(
        id = id,
        characteristicUuid = "characteristic",
        payload = byteArrayOf(1, 2, 3),
        timeoutMillis = timeoutMillis,
    )
}
