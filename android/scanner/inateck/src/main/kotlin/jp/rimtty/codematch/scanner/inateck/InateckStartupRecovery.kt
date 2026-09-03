package jp.rimtty.codematch.scanner.inateck

/**
 * Prevents duplicate automatic reconnect requests while the app remains in
 * one foreground epoch.
 *
 * ProcessLifecycleOwner can deliver an initial [onForeground] callback while
 * the scanner is being constructed and can deliver another callback when a
 * host is recreated. The reconnect operation itself is idempotent only after
 * a session has been bound, so the lifecycle edge is kept explicit here. A
 * failed request is deliberately not treated as permanent: returning to the
 * foreground after permissions or Bluetooth have recovered gets one new
 * attempt.
 */
internal class InateckStartupRecovery(
    private val reconnectKnownDevice: () -> Boolean,
) {
    private var foreground = false

    /** Requests recovery once for the current foreground epoch. */
    fun onForeground(): Boolean {
        if (foreground) return false
        foreground = true
        return reconnectKnownDevice()
    }

    /** Ends the epoch so the next foreground can retry recovery. */
    fun onBackground() {
        foreground = false
    }
}
