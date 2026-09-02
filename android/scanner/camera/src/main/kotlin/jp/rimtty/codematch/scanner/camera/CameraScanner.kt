package jp.rimtty.codematch.scanner.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.Task
import jp.rimtty.codematch.scanner.api.InputSource
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Platform seams used by [CameraScanner]. Production callers use the public
 * constructor; the internal constructor lets JVM tests control provider
 * completion and ML Kit client creation without a real camera service.
 */
internal typealias CameraAnalyzerFactory = (
    previewViewProvider: () -> PreviewView?,
    guideProvider: () -> CameraGuide,
    onPayload: (ScanPayload) -> Unit,
    onError: (CameraError) -> Unit,
    mainExecutor: Executor,
    scannerFactory: (ScanFormat) -> BarcodeScanner,
) -> MlKitImageAnalyzer

internal class CameraScannerDependencies(
    val providerFutureFactory: (Context) -> CameraProviderFuture = ::defaultCameraProviderFuture,
    val scannerFactory: (ScanFormat) -> BarcodeScanner = ::createBarcodeScanner,
    val mainExecutorFactory: (Context) -> Executor = { ContextCompat.getMainExecutor(it) },
    val analysisExecutorFactory: () -> ExecutorService = ::newCameraAnalysisExecutor,
    val analyzerFactory: CameraAnalyzerFactory = { previewViewProvider,
        guideProvider,
        onPayload,
        onError,
        mainExecutor,
        scannerFactory,
    ->
        MlKitImageAnalyzer(
            previewViewProvider = previewViewProvider,
            guideProvider = guideProvider,
            onPayload = onPayload,
            onError = onError,
            mainExecutor = mainExecutor,
            scannerFactory = scannerFactory,
        )
    },
)

/** Small adapter around CameraX's Guava future for deterministic tests. */
internal interface CameraProviderFuture {
    fun addListener(listener: () -> Unit, executor: Executor)
    fun get(): CameraProviderAdapter
}

/** CameraX provider operations needed by the scanner binding state machine. */
internal interface CameraProviderAdapter {
    fun hasBackCamera(): Boolean
    fun unbind(vararg useCases: UseCase)
    fun bindToLifecycle(owner: LifecycleOwner, useCaseGroup: UseCaseGroup): Camera
}

private class ProcessCameraProviderFuture(
    private val future: com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>,
) : CameraProviderFuture {
    override fun addListener(listener: () -> Unit, executor: Executor) {
        future.addListener({ listener() }, executor)
    }

    override fun get(): CameraProviderAdapter = ProcessCameraProviderAdapter(future.get())
}

private class ProcessCameraProviderAdapter(
    private val provider: ProcessCameraProvider,
) : CameraProviderAdapter {
    override fun hasBackCamera(): Boolean =
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

    override fun unbind(vararg useCases: UseCase) {
        provider.unbind(*useCases)
    }

    override fun bindToLifecycle(owner: LifecycleOwner, useCaseGroup: UseCaseGroup): Camera =
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
}

private fun defaultCameraProviderFuture(context: Context): CameraProviderFuture =
    ProcessCameraProviderFuture(ProcessCameraProvider.getInstance(context))

private fun createBarcodeScanner(format: ScanFormat): BarcodeScanner {
    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(format.toMlKitFormat())
        .build()
    return BarcodeScanning.getClient(options)
}

private fun newCameraAnalysisExecutor(): ExecutorService = Executors.newSingleThreadExecutor {
    Thread(it, "CodeMatch-camera-analysis").apply { isDaemon = true }
}

private data class ViewPortSnapshot(
    val width: Int,
    val height: Int,
    val aspectNumerator: Int,
    val aspectDenominator: Int,
    val rotation: Int,
    val scaleType: Int,
    val layoutDirection: Int,
)

private fun PreviewView.viewPortSnapshot(viewPort: ViewPort): ViewPortSnapshot {
    val aspectRatio = viewPort.aspectRatio
    return ViewPortSnapshot(
        width = width,
        height = height,
        aspectNumerator = aspectRatio.numerator,
        aspectDenominator = aspectRatio.denominator,
        rotation = viewPort.rotation,
        scaleType = viewPort.scaleType,
        layoutDirection = viewPort.layoutDirection,
    )
}

