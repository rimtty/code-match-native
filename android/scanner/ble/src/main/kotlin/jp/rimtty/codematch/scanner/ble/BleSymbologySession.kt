package jp.rimtty.codematch.scanner.ble

import jp.rimtty.codematch.scanner.api.ConfigurationState
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScannerDevice

/** State of the setting handshake and the fixed-mode scan session. */
sealed interface BleSymbologySessionState {
    data object Disconnected : BleSymbologySessionState
    data object LoadingSettings : BleSymbologySessionState
    data object Ready : BleSymbologySessionState
    data class ApplyingSession(val expectedFormat: ScanFormat) : BleSymbologySessionState
    data object SessionReady : BleSymbologySessionState
    data object Restoring : BleSymbologySessionState
    data object AwaitingTransportReset : BleSymbologySessionState
    data object AwaitingReconnect : BleSymbologySessionState
    data class Failed(val reason: String) : BleSymbologySessionState
}

/**
 * Protocol-neutral owner of scanner symbology settings.
 *
 * The adapter supplies the discovered settings characteristic endpoint and a
 * [BleSymbologyCodec], then translates [BleTransport.read]/[BleTransport.write]
 * to Android BluetoothGatt or a vendor SDK. No scanner UUID, wire encoding,
 * flag range, vendor command, or SDK class is fixed in this module.
 *
 * A session always applies one physical mode with QR and Code 128 enabled.
 * QR→Code128 changes are logical only. Before applying that mode, a fresh full
 * device inventory is required and is persisted. The inventory is retained
 * until restoration succeeds, including after a command/read timeout or
 * process interruption.
 */
