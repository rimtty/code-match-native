package jp.rimtty.codematch.scanner.inateck

/**
 * Diagnostic-only fault injection around the real SDK gateway. Calls must be serialized
 * on the host thread, just like the SDK adapter. Never installed by the normal factory.
 *
 * Reads reach the SDK, but their completion is held until explicitly released. This
 * allows the unmodified transport/session deadline to expire before a late callback.
 * Settings/illumination writes NEVER reach the SDK: failure is injected before dispatch.
 * SDK connection/authentication setup is still real and is not claimed to be read-only.
 * No payloads, device identities, inventories or vendor exceptions are logged.
 */
internal class InateckReadOnlyFaultGateway(
    private val sdk: InateckSdkGateway,
) : InateckSdkGateway {
    private class PendingRead(
        val completion: (Result<List<Map<String, String>>>) -> Unit,
        var result: Result<List<Map<String, String>>>? = null,
    )

    private var pending: PendingRead? = null
    private var closed = false
    val hasCompletedRead: Boolean get() = pending?.result != null
    override val readiness get() = sdk.readiness

    override fun startDiscovery(onDevice: (InateckSdkDevice) -> Unit, onFinished: () -> Unit) =
        !closed && sdk.startDiscovery(onDevice, onFinished)

    override fun stopDiscovery() = !closed && sdk.stopDiscovery()

    override fun connect(
        deviceId: String,
        onScanBytes: (ByteArray) -> Unit,
        onDisconnected: (Boolean) -> Unit,
        completion: (Result<Unit>) -> Unit,
    ) = !closed && sdk.connect(deviceId, onScanBytes, onDisconnected, completion)

    // Intentionally retain a held read across disconnect so the transport's own
    // generation guard, not this test double, must reject its late delivery.
    override fun disconnect(deviceId: String, completion: (Result<Unit>) -> Unit) =
        !closed && sdk.disconnect(deviceId, completion)

    override fun readSettings(
        deviceId: String,
        completion: (Result<List<Map<String, String>>>) -> Unit,
    ): Boolean {
        if (closed || pending != null) return false
        val read = PendingRead(completion)
        pending = read
        val accepted = try {
            sdk.readSettings(deviceId) { result ->
                if (!closed && pending === read && read.result == null) {
                    read.result = result.fold(
                        onSuccess = { Result.success(it.map { item -> item.toMap() }) },
                        onFailure = { Result.failure(IllegalStateException("SDK read failed")) },
                    )
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!accepted && pending === read) pending = null
        return accepted
    }

    /** Releases exactly once; callers can first wait for the real session deadline. */
    fun releaseCompletedRead(): Boolean {
        if (closed) return false
        val read = pending ?: return false
        val result = read.result ?: return false
        pending = null
        read.result = null
        read.completion(result)
        return true
    }

    override fun writeSettings(
        deviceId: String,
        commandJson: String,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        if (closed) return false
        completion(Result.failure(IllegalStateException("Injected setting failure; SDK write suppressed")))
        return true
    }

    override fun setIllumination(deviceId: String, enabled: Boolean, completion: (Result<Unit>) -> Unit) = false

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        sdk.close()
    }
}
