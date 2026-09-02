package jp.rimtty.codematch.scan

import jp.rimtty.codematch.feature.scan.CameraPermissionState
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionStateTest {
    @Test
    fun settingsGrantClearsPermanentDenial() {
        assertEquals(
            CameraPermissionState.GRANTED,
            reconcileCameraPermissionState(
                current = CameraPermissionState.PERMANENTLY_DENIED,
                hasPermission = true,
                permissionWasRequested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun revokedGrantBecomesDenied() {
        assertEquals(
            CameraPermissionState.DENIED,
            reconcileCameraPermissionState(
                current = CameraPermissionState.GRANTED,
                hasPermission = false,
                permissionWasRequested = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun requestedPermissionWithoutRationaleIsPermanentDenial() {
        assertEquals(
            CameraPermissionState.PERMANENTLY_DENIED,
            reconcileCameraPermissionState(
                current = CameraPermissionState.REQUESTING,
                hasPermission = false,
                permissionWasRequested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun unknownStateIsPreservedBeforeFirstRequest() {
        assertEquals(
            CameraPermissionState.UNKNOWN,
            reconcileCameraPermissionState(
                current = CameraPermissionState.UNKNOWN,
                hasPermission = false,
                permissionWasRequested = false,
                shouldShowRationale = false,
            ),
        )
    }
}