/**
 * CameraX + bundled ML Kit barcode adapter for the QR -> Code 128 workflow.
 *
 * The adapter deliberately does not implement [jp.rimtty.codematch.scanner.api.ExternalScanner]:
 * camera input is selected and coordinated by the scan feature, which receives
 * [ScanPayload] values through [onPayload]. This keeps CameraX and ML Kit out
 * of the platform-neutral scanner contract.
 *
 * All lifecycle methods are main-thread methods. A scanner can be bound again
 * after [unbind] or after a lifecycle stop; [close] is terminal and releases
 * the analyzer executor. No image is copied, persisted, or logged.
 */
class CameraScanner private constructor(
    context: Context,
    private val onPayload: (ScanPayload) -> Unit,
    private val onStateChanged: (CameraCaptureState) -> Unit = {},
    private val onError: (CameraError) -> Unit = {},
    private val dependencies: CameraScannerDependencies,
) : AutoCloseable {
    constructor(
        context: Context,
        onPayload: (ScanPayload) -> Unit,
        onStateChanged: (CameraCaptureState) -> Unit = {},
        onError: (CameraError) -> Unit = {},
    ) : this(context, onPayload, onStateChanged, onError, CameraScannerDependencies())

    internal constructor(
        context: Context,
        dependencies: CameraScannerDependencies,
        onPayload: (ScanPayload) -> Unit,
        onStateChanged: (CameraCaptureState) -> Unit = {},
        onError: (CameraError) -> Unit = {},
    ) : this(context, onPayload, onStateChanged, onError, dependencies)

    private val appContext = context.applicationContext
    private val mainExecutor = dependencies.mainExecutorFactory(appContext)
    private val analysisExecutor: ExecutorService = dependencies.analysisExecutorFactory()
    private val analyzer = dependencies.analyzerFactory(
        { previewView },
        { guide },
        onPayload,
        onError,
        mainExecutor,
        dependencies.scannerFactory,
    )

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (owner === lifecycleOwner) requestBind()
        }

        override fun onStop(owner: LifecycleOwner) {
            if (owner === lifecycleOwner) requestStopUseCases()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            if (owner === lifecycleOwner) {
                // A destroyed owner is terminal for this scanner instance.
                // Mark it closed as well as releasing the native resources so
                // a stale host cannot attempt to reuse a shut-down analyzer.
                close()
            }
        }
    }

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var provider: CameraProviderAdapter? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var camera: Camera? = null
    private var bindGeneration: Long = 0L
    /** Generation of the provider future currently allowed to bind. */
    private var pendingBindGeneration: Long? = null
    /** True while CameraX has been unbound and ML Kit frames are draining. */
    private var unbindInProgress = false
    /** A start/bind request observed while the stop barrier was active. */
    private var rebindAfterUnbind = false
    private val unbindCompletions = ArrayList<() -> Unit>()
    private var viewPortRetryPending = false
    private var targetRotation: Int? = null
    private var isClosed = false
    private var _expectedFormat: ScanFormat? = null
    private var guide: CameraGuide = CameraGuide.forFormat(null)
    private var customGuide: CameraGuide? = null
    private var _captureState: CameraCaptureState = CameraCaptureState.IDLE

    /** Keep target rotation current when the activity handles orientation in place. */
    private val previewLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!isClosed) {
            updateTargetRotation()
            if (previewUseCase == null && analysisUseCase == null) {
                requestBind()
            }
        }
    }

    /** The format currently requested by the scan state machine. */
    val expectedFormat: ScanFormat?
        get() = _expectedFormat

    /** Current camera lifecycle/permission state. */
    val captureState: CameraCaptureState
        get() = _captureState

    /** The guide used for candidate acceptance and drawn by the Compose host. */
    val currentGuide: CameraGuide
        get() = guide

    /**
     * Attach the analyzer and preview to [lifecycleOwner]. Calling this more
     * than once is safe; a previous owner/view is detached before replacement.
     */
    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        expectedFormat: ScanFormat? = this.expectedFormat,
        guide: CameraGuide? = null,
    ) {
        check(!isClosed) { "CameraScanner is closed" }

        if (this.lifecycleOwner !== lifecycleOwner || this.previewView !== previewView) {
            unbind()
            this.lifecycleOwner = lifecycleOwner
            this.previewView = previewView
            previewView.addOnLayoutChangeListener(previewLayoutListener)
            lifecycleOwner.lifecycle.addObserver(this.lifecycleObserver)
        }

        this.customGuide = guide
        this.guide = guide ?: CameraGuide.forFormat(expectedFormat)
        updateExpectedFormat(expectedFormat)

        if (_captureState != CameraCaptureState.ERROR &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            requestBind()
        }
    }

    /**
     * Replace only the guide geometry. A null guide restores the per-format
     * default. This does not restart CameraX use cases.
     */
    @MainThread
    fun setGuide(guide: CameraGuide?) {
        customGuide = guide
        this.guide = guide ?: CameraGuide.forFormat(expectedFormat)
    }

    /**
     * Change the ML Kit allow-list without rebuilding the CameraX pipeline.
     * The old ML Kit client is retired only after any in-flight frame finishes.
     */
    @MainThread
    fun updateExpectedFormat(format: ScanFormat?) {
        check(!isClosed) { "CameraScanner is closed" }
        _expectedFormat = format
        if (customGuide == null) guide = CameraGuide.forFormat(format)
        if (!analyzer.updateExpectedFormat(format)) {
            setCaptureState(CameraCaptureState.ERROR)
            return
        }
        // A retry after a previous provider/model failure is allowed to move
        // back into the normal binding state once its analyzer is available.
        if (_captureState == CameraCaptureState.ERROR) {
            setCaptureState(CameraCaptureState.IDLE)
        }

        if (format == null) {
            // Stop the physical use cases but retain the attached lifecycle
            // owner and PreviewView. A later non-null format can therefore
            // rebind the same host after this drain completes.
            requestStopUseCases()
        } else {
            requestBind()
        }
    }

    /** Notify the scanner that the host's permission request was accepted. */
    @MainThread
    fun onPermissionGranted() {
        if (!isClosed) requestBind()
    }

    /** Notify the scanner that the host's permission request was rejected. */
    @MainThread
    fun onPermissionDenied() {
        if (isClosed) return
        requestStopUseCases(
            onComplete = {
                if (!isClosed) setCaptureState(CameraCaptureState.PERMISSION_DENIED)
            },
        )
    }

    /**
     * Stop and detach all CameraX use cases while retaining the object for a
     * later [bind]. Lifecycle backgrounding uses this same safe boundary.
     */
    @MainThread
    fun unbind(onComplete: () -> Unit = {}) {
        requestStopUseCases(onComplete = onComplete, detachHost = true)
    }

    /**
     * Begin the CameraX stop boundary. CameraX's provider unbind is
     * synchronous, but an ImageAnalysis frame can still be waiting on an
     * asynchronous ML Kit Task. [onComplete] is delivered only after both
     * boundaries have completed.
     */
    @MainThread
    private fun requestStopUseCases(
        onComplete: () -> Unit = {},
        detachHost: Boolean = false,
    ) {
        if (isClosed) {
            dispatchOnMain(onComplete)
            return
        }
        unbindCompletions += onComplete
        if (unbindInProgress) {
            if (detachHost) detachHostBindings()
            return
        }

        unbindInProgress = true
        bindGeneration += 1
        if (detachHost) detachHostBindings()
        unbindUseCases()
        analyzer.awaitIdle {
            dispatchOnMain(::finishUnbind)
        }
    }

    /**
     * Convert a tap in PreviewView coordinates to CameraX's metering point and
     * start AF/AE. PreviewView owns the crop/rotation transform, so this stays
     * correct for portrait, landscape, and FILL_CENTER letter/crop changes.
     */
    @MainThread
    fun focusAt(viewX: Float, viewY: Float): Boolean {
        val currentPreview = previewView ?: return false
        val currentCamera = camera ?: return false
        if (viewX.isNaN() || viewY.isNaN() ||
            currentPreview.width <= 0 || currentPreview.height <= 0
        ) {
            return false
        }

        val x = viewX.coerceIn(0f, currentPreview.width.toFloat())
        val y = viewY.coerceIn(0f, currentPreview.height.toFloat())
        return try {
            val point = currentPreview.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            ).build()
            val focusFuture = currentCamera.cameraControl.startFocusAndMetering(action)
            focusFuture.addListener(
                {
                    try {
                        if (!focusFuture.get().isFocusSuccessful) {
                            onError(
                                CameraError(
                                    code = CameraErrorCode.FOCUS_FAILED,
                                    message = "Camera focus did not succeed",
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        onError(
                            CameraError(
                                code = CameraErrorCode.FOCUS_FAILED,
                                message = "Camera focus could not be started",
                            ),
                        )
                    }
                },
                mainExecutor,
            )
            true
        } catch (_: IllegalArgumentException) {
            onError(
                CameraError(
                    code = CameraErrorCode.FOCUS_FAILED,
                    message = "Camera focus point was outside the preview",
                ),
            )
            false
        }
    }

    @MainThread
    fun focusAt(viewPoint: android.graphics.PointF): Boolean =
        focusAt(viewPoint.x, viewPoint.y)

    /** Focus using coordinates normalized to the currently attached PreviewView. */
    @MainThread
    fun focusAtNormalized(xFraction: Float, yFraction: Float): Boolean {
        if (!xFraction.isFinite() || !yFraction.isFinite()) return false
        val currentPreview = previewView ?: return false
        if (currentPreview.width <= 0 || currentPreview.height <= 0) return false
        return focusAt(
            viewX = xFraction.coerceIn(0f, 1f) * currentPreview.width,
            viewY = yFraction.coerceIn(0f, 1f) * currentPreview.height,
        )
    }

    /**
     * Release the analyzer and its executor. If a frame is still being
     * analyzed, the analyzer keeps its ML Kit task and image alive until that
     * task completes; the task's completion path therefore remains safe even
     * after this instance is no longer reachable by the UI.
     */
    @MainThread
    override fun close() {
        if (isClosed) return
        isClosed = true
        bindGeneration += 1
        detachHostBindings()
        if (!unbindInProgress) {
            unbindInProgress = true
            unbindUseCases()
            analyzer.awaitIdle {
                dispatchOnMain(::finishUnbind)
            }
        }
        lifecycleOwner = null
        previewView = null
        targetRotation = null
        analyzer.close()
        analysisExecutor.shutdown()
    }

    private fun requestBind() {
        if (isClosed || _captureState == CameraCaptureState.ERROR) return
        if (unbindInProgress) {
            rebindAfterUnbind = true
            return
        }
        val owner = lifecycleOwner ?: return
        val currentPreview = previewView ?: return
        val format = expectedFormat ?: return
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (previewUseCase != null && analysisUseCase != null) return
        if (pendingBindGeneration != null) return

        val viewPort = currentPreview.viewPort
        if (viewPort == null) {
            // PreviewView exposes its ViewPort only after it is attached and
            // laid out. Retry once on the UI queue; repeated state updates do
            // not enqueue parallel retries.
            if (!viewPortRetryPending) {
                viewPortRetryPending = true
                currentPreview.post {
                    viewPortRetryPending = false
                    if (!isClosed && owner === lifecycleOwner &&
                        currentPreview === previewView &&
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        // Re-read the current format. It may have changed
                        // while this retry was queued (QR -> Code 128).
                        requestBind()
                    }
                }
            }
            return
        }
        val viewPortSnapshot = currentPreview.viewPortSnapshot(viewPort)

        if (!hasCameraPermission()) {
            setCaptureState(CameraCaptureState.PERMISSION_REQUIRED)
            return
        }

        setCaptureState(CameraCaptureState.STARTING)
        val generation = ++bindGeneration
        pendingBindGeneration = generation
        val future = try {
            dependencies.providerFutureFactory(appContext)
        } catch (_: Exception) {
            pendingBindGeneration = null
            reportBindFailure(
                generation = generation,
                code = CameraErrorCode.PROVIDER_UNAVAILABLE,
                message = "Camera provider is not available",
            )
            return
        }
        try {
            future.addListener(
                {
                    var cameraProvider: CameraProviderAdapter? = null
                    var preview: Preview? = null
                    var analysis: ImageAnalysis? = null
                    if (pendingBindGeneration == generation) pendingBindGeneration = null
                    if (isClosed || generation != bindGeneration ||
                        owner !== lifecycleOwner || currentPreview !== previewView ||
                        !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        return@addListener
                    }
                    // A logical QR -> Code 128 change can happen while the
                    // provider future is pending. updateExpectedFormat() could
                    // not enqueue a second bind while this generation owned
                    // the slot, so restart now with the current format.
                    if (expectedFormat != format) {
                        requestBind()
                        return@addListener
                    }
                    // A rotation or size change can replace PreviewView's
                    // ViewPort while the provider future is pending. Binding
                    // with the stale viewport would make ML Kit coordinates
                    // disagree with the displayed crop, so retry from the
                    // current view state.
                    val currentViewPort = currentPreview.viewPort
                    if (currentViewPort == null ||
                        currentPreview.viewPortSnapshot(currentViewPort) != viewPortSnapshot
                    ) {
                        requestBind()
                        return@addListener
                    }

                    try {
                        val resolvedProvider = future.get()
                        cameraProvider = resolvedProvider
                        if (!resolvedProvider.hasBackCamera()) {
                            setCaptureState(CameraCaptureState.UNAVAILABLE)
                            onError(
                                CameraError(
                                    code = CameraErrorCode.BACK_CAMERA_UNAVAILABLE,
                                    message = "A usable back camera is not available",
                                ),
                            )
                            return@addListener
                        }

                        val targetRotation = currentPreview.display?.rotation ?: Surface.ROTATION_0
                        this@CameraScanner.targetRotation = targetRotation
                        val builtPreview = Preview.Builder()
                            .setTargetRotation(targetRotation)
                            .build()
                        preview = builtPreview
                        val builtAnalysis = ImageAnalysis.Builder()
                            .setTargetRotation(targetRotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setImageQueueDepth(1)
                            .build()
                        analysis = builtAnalysis

                        currentPreview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        currentPreview.scaleType = PreviewView.ScaleType.FILL_CENTER
                        builtPreview.setSurfaceProvider(currentPreview.surfaceProvider)
                        builtAnalysis.setAnalyzer(analysisExecutor, analyzer)

                        // Preview and analysis must share PreviewView's ViewPort.
                        // CoordinateTransform is only valid when its source and
                        // target use the same crop/rotation coordinate system.
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(builtPreview)
                            .addUseCase(builtAnalysis)
                            .setViewPort(viewPort)
                            .build()

                        // Unbind only these use cases. Calling unbindAll() here
                        // could interrupt another feature using the same provider.
                        resolvedProvider.unbind(builtPreview, builtAnalysis)
                        val boundCamera = resolvedProvider.bindToLifecycle(
                            owner,
                            useCaseGroup,
                        )
                        provider = resolvedProvider
                        previewUseCase = preview
                        analysisUseCase = analysis
                        camera = boundCamera
                        setCaptureState(CameraCaptureState.RUNNING)
                    } catch (_: SecurityException) {
                        cleanupFailedBind(cameraProvider, preview, analysis)
                        setCaptureState(CameraCaptureState.PERMISSION_DENIED)
                        onError(
                            CameraError(
                                code = CameraErrorCode.PERMISSION_DENIED,
                                message = "Camera permission was not available",
                            ),
                        )
                    } catch (_: java.util.concurrent.ExecutionException) {
                        cleanupFailedBind(cameraProvider, preview, analysis)
                        setCaptureState(CameraCaptureState.ERROR)
                        onError(
                            CameraError(
                                code = CameraErrorCode.PROVIDER_UNAVAILABLE,
                                message = "Camera provider could not be initialized",
                            ),
                        )
                    } catch (_: Exception) {
                        cleanupFailedBind(cameraProvider, preview, analysis)
                        setCaptureState(CameraCaptureState.ERROR)
                        onError(
                            CameraError(
                                code = CameraErrorCode.USE_CASE_BIND_FAILED,
                                message = "Camera capture could not be started",
                            ),
                        )
                    }
                },
                mainExecutor,
            )
        } catch (_: Exception) {
            if (pendingBindGeneration == generation) pendingBindGeneration = null
            reportBindFailure(
                generation = generation,
                code = CameraErrorCode.PROVIDER_UNAVAILABLE,
                message = "Camera provider could not be initialized",
            )
        }
    }

    private fun unbindUseCases() {
        // Invalidate provider callbacks as well as ML Kit callbacks. A
        // provider future can complete after a lifecycle stop and must not
        // bind use cases back onto the stopped owner.
        bindGeneration += 1
        pendingBindGeneration = null
        // Invalidate callbacks before detaching the use cases. A completed
        // ML Kit Task may still call its listener after CameraX unbinds; its
        // generation check must then discard the result.
        analyzer.invalidateInFlightCallbacks()
        val cameraProvider = provider
        val preview = previewUseCase
        val analysis = analysisUseCase
        if (cameraProvider != null) {
            try {
                if (preview != null || analysis != null) {
                    val useCases = listOfNotNull(preview, analysis).toTypedArray()
                    cameraProvider.unbind(*useCases)
                }
            } catch (_: Exception) {
                // A lifecycle owner may already be destroyed. The references
                // are still cleared below so a stale analyzer cannot restart.
            }
        }
        analysis?.clearAnalyzer()
        provider = null
        previewUseCase = null
        analysisUseCase = null
        camera = null
        targetRotation = null
        viewPortRetryPending = false
    }

    private fun finishUnbind() {
        if (!unbindInProgress) return
        unbindInProgress = false
        if (_captureState == CameraCaptureState.RUNNING ||
            _captureState == CameraCaptureState.STARTING
        ) {
            setCaptureState(CameraCaptureState.STOPPED)
        }

        val completions = unbindCompletions.toList()
        unbindCompletions.clear()
        completions.forEach { it() }

        if (rebindAfterUnbind) {
            rebindAfterUnbind = false
            requestBind()
        }
    }

    private fun detachHostBindings() {
        previewView?.removeOnLayoutChangeListener(previewLayoutListener)
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        previewView = null
        targetRotation = null
    }

    private fun dispatchOnMain(action: () -> Unit) {
        try {
            mainExecutor.execute(action)
        } catch (_: RuntimeException) {
            // The production main executor is process-owned and does not
            // reject work. A test/embedding executor may be shutting down;
            // complete the stop boundary rather than dropping its callback.
            action()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    private fun setCaptureState(state: CameraCaptureState) {
        if (_captureState == state) return
        _captureState = state
        onStateChanged(state)
    }

    private fun updateTargetRotation() {
        val currentPreview = previewView ?: return
        val rotation = currentPreview.display?.rotation ?: Surface.ROTATION_0
        if (targetRotation == rotation) return
        targetRotation = rotation
        // Results from a frame analyzed under the previous orientation must
        // not be checked against the new PreviewView transform.
        analyzer.invalidateInFlightCallbacks()
        previewUseCase?.setTargetRotation(rotation)
        analysisUseCase?.setTargetRotation(rotation)
    }

    private fun cleanupFailedBind(
        cameraProvider: CameraProviderAdapter?,
        preview: Preview?,
        analysis: ImageAnalysis?,
    ) {
        if (cameraProvider != null && (preview != null || analysis != null)) {
            runCatching {
                cameraProvider.unbind(*listOfNotNull(preview, analysis).toTypedArray())
            }
        }
        analysis?.clearAnalyzer()
        if (provider === cameraProvider) provider = null
        if (previewUseCase === preview) previewUseCase = null
        if (analysisUseCase === analysis) analysisUseCase = null
        camera = null
    }

    private fun reportBindFailure(
        generation: Long,
        code: CameraErrorCode,
        message: String,
    ) {
        if (isClosed || generation != bindGeneration) return
        setCaptureState(CameraCaptureState.ERROR)
        onError(CameraError(code = code, message = message))
    }
}

// These CameraX markers are Java annotations rather than Kotlin
// @RequiresOptIn markers. Lint therefore requires the explicit Android-side
// suppression at the adapter boundary; the use is intentional and isolated.
@SuppressLint("UnsafeOptInUsageError")
internal class MlKitImageAnalyzer(
    private val previewViewProvider: () -> PreviewView?,
    private val guideProvider: () -> CameraGuide,
    private val onPayload: (ScanPayload) -> Unit,
    private val onError: (CameraError) -> Unit,
    private val mainExecutor: java.util.concurrent.Executor,
    private val scannerFactory: (ScanFormat) -> BarcodeScanner = ::createBarcodeScanner,
    private val processFrame: (BarcodeScanner, ImageProxy) -> Task<List<Barcode>> =
        { scanner, imageProxy ->
            val mediaImage = imageProxy.image
                ?: throw IllegalArgumentException("ImageProxy has no media image")
            scanner.process(
                InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                ),
            )
        },
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val gate = AnalysisFrameGate()
    private val transformFactory = ImageProxyTransformFactory()
    private val lock = Any()
    private val retiredScanners = ArrayList<BarcodeScanner>()

    @Volatile
    private var expectedFormat: ScanFormat? = null

    @Volatile
    private var scanner: BarcodeScanner? = null

    @Volatile
    private var generation: Long = 0L

    /** Serializes concurrent format changes while a replacement client is built. */
    private var updateSequence: Long = 0L

    @Volatile
    private var closed = false

    fun invalidateInFlightCallbacks() {
        synchronized(lock) {
            generation += 1
        }
    }

    /**
     * Invoke [onIdle] after the current ML Kit task has released its frame.
     * This is the asynchronous half of the CameraX stop boundary; provider
     * unbind itself has already returned by the time this is registered.
     */
    fun awaitIdle(onIdle: () -> Unit) {
        gate.whenIdle {
            closeRetiredIfIdle()
            onIdle()
        }
    }

    fun updateExpectedFormat(format: ScanFormat?): Boolean {
        val sequence: Long
        synchronized(lock) {
            if (closed) return false
            if (format == expectedFormat && (format == null || scanner != null)) {
                return true
            }
            sequence = ++updateSequence
        }

        val replacement = try {
            format?.let(scannerFactory)
        } catch (_: Exception) {
            null
        }

        var closeImmediately: BarcodeScanner? = null
        var failed = false
        synchronized(lock) {
            if (closed) {
                replacement?.close()
                return false
            }
            if (sequence != updateSequence) {
                replacement?.close()
                return expectedFormat == format && (format == null || scanner != null)
            }
            if (format != null && replacement == null) {
                expectedFormat = format
                generation += 1
                closeImmediately = scanner
                scanner = null
                if (closeImmediately != null && gate.isBusy) {
                    retiredScanners += closeImmediately!!
                    closeImmediately = null
                }
                failed = true
            } else {
                expectedFormat = format
                generation += 1
                closeImmediately = scanner
                scanner = replacement
                if (closeImmediately != null && gate.isBusy) {
                    retiredScanners += closeImmediately!!
                    closeImmediately = null
                }
            }
        }
        closeImmediately?.close()
        if (failed) {
            onError(
                CameraError(
                    code = CameraErrorCode.SCANNER_UNAVAILABLE,
                    message = "Barcode analyzer could not be created",
                ),
            )
            return false
        }
        return true
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!gate.tryAcquire()) {
            imageProxy.close()
            return
        }

        val localScanner = scanner
        val localFormat = expectedFormat
        val localGeneration = generation
        if (closed || localScanner == null || localFormat == null) {
            imageProxy.close()
            gate.release()
            closeRetiredIfIdle()
            return
        }

        try {
            processFrame(localScanner, imageProxy)
                .addOnSuccessListener(mainExecutor) { barcodes ->
                    if (!closed && localGeneration == generation && localFormat == expectedFormat) {
                        deliverFirstAcceptedBarcode(barcodes, imageProxy, localFormat)
                    }
                }
                .addOnFailureListener(mainExecutor) {
                    if (!closed && localGeneration == generation) {
                        onError(
                            CameraError(
                                code = CameraErrorCode.ANALYSIS_FAILED,
                                message = "Barcode analysis failed",
                            ),
                        )
                    }
                }
                .addOnCompleteListener(mainExecutor) {
                    imageProxy.close()
                    gate.release()
                    closeRetiredIfIdle()
                }
        } catch (_: Exception) {
            imageProxy.close()
            gate.release()
            closeRetiredIfIdle()
            if (!closed && localGeneration == generation) {
                onError(
                    CameraError(
                        code = CameraErrorCode.ANALYSIS_FAILED,
                        message = "Barcode frame could not be analyzed",
                    ),
                )
            }
        }
    }

    override fun close() {
        val toClose = ArrayList<BarcodeScanner>()
        synchronized(lock) {
            if (closed) return
            closed = true
            expectedFormat = null
            generation += 1
            scanner?.let(toClose::add)
            scanner = null
            toClose += retiredScanners
            retiredScanners.clear()
            if (gate.isBusy) {
                // A format switch can make the in-flight client one of the
                // retired scanners rather than the current scanner. Defer all
                // clients until the Task completes instead of guessing which
                // one still owns the frame.
                retiredScanners += toClose
                toClose.clear()
            }
        }
        toClose.forEach(BarcodeScanner::close)
        closeRetiredIfIdle()
    }

    private fun deliverFirstAcceptedBarcode(
        barcodes: List<Barcode>,
        imageProxy: ImageProxy,
        expected: ScanFormat,
    ) {
        val preview = previewViewProvider() ?: return
        val width = preview.width
        val height = preview.height
        if (width <= 0 || height <= 0) return

        val targetTransform = preview.outputTransform ?: return
        val sourceTransform = try {
            // ML Kit's Barcode.boundingBox is expressed after the rotation
            // supplied to InputImage, so ImageProxyTransformFactory's default
            // rotation=false/crop=false coordinate space is intentional.
            transformFactory.getOutputTransform(imageProxy)
        } catch (_: Exception) {
            return
        }
        val coordinateTransform = try {
            CoordinateTransform(sourceTransform, targetTransform)
        } catch (_: Exception) {
            return
        }
        val currentGuide = guideProvider()

        // ML Kit can return more than one candidate. The first candidate whose
        // type, decoded value, and transformed bounds satisfy the guide wins.
        for (barcode in barcodes) {
            if (barcode.format.toScanFormat() != expected) continue
            val rawValue = barcode.rawValue?.takeIf { it.isNotBlank() } ?: continue
            val mappedCorners = barcode.cornerPoints
                ?.takeIf { it.size >= 4 }
                ?.let { points ->
                    floatArrayOf(
                        points[0].x.toFloat(), points[0].y.toFloat(),
                        points[1].x.toFloat(), points[1].y.toFloat(),
                        points[2].x.toFloat(), points[2].y.toFloat(),
                        points[3].x.toFloat(), points[3].y.toFloat(),
                    )
                }
                ?: barcode.boundingBox?.let { bounds ->
                    floatArrayOf(
                        bounds.left.toFloat(), bounds.top.toFloat(),
                        bounds.right.toFloat(), bounds.top.toFloat(),
                        bounds.right.toFloat(), bounds.bottom.toFloat(),
                        bounds.left.toFloat(), bounds.bottom.toFloat(),
                    )
                }
                ?: continue
            try {
                // Keep the four points rather than only mapRect's enclosing
                // box. A rotated candidate must pass the guide at every
                // transformed corner, including after a crop/rotation.
                coordinateTransform.mapPoints(mappedCorners)
            } catch (_: Exception) {
                continue
            }
            if (mappedCorners.any { !it.isFinite() }) {
                continue
            }
            val candidate = CameraQuad(
                topLeft = CameraPoint(mappedCorners[0], mappedCorners[1]),
                topRight = CameraPoint(mappedCorners[2], mappedCorners[3]),
                bottomRight = CameraPoint(mappedCorners[4], mappedCorners[5]),
                bottomLeft = CameraPoint(mappedCorners[6], mappedCorners[7]),
            )
            if (!currentGuide.accepts(candidate, width.toFloat(), height.toFloat())) continue

            // Only this sanitized, text-bearing event leaves the analyzer. It
            // is delivered on main and is never written to a log or file here.
            onPayload(
                ScanPayload(
                    value = rawValue,
                    source = InputSource.CAMERA,
                    format = expected,
                    timestampMillis = SystemClock.elapsedRealtime(),
                ),
            )
            return
        }
    }

    private fun closeRetiredIfIdle() {
        if (gate.isBusy) return
        val toClose = synchronized(lock) {
            if (gate.isBusy || retiredScanners.isEmpty()) {
                emptyList()
            } else {
                retiredScanners.toList().also { retiredScanners.clear() }
            }
        }
        toClose.forEach(BarcodeScanner::close)
    }

}

private fun Int.toScanFormat(): ScanFormat? = when (this) {
    Barcode.FORMAT_QR_CODE -> ScanFormat.QR
    Barcode.FORMAT_CODE_128 -> ScanFormat.CODE_128
    else -> null
}

private fun ScanFormat.toMlKitFormat(): Int = when (this) {
    ScanFormat.QR -> Barcode.FORMAT_QR_CODE
    ScanFormat.CODE_128 -> Barcode.FORMAT_CODE_128
}
