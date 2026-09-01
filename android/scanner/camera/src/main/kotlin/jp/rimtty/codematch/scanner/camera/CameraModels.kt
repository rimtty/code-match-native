package jp.rimtty.codematch.scanner.camera

import jp.rimtty.codematch.scanner.api.ScanFormat

/**
 * The lifecycle state exposed by [CameraScanner].
 *
 * Permission is intentionally represented separately from unavailable
 * hardware so the host can show an actionable message for each case.
 */
enum class CameraCaptureState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPED,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    UNAVAILABLE,
    ERROR,
}

/** Error categories safe to show or record in diagnostics. No scan value is included. */
enum class CameraErrorCode {
    PROVIDER_UNAVAILABLE,
    BACK_CAMERA_UNAVAILABLE,
    USE_CASE_BIND_FAILED,
    PERMISSION_DENIED,
    SCANNER_UNAVAILABLE,
    ANALYSIS_FAILED,
    FOCUS_FAILED,
}

/** A camera error that deliberately contains no image, barcode, or payload data. */
data class CameraError(
    val code: CameraErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

/**
 * A rectangle in the coordinate space of the displayed [PreviewView].
 *
 * Coordinates are pixels, with origin at the top-left. This is useful for
 * tests and for callers that already have an axis-aligned result. Production
 * analysis uses [CameraQuad] so every transformed corner is checked.
 */
data class CameraRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "CameraRect coordinates must be finite"
        }
        require(left <= right && top <= bottom) {
            "CameraRect must be ordered"
        }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = (width * height).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersection(other: CameraRect): CameraRect? {
        val intersection = CameraRect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
        return intersection.takeIf { it.width > 0f && it.height > 0f }
    }

    fun contains(pointX: Float, pointY: Float): Boolean =
        pointX >= left && pointX <= right && pointY >= top && pointY <= bottom

    fun contains(other: CameraRect): Boolean =
        other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom

    /** True only when all four corners of the candidate are inside the guide. */
    fun containsCandidate(candidate: CameraRect): Boolean =
        containsCandidate(
            CameraQuad(
                topLeft = CameraPoint(candidate.left, candidate.top),
                topRight = CameraPoint(candidate.right, candidate.top),
                bottomRight = CameraPoint(candidate.right, candidate.bottom),
                bottomLeft = CameraPoint(candidate.left, candidate.bottom),
            ),
        )

    /** True only when every transformed corner is inside the guide. */
    fun containsCandidate(candidate: CameraQuad): Boolean =
        candidate.area > 0f && candidate.corners.all { contains(it.x, it.y) }
}

/** A finite point in PreviewView pixel coordinates. */
data class CameraPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "CameraPoint coordinates must be finite" }
    }
}

/**
 * The four corners of a decoded barcode after CameraX's image-to-preview
 * transform. Keeping the quadrilateral instead of only its bounding box means
 * a rotated/cropped result cannot be accepted because of a misleading box.
 */
data class CameraQuad(
    val topLeft: CameraPoint,
    val topRight: CameraPoint,
    val bottomRight: CameraPoint,
    val bottomLeft: CameraPoint,
) {
    val corners: List<CameraPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Signed-independent polygon area using the shoelace formula. */
    val area: Float
        get() {
            var sum = 0f
            corners.forEachIndexed { index, point ->
                val next = corners[(index + 1) % corners.size]
                sum += point.x * next.y - next.x * point.y
            }
            return kotlin.math.abs(sum) / 2f
        }
}

/**
 * Guide rectangle expressed as fractions of the PreviewView dimensions.
 * Keeping this model independent from Compose makes the acceptance policy
 * deterministic and testable without a camera or Android UI runtime.
 */
data class CameraGuide(
    val leftFraction: Float,
    val topFraction: Float,
    val rightFraction: Float,
    val bottomFraction: Float,
) {
    init {
        require(leftFraction.isFinite() && topFraction.isFinite()) {
            "Guide coordinates must be finite"
        }
        require(rightFraction.isFinite() && bottomFraction.isFinite()) {
            "Guide coordinates must be finite"
        }
        require(leftFraction in 0f..1f && topFraction in 0f..1f) {
            "Guide start coordinates must be between 0 and 1"
        }
        require(rightFraction in 0f..1f && bottomFraction in 0f..1f) {
            "Guide end coordinates must be between 0 and 1"
        }
        require(leftFraction < rightFraction && topFraction < bottomFraction) {
            "Guide must have positive dimensions"
        }
    }

    fun toRect(width: Float, height: Float): CameraRect {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            "Viewport dimensions must be positive"
        }
        return CameraRect(
            left = width * leftFraction,
            top = height * topFraction,
            right = width * rightFraction,
            bottom = height * bottomFraction,
        )
    }

    fun accepts(candidate: CameraRect, width: Float, height: Float): Boolean =
        toRect(width, height).containsCandidate(candidate)

    fun accepts(candidate: CameraQuad, width: Float, height: Float): Boolean =
        toRect(width, height).containsCandidate(candidate)

    companion object {
        /** QR uses a centered square-ish guide with generous quiet space. */
        fun forFormat(format: ScanFormat?): CameraGuide = when (format) {
            ScanFormat.CODE_128 -> CameraGuide(
                leftFraction = 0.04f,
                topFraction = 0.30f,
                rightFraction = 0.96f,
                bottomFraction = 0.70f,
            )

            ScanFormat.QR, null -> CameraGuide(
                leftFraction = 0.12f,
                topFraction = 0.12f,
                rightFraction = 0.88f,
                bottomFraction = 0.88f,
            )
        }
    }
}

/**
 * Small concurrency gate used by the ImageAnalysis analyzer.
 *
 * CameraX drops queued frames with KEEP_ONLY_LATEST, while this gate drops a
 * frame that is delivered while ML Kit is still processing the prior frame.
 */
internal class AnalysisFrameGate {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }

    val isBusy: Boolean
        get() = inFlight.get()
}