class BleSymbologySession(
    private val device: ScannerDevice,
    private val transport: BleTransport,
    private val profile: BleSymbologyProfile,
    private val snapshotStore: SymbologySnapshotStore,
    private val diagnostics: BleDiagnosticLog = BleDiagnosticLog(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
    private val settingsReadTimeoutMillis: Long = DEFAULT_SETTINGS_READ_TIMEOUT_MILLIS,
) {
    init {
        require(commandTimeoutMillis > 0) { "commandTimeoutMillis must be positive" }
        require(settingsReadTimeoutMillis > 0) { "settingsReadTimeoutMillis must be positive" }
    }

    private enum class Operation {
        RECOVERY,
        START_SESSION,
        RESTORE_SESSION,
    }

    private var mutableState: BleSymbologySessionState = BleSymbologySessionState.Disconnected
    private var mutableConfiguration: ConfigurationState = ConfigurationState.Unavailable
    private var mutableExpectedFormat: ScanFormat? = null
    private var freshSnapshot: SymbologySnapshot? = null
    private var activeSnapshot: SymbologySnapshot? = null
    private var sessionActive = false
    private var pendingSettingsRead: PendingSettingsRead? = null
    private var connectionGeneration = 0L
    private var commandGeneration = 0L
    private var listener: ((BleSymbologySessionState, ConfigurationState) -> Unit)? = null

    private val commandQueue = BleCommandQueue(
        dispatch = { command, completion ->
            transport.write(
                characteristicUuid = command.characteristicUuid,
                payload = command.payload,
            ) { result ->
                completion(
                    result.fold(
                        onSuccess = { BleCommandOutcome.Succeeded },
                        onFailure = { BleCommandOutcome.Failed("BLE setting command failed") },
                    ),
                )
            }
        },
        nowMillis = nowMillis,
    )

    val state: BleSymbologySessionState get() = mutableState
    val configurationState: ConfigurationState get() = mutableConfiguration
    val expectedFormat: ScanFormat? get() = mutableExpectedFormat
    val physicalMode: BleSymbologyMode
        get() = BleSymbologyMode.forExpectedFormat(mutableExpectedFormat)
    val preSessionSnapshot: SymbologySnapshot? get() = activeSnapshot
    val currentSnapshot: SymbologySnapshot? get() = freshSnapshot
    val diagnosticEvents get() = diagnostics.snapshot()
    val isSessionActive: Boolean get() = sessionActive
    val isSettingsReadPending: Boolean get() = pendingSettingsRead != null
    val isReadyForScanning: Boolean
        get() = mutableState == BleSymbologySessionState.SessionReady &&
            mutableConfiguration.isReady
    val isCommandBlockedAfterTimeout: Boolean get() = commandQueue.blockedAfterTimeout

    fun setListener(listener: ((BleSymbologySessionState, ConfigurationState) -> Unit)?) {
        this.listener = listener
    }

    /**
     * Starts a fresh settings read after the transport reports a connection.
     * A persisted snapshot from another scanner is rejected before any write.
     */
    fun onConnected(): Boolean {
        if (mutableState == BleSymbologySessionState.AwaitingTransportReset ||
            pendingSettingsRead != null
        ) return false
        if (mutableState !is BleSymbologySessionState.Disconnected &&
            mutableState !is BleSymbologySessionState.AwaitingReconnect &&
            mutableState !is BleSymbologySessionState.Failed
        ) return false
        val generation = ++connectionGeneration
        pendingSettingsRead = PendingSettingsRead(generation, nowMillis())
        mutableState = BleSymbologySessionState.LoadingSettings
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        diagnostics.configuration("Scanner settings read requested")
        val accepted = try {
            transport.read(profile.settingsCharacteristicUuid) { result ->
                if (generation != connectionGeneration ||
                    pendingSettingsRead?.generation != generation
                ) return@read
                // Clear this before invoking the codec and any downstream
                // callback so a synchronous adapter cannot start a second
                // read against the same request generation.
                pendingSettingsRead = null
                val snapshot = result.getOrNull()?.let { payload ->
                    runCatching {
                        profile.codec.decodeSnapshot(
                            deviceId = device.id,
                            payload = payload,
                            capturedAtMillis = nowMillis(),
                        )
                    }.getOrNull()
                }
                if (snapshot == null || !snapshot.hasRequiredSessionSymbols()) {
                    fail("Scanner settings are incomplete")
                    return@read
                }
                freshSnapshot = snapshot
                val persisted = snapshotStore.load(device.id) ?: snapshotStore.loadLatest()
                if (persisted != null && persisted.deviceId != device.id) {
                    fail("Saved scanner settings belong to another device")
                    return@read
                }
                if (persisted != null) {
                    val recovery = mergeCurrentAreas(snapshot, persisted)
                    if (recovery == null) {
                        fail("Saved scanner settings no longer match the device inventory")
                        return@read
                    }
                    mutableState = BleSymbologySessionState.Restoring
                    apply(
                        operation = Operation.RECOVERY,
                        settings = recovery.settings,
                        expectedFormat = null,
                    )
                } else {
                    mutableState = BleSymbologySessionState.Ready
                    mutableConfiguration = ConfigurationState.Ready
                    emit()
                    diagnostics.configuration("Scanner settings read successfully")
                }
            }
        } catch (_: Exception) {
            if (pendingSettingsRead?.generation == generation) {
                pendingSettingsRead = null
                connectionGeneration++
                fail("Scanner settings read could not start")
            }
            return false
        }
        if (!accepted && pendingSettingsRead?.generation == generation) {
            pendingSettingsRead = null
            connectionGeneration++
            fail("Scanner settings read could not start")
        }
        return accepted
    }

    /**
     * Begins a session. The first call applies the fixed QR+Code128 mode; any
     * later QR/Code128 change updates only the logical expected format.
     */
    fun startSession(expectedFormat: ScanFormat): Boolean {
        if (sessionActive) {
            if (mutableState == BleSymbologySessionState.SessionReady ||
                mutableState is BleSymbologySessionState.ApplyingSession
            ) {
                mutableExpectedFormat = expectedFormat
                if (mutableState is BleSymbologySessionState.ApplyingSession) {
                    mutableState = BleSymbologySessionState.ApplyingSession(expectedFormat)
                }
                emit()
                return true
            }
            return false
        }
        val original = freshSnapshot
        if (mutableState != BleSymbologySessionState.Ready || original == null) return false
        if (!original.hasRequiredSessionSymbols()) return false

        // Persist before the first setting write. A process death after this
        // point leaves enough information for the next connection to restore
        // every reported symbology value.
        snapshotStore.save(original)
        activeSnapshot = original
        sessionActive = true
        mutableExpectedFormat = expectedFormat
        val restricted = original.forMode(BleSymbologyMode.SESSION_CODES) ?: run {
            fail("Scanner settings do not contain QR and Code 128")
            return false
        }
        apply(Operation.START_SESSION, restricted, expectedFormat)
        return true
    }

    /** Restore the complete pre-session inventory; Ready follows success only. */
    fun endSession(): Boolean {
        val original = activeSnapshot ?: return false
        if (!sessionActive || mutableState == BleSymbologySessionState.Restoring) return false
        mutableState = BleSymbologySessionState.Restoring
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        apply(Operation.RESTORE_SESSION, original.settings, expectedFormat = null)
        return true
    }

    /**
     * Called after the owner has observed that the timed-out link is closed.
     * Only now is a blocked command generation released.
     */
    fun onTransportResetCompleted() {
        pendingSettingsRead = null
        commandQueue.resetAfterTransportReset()
        connectionGeneration++
        mutableState = BleSymbologySessionState.AwaitingReconnect
        mutableConfiguration = ConfigurationState.Unavailable
        emit()
    }

    /**
     * Called on any disconnect callback. The recovery snapshot remains in the
     * store until [onConnected] completes its restore command successfully.
     */
    fun onTransportDisconnected() {
        pendingSettingsRead = null
        connectionGeneration++
        commandQueue.cancel("transport disconnected")
        commandQueue.resetAfterTransportReset()
        mutableState = BleSymbologySessionState.Disconnected
        mutableConfiguration = ConfigurationState.Unavailable
        emit()
    }

    /** Host drives timeout handling from its coroutine/ticker. */
    fun tick(atMillis: Long = nowMillis()): BleCommandTickResult {
        val pendingRead = pendingSettingsRead
        if (pendingRead != null &&
            atMillis - pendingRead.startedAtMillis >= settingsReadTimeoutMillis
        ) {
            pendingSettingsRead = null
            connectionGeneration++
            mutableState = BleSymbologySessionState.AwaitingTransportReset
            mutableConfiguration = ConfigurationState.Unavailable
            emit()
            diagnostics.error("Scanner settings read timed out; transport reset required")
            transport.disconnect(device)
            return BleCommandTickResult.Noop
        }
        val result = commandQueue.tick(atMillis)
        if (result is BleCommandTickResult.TimedOut) {
            // Keep the snapshot, refuse all additional commands, and require
            // the owner to close the link before attempting reconnection.
            mutableState = BleSymbologySessionState.AwaitingTransportReset
            mutableConfiguration = ConfigurationState.Unavailable
            emit()
            diagnostics.error("Scanner settings command timed out; transport reset required")
            transport.disconnect(device)
        }
        return result
    }

    private fun apply(
        operation: Operation,
        settings: List<ScannerSettingItem>,
        expectedFormat: ScanFormat?,
    ) {
        val snapshot = SymbologySnapshot(device.id, settings, nowMillis())
        val commands = SymbologySettings.commandsFor(snapshot, BleSymbologyMode.UNRESTRICTED)
        if (commands == null) {
            fail("Scanner settings command could not be built")
            return
        }
        val encodedPayload = runCatching { profile.codec.encodeCommands(commands) }.getOrNull()
            ?: run {
                fail("Scanner settings command could not be encoded")
                return
            }
        if (encodedPayload.isEmpty()) {
            fail("Scanner settings command could not be encoded")
            return
        }
        val command = BleCommand(
            id = "symbology-${++commandGeneration}",
            characteristicUuid = profile.settingsCharacteristicUuid,
            payload = encodedPayload,
            timeoutMillis = commandTimeoutMillis,
        )
        if (operation == Operation.START_SESSION) {
            mutableState = BleSymbologySessionState.ApplyingSession(
                expectedFormat ?: ScanFormat.QR,
            )
        }
        mutableConfiguration = ConfigurationState.Configuring
        emit()
        val submitted = commandQueue.submit(command) { outcome ->
            when (outcome) {
                BleCommandOutcome.Succeeded -> {
                    when (operation) {
                        Operation.RECOVERY -> {
                            snapshotStore.clear(device.id)
                            freshSnapshot = snapshot
                            clearActiveSession()
                            mutableState = BleSymbologySessionState.Ready
                            mutableConfiguration = ConfigurationState.Ready
                            diagnostics.configuration("Scanner settings restored")
                        }
                        Operation.START_SESSION -> {
                            mutableState = BleSymbologySessionState.SessionReady
                            mutableConfiguration = ConfigurationState.Ready
                            diagnostics.configuration("Scanner session settings ready")
                        }
                        Operation.RESTORE_SESSION -> {
                            snapshotStore.clear(device.id)
                            freshSnapshot = snapshot
                            clearActiveSession()
                            mutableState = BleSymbologySessionState.Ready
                            mutableConfiguration = ConfigurationState.Ready
                            diagnostics.configuration("Scanner settings restored")
                        }
                    }
                    emit()
                }
                BleCommandOutcome.TimedOut -> {
                    // tick() owns the transport reset transition. Keeping this
                    // callback branch side-effect free avoids duplicate close
                    // requests when an adapter reports its own timeout.
                }
                is BleCommandOutcome.Failed -> fail("Scanner settings command failed")
                is BleCommandOutcome.Cancelled -> {
                    if (mutableState != BleSymbologySessionState.Disconnected) {
                        fail("Scanner settings command cancelled")
                    }
                }
            }
        }
        if (submitted is BleCommandSubmitResult.Rejected) {
            fail("Scanner settings command is not available")
        }
    }

    private fun mergeCurrentAreas(
        current: SymbologySnapshot,
        saved: SymbologySnapshot,
    ): SymbologySnapshot? {
        if (current.deviceId != saved.deviceId) return null
        val currentIdentities = current.settings.groupingBy(::stableIdentity).eachCount()
        val savedIdentities = saved.settings.groupingBy(::stableIdentity).eachCount()
        if (currentIdentities != savedIdentities) return null

        val savedValues = saved.settings.groupBy(::stableIdentity)
            .mapValues { (_, values) -> ArrayDeque(values) }
        val merged = current.settings.map { item ->
            val values = savedValues[stableIdentity(item)] ?: return null
            val savedItem = values.removeFirstOrNull() ?: return null
            item.copy(value = savedItem.value)
        }
        return SymbologySnapshot(current.deviceId, merged, current.capturedAtMillis)
    }

    /** Name/area/flag identify one reported symbology across a reconnect. */
    private fun stableIdentity(item: ScannerSettingItem): SymbologyIdentity =
        SymbologyIdentity(
            name = item.name.lowercase(),
            area = item.area,
            flag = item.flag,
        )

    private fun fail(reason: String) {
        mutableState = BleSymbologySessionState.Failed(reason)
        mutableConfiguration = ConfigurationState.Failed(reason)
        emit()
        diagnostics.error("Scanner configuration failed")
    }

    private fun clearActiveSession() {
        activeSnapshot = null
        sessionActive = false
        mutableExpectedFormat = null
    }

    private data class PendingSettingsRead(
        val generation: Long,
        val startedAtMillis: Long,
    )

    private data class SymbologyIdentity(
        val name: String,
        val area: String,
        val flag: Int?,
    )

    private fun emit() {
        listener?.invoke(mutableState, mutableConfiguration)
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 3_000L
        const val DEFAULT_SETTINGS_READ_TIMEOUT_MILLIS = 3_000L
    }
}
