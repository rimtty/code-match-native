package jp.rimtty.codematch.feature.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import jp.rimtty.codematch.scanner.api.ScanFormat
import kotlinx.coroutines.delay

/** Camera permission state exposed to UI without leaking Android permission types. */
enum class CameraPermissionState {
    UNKNOWN,
    REQUESTING,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

/** Whether a usable camera is present on the current device. */
enum class CameraAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/** The visual guide shape required by the current logical scan format. */
enum class CameraGuideShape {
    QR_SQUARE,
    CODE_128_WIDE,
}

data class CameraGuide(val format: ScanFormat) {
    val shape: CameraGuideShape
        get() = when (format) {
            ScanFormat.QR -> CameraGuideShape.QR_SQUARE
            ScanFormat.CODE_128 -> CameraGuideShape.CODE_128_WIDE
        }

    /**
     * The normalized region of interest used by both the overlay and the
     * camera analyzer. The stage is deliberately fixed at 4:3, so these
     * coordinates remain identical in the PreviewView and in ML Kit's ROI.
     */
    val regionOfInterest: CameraRegion
        get() = when (shape) {
            CameraGuideShape.QR_SQUARE -> CameraRegion(
                left = .2675f,
                top = .19f,
                width = .465f,
                height = .62f,
            )
            CameraGuideShape.CODE_128_WIDE -> CameraRegion(
                left = .10f,
                top = .38f,
                width = .80f,
                height = .24f,
            )
        }

    /** Short alias for camera adapters that call the region `roi`. */
    val roi: CameraRegion get() = regionOfInterest
}

/** A normalized [0, 1] rectangle in preview coordinates. */
data class CameraRegion(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(left in 0f..1f)
        require(top in 0f..1f)
        require(width in 0f..1f && left + width <= 1f)
        require(height in 0f..1f && top + height <= 1f)
    }

    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** A normalized tap location that a CameraX host can map to a metering point. */
data class CameraFocusPoint(
    val xFraction: Float,
    val yFraction: Float,
) {
    init {
        require(xFraction in 0f..1f) { "xFraction must be between 0 and 1" }
        require(yFraction in 0f..1f) { "yFraction must be between 0 and 1" }
    }
}

/** Request passed to a preview slot supplied by the application camera host. */
data class CameraPreviewRequest(
    val format: ScanFormat,
    val guide: CameraGuide = CameraGuide(format),
    val onFocus: (CameraFocusPoint) -> Unit,
) {
    val regionOfInterest: CameraRegion get() = guide.regionOfInterest
}

/**
 * CameraX-free preview slot. The app can provide an AndroidView/PreviewView
 * implementation later without making this feature depend on CameraX.
 */
typealias CameraPreviewContent = @Composable (
    modifier: Modifier,
    request: CameraPreviewRequest,
) -> Unit

/**
 * Stateless camera stage used by the scan feature.
 *
 * The host owns permission, camera binding, image analysis, and the actual
 * preview. This composable owns only the stable 4:3 stage, format guide, tap
 * coordinate normalization, and accessible instructions. A square guide is
 * used for QR and a wide guide for Code 128.
 */
@Composable
fun CameraStage(
    format: ScanFormat,
    running: Boolean,
    modifier: Modifier = Modifier,
    previewContent: CameraPreviewContent? = null,
    onFocus: (CameraFocusPoint) -> Unit = {},
) {
    val guide = remember(format) { CameraGuide(format) }
    val latestOnFocus by rememberUpdatedState(onFocus)
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<CameraFocusPoint?>(null) }

    val focusDescription = stringResource(R.string.scan_camera_tap_to_focus)
    val focusActionDescription = stringResource(R.string.scan_camera_focus_action)
    val guideDescription = stringResource(
        if (format == ScanFormat.QR) {
            R.string.scan_camera_qr_guide
        } else {
            R.string.scan_camera_code128_guide
        },
    )
    val stageDescription = stringResource(
        R.string.scan_camera_stage_description,
        guideDescription,
        focusDescription,
    )
    val focusColor = MaterialTheme.colorScheme.secondary

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900L)
            focusPoint = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(
                color = Color.Black,
                shape = RoundedCornerShape(20.dp),
            )
            .onSizeChanged { stageSize = it }
            .semantics {
                contentDescription = stageDescription
                // The visual preview is a pointer surface, so a TalkBack or
                // keyboard user needs an equivalent action. Center focus is a
                // deterministic fallback when no screen coordinate exists.
                onClick(label = focusActionDescription) {
                    if (running) {
                        val center = CameraFocusPoint(.5f, .5f)
                        focusPoint = center
                        latestOnFocus(center)
                        true
                    } else {
                        false
                    }
                }
            }
            .testTag("scan_camera_stage"),
        contentAlignment = Alignment.Center,
    ) {
        if (running && previewContent != null) {
            previewContent(
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent, RoundedCornerShape(20.dp)),
                CameraPreviewRequest(
                    format = format,
                    guide = guide,
                    onFocus = { point ->
                        focusPoint = point
                        latestOnFocus(point)
                    },
                ),
            )
        } else {
            Text(
                text = stringResource(
                    if (running) R.string.scan_camera_preparing else R.string.scan_camera_stopped,
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }

        // Keep the gesture layer above an arbitrary PreviewView. The camera
        // host receives normalized coordinates and can call startFocusAndMetering.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Camera start/stop is state-driven. Include [running] in the
                // gesture key so a pointer coroutine cannot retain the old
                // running value across a lifecycle stop or rebind.
                .pointerInput(format, stageSize, running) {
                    detectTapGestures { offset ->
                        if (!running) return@detectTapGestures
                        val width = stageSize.width.toFloat()
                        val height = stageSize.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectTapGestures
                        val point = CameraFocusPoint(
                            xFraction = (offset.x / width).coerceIn(0f, 1f),
                            yFraction = (offset.y / height).coerceIn(0f, 1f),
                        )
                        focusPoint = point
                        latestOnFocus(point)
                    }
                },
        ) {
            val region = guide.regionOfInterest
            val left = region.left * size.width
            val top = region.top * size.height
            val guideWidth = region.width * size.width
            val guideHeight = region.height * size.height
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(guideWidth, guideHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
            focusPoint?.let { point ->
                drawCircle(
                    color = focusColor,
                    radius = 26.dp.toPx(),
                    center = Offset(
                        x = point.xFraction * size.width,
                        y = point.yFraction * size.height,
                    ),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}
