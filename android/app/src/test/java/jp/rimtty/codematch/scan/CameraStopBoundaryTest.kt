package jp.rimtty.codematch.scan

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import jp.rimtty.codematch.feature.scan.CameraAvailability
import jp.rimtty.codematch.feature.scan.CameraFocusPoint
import jp.rimtty.codematch.feature.scan.CameraPermissionState
import jp.rimtty.codematch.feature.scan.CameraPreviewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStopBoundaryTest {
    @Test
    fun sessionEndWaitsForDelayedHostStopCompletion() {
        val host = DelayedCameraHost()
        val events = mutableListOf<String>()

        stopCameraBeforeSessionEnd(
            cameraHost = host,
            onCameraStopped = { events += "camera-stopped" },
            onSessionEnded = { events += "session-ended" },
        )

        assertTrue(host.stopRequested)
        assertEquals(emptyList<String>(), events)

        host.completeStop()

        assertEquals(
            listOf("camera-stopped", "session-ended"),
            events,
        )
    }

    @Test
    fun sessionEndWithoutHostRemainsImmediate() {
        val events = mutableListOf<String>()

        stopCameraBeforeSessionEnd(
            cameraHost = null,
            onCameraStopped = { events += "camera-stopped" },
            onSessionEnded = { events += "session-ended" },
        )

        assertEquals(
            listOf("camera-stopped", "session-ended"),
            events,
        )
    }

    private class DelayedCameraHost : CameraHost {
        var stopRequested = false
            private set

        private var stopCompletion: (() -> Unit)? = null

        override val availability: CameraAvailability = CameraAvailability.AVAILABLE
        override val permissionState: CameraPermissionState = CameraPermissionState.GRANTED

        override fun start(
            request: CameraStartRequest,
            callbacks: CameraHostCallbacks,
        ): CameraStartResult = CameraStartResult.Started

        override fun stop() {
            error("The callback stop boundary must be used")
        }

        override fun stop(onComplete: () -> Unit) {
            stopRequested = true
            stopCompletion = onComplete
        }

        override fun focus(point: CameraFocusPoint): Boolean = true

        @Composable
        override fun Preview(modifier: Modifier, request: CameraPreviewRequest) = Unit

        fun completeStop() {
            checkNotNull(stopCompletion).invoke()
            stopCompletion = null
        }
    }
}
