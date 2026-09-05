package jp.rimtty.codematch.scanner.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.rimtty.codematch.scanner.api.ScanFormat

/**
 * Compose host for [CameraScanner].
 *
 * The host owns the permission request and the view lifetime, while the
 * scanner owns CameraX binding. Tapping the preview is translated directly to
 * PreviewView coordinates and delegated to CameraX's metering-point factory.
 * A [CameraScanner] is supplied by the app so its payload callback can be
 * wired to the scan ViewModel without this module depending on feature code.
 */
@Composable
fun CameraScannerHost(
    cameraScanner: CameraScanner,
    expectedFormat: ScanFormat?,
    modifier: Modifier = Modifier,
    guide: CameraGuide = CameraGuide.forFormat(expectedFormat),
    showGuide: Boolean = true,
    guideColor: Color = Color(0xFFE2FF55),
    accessibilityDescription: String? = null,
    requestPermission: Boolean = true,
    onPermissionDenied: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPermissionDenied by rememberUpdatedState(onPermissionDenied)
    var permissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            cameraScanner.onPermissionGranted()
        } else {
            cameraScanner.onPermissionDenied()
            currentPermissionDenied()
        }
    }

    // Permission is requested on first camera start only. With a null format
    // the destination is not scanning, so no permission prompt is shown.
    androidx.compose.runtime.LaunchedEffect(expectedFormat, permissionGranted) {
        if (requestPermission && expectedFormat != null && !permissionGranted &&
            !context.hasCameraPermission()
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Keep callbacks current without rebuilding CameraX use cases when the
    // surrounding screen recomposes.
    SideEffect {
        cameraScanner.setGuide(guide)
        cameraScanner.updateExpectedFormat(expectedFormat)
    }

    DisposableEffect(cameraScanner, lifecycleOwner, previewView) {
        cameraScanner.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            expectedFormat = expectedFormat,
            guide = guide,
        )
        onDispose { cameraScanner.unbind() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(cameraScanner) {
                detectTapGestures { offset ->
                    cameraScanner.focusAt(offset.x, offset.y)
                }
            }
            .then(
                if (accessibilityDescription == null) Modifier
                else Modifier.semantics {
                    contentDescription = accessibilityDescription
                },
            )
            .testTag("camera_scanner_host"),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (showGuide && expectedFormat != null) {
            CameraGuideOverlay(
                guide = guide,
                color = guideColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CameraGuideOverlay(
    guide: CameraGuide,
    color: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val bounds = guide.toRect(size.width, size.height)
        val topLeft = Offset(bounds.left, bounds.top)
        drawRect(
            color = color,
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(bounds.width, bounds.height),
            style = Stroke(width = 3f),
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
