package jp.rimtty.codematch.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException

/**
 * Registers one back callback for both the system button and predictive
 * back gestures.
 *
 * The callback is intentionally committed only after the gesture flow
 * completes. A cancelled gesture therefore leaves the destination state
 * untouched, which is important for history details and the end-session
 * confirmation dialog.
 */
@Composable
fun CodeMatchBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            // Collecting the flow is required for the handler to receive the
            // completion/cancellation signal. We do not need a custom visual
            // animation because Material surfaces provide the transition.
            progress.collect { }
            currentOnBack()
        } catch (_: CancellationException) {
            // A cancelled predictive gesture must not mutate navigation state.
        }
    }
}
