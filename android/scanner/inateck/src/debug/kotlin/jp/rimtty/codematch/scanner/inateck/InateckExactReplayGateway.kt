package jp.rimtty.codematch.scanner.inateck

import jp.rimtty.codematch.scanner.ble.SymbologySnapshot

/**
 * One-shot diagnostic replay of UNCHANGED symbologies using the real SDK.
 * Unlike the read-only fault gateway this class DOES dispatch a settings write.
 * A fresh SDK read must still equal the armed baseline before dispatch. The
 * write callback (including the production gateway's readback) is held for the
 * session's normal deadline/late-callback test. No inventory is persisted/logged.
 * All methods and callbacks must run on the same serialized host thread.
 */
internal class InateckExactReplayGateway(
    private val sdk: InateckSdkGateway,
) : InateckSdkGateway by sdk {
    private var baselineDevice: String? = null
    private var baseline: Set<InateckAreaNameSettingsContract.SettingTriple>? = null
    private var used = false
    private var closed = false
    private var generation = 0
    private var pendingCompletion: ((Result<Unit>) -> Unit)? = null
    private var pendingResult: Result<Unit>? = null
    var issuedWrites = 0
        private set
    val hasCompletedWrite get() = pendingResult != null
    val completedWriteSucceeded get() = pendingResult?.isSuccess == true

    fun arm(snapshot: SymbologySnapshot): Boolean {
        if (closed || used || baseline != null || !snapshot.hasRequiredSessionSymbols()) return false
        val commands = snapshot.settings.map {
            InateckAreaNameSettingsContract.SettingTriple(it.area, it.name, it.value.toString())
        }.toSet()
        if (commands.size != snapshot.settings.size || commands.any {
                !InateckAreaNameSettingsContract.isSymbologyCommandName(it.name)
            }) return false
        baselineDevice = snapshot.deviceId
        baseline = commands
        return true
    }

    override fun writeSettings(deviceId: String, commandJson: String, completion: (Result<Unit>) -> Unit): Boolean {
        if (closed || used || deviceId != baselineDevice || commandJson.length > 65_536) return false
        val expected = baseline ?: return false
        if (InateckAreaNameSettingsContract.parseCommand(commandJson) != expected) return false
        used = true
        pendingCompletion = completion
        val token = ++generation
        val accepted = try {
            sdk.readSettings(deviceId) { fresh ->
                if (closed || generation != token) return@readSettings
                generation++ // a duplicate read completion must not dispatch a second write
                if (fresh.getOrNull()?.let {
                        InateckAreaNameSettingsContract.normalizeInventory(it) == expected
                    } != true) {
                    completeImmediately("Replay refused: fresh inventory differs")
                } else {
                    // Fresh getSettingInfo completed before this dispatch. No
                    // requested setting differs from the same-device baseline.
                    val written = try {
                        sdk.writeSettings(deviceId, commandJson) { result ->
                            if (!closed && pendingResult == null && pendingCompletion != null) {
                                pendingResult = result.fold(
                                    onSuccess = { Result.success(Unit) },
                                    onFailure = { Result.failure(IllegalStateException("SDK replay failed")) },
                                )
                            }
                        }
                    } catch (_: Exception) { false }
                    if (written) issuedWrites++ else completeImmediately("SDK replay rejected")
                }
            }
        } catch (_: Exception) { false }
        if (!accepted) {
            generation++
            pendingCompletion = null
            pendingResult = null
        }
        return accepted
    }

    private fun completeImmediately(reason: String) {
        val completion = pendingCompletion
        pendingCompletion = null
        pendingResult = null
        completion?.invoke(Result.failure(IllegalStateException(reason)))
    }

    fun releaseCompletedWrite(): Boolean {
        if (closed) return false
        val completion = pendingCompletion ?: return false
        val result = pendingResult ?: return false
        pendingCompletion = null; pendingResult = null
        completion(result)
        return true
    }

    override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit): Boolean {
        // Invalidate the pre-write read but retain an already held write result
        // so the unmodified transport rejects its late callback after disconnect.
        generation++
        return !closed && sdk.disconnect(deviceId, completion)
    }

    override fun setIllumination(deviceId: String, enabled: Boolean, completion: (Result<Unit>) -> Unit) = false

    override fun close() {
        if (closed) return
        closed = true; generation++
        baselineDevice = null; baseline = null
        pendingCompletion = null; pendingResult = null
        sdk.close()
    }
}
