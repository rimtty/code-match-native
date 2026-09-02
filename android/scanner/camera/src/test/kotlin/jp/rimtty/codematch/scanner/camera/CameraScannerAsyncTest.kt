package jp.rimtty.codematch.scanner.camera

import android.Manifest
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.common.InputImage
import com.google.android.odml.image.MlImage
import com.google.android.gms.common.Feature
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import jp.rimtty.codematch.scanner.api.ScanFormat
import jp.rimtty.codematch.scanner.api.ScanPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraScannerAsyncTest {
    private lateinit var application: Application
    private lateinit var activity: Activity
    private val directExecutor = Executor { command -> command.run() }

    @Before
    fun grantCameraPermission() {
        application = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @Test
    fun `a completed provider binds once when viewport is unchanged`() {
        val future = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val scanner = newScanner(ArrayDeque(listOf(future)))
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        future.complete(provider)

        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)
        assertEquals(1, provider.boundGroups.size)
        assertEquals(1, provider.unbindCalls)
        scanner.close()
    }

    @Test
    fun `pending provider future restarts with the latest QR to Code 128 format`() {
        val firstFuture = ManualProviderFuture()
        val secondFuture = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val requestedFormats = mutableListOf<ScanFormat>()
        val futures = ArrayDeque(listOf(firstFuture, secondFuture))
        val scanner = newScanner(
            futures = futures,
            scannerFactory = { format ->
                requestedFormats += format
                FakeBarcodeScanner()
            },
        )
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        scanner.updateExpectedFormat(ScanFormat.CODE_128)

        firstFuture.complete(provider)
        assertEquals(0, provider.boundGroups.size)
        assertEquals(2, requestedFormats.size)
        assertEquals(0, futures.size)
        assertEquals(1, secondFuture.pendingListenerCount)

        secondFuture.complete(provider)
        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)
        assertEquals(1, provider.boundGroups.size)
        assertEquals(
            listOf(ScanFormat.QR, ScanFormat.CODE_128),
            requestedFormats,
        )
        scanner.close()
    }

    @Test
    fun `pending provider future retries after viewport changes`() {
        val firstFuture = ManualProviderFuture()
        val secondFuture = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val futures = ArrayDeque(listOf(firstFuture, secondFuture))
        val scanner = newScanner(futures)
        val owner = StartedLifecycleOwner()
        val preview = previewView(width = 640, height = 480)

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        preview.layout(0, 0, 800, 480)
        firstFuture.complete(provider)

        assertEquals(0, provider.boundGroups.size)
        assertEquals(1, secondFuture.pendingListenerCount)
        secondFuture.complete(provider)

        assertEquals(1, provider.boundGroups.size)
        assertEquals(800, preview.width)
        assertEquals(480, preview.height)
        scanner.close()
    }

    @Test
    fun `pending provider future retries after display rotation changes`() {
        val firstFuture = ManualProviderFuture()
        val secondFuture = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val futures = ArrayDeque(listOf(firstFuture, secondFuture))
        val scanner = newScanner(futures)
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        Shadows.shadowOf(checkNotNull(preview.display)).setRotation(Surface.ROTATION_90)
        preview.layout(0, 0, preview.width + 1, preview.height)
        firstFuture.complete(provider)

        assertEquals(0, provider.boundGroups.size)
        assertEquals(1, secondFuture.pendingListenerCount)
        secondFuture.complete(provider)

        assertEquals(1, provider.boundGroups.size)
        assertEquals(
            Surface.ROTATION_90,
            provider.boundGroups.single().viewPort!!.rotation,
        )
        scanner.close()
    }

    @Test
    fun `clearing the format stops then rebinds on the same attached host`() {
        val firstFuture = ManualProviderFuture()
        val secondFuture = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val scanner = newScanner(ArrayDeque(listOf(firstFuture, secondFuture)))
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        firstFuture.complete(provider)
        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)

        scanner.updateExpectedFormat(null)
        assertEquals(CameraCaptureState.STOPPED, scanner.captureState)

        // updateExpectedFormat(null) is a physical stop, not a host detach;
        // the same lifecycle owner and PreviewView can be used for the next
        // logical step without creating a second host.
        scanner.updateExpectedFormat(ScanFormat.QR)
        assertEquals(1, secondFuture.pendingListenerCount)
        secondFuture.complete(provider)

        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)
        assertEquals(2, provider.boundGroups.size)
        scanner.close()
    }

    @Test
    fun `lifecycle stop invalidates a pending provider callback`() {
        val future = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val scanner = newScanner(ArrayDeque(listOf(future)))
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        owner.stop()
        future.complete(provider)

        assertEquals(CameraCaptureState.STOPPED, scanner.captureState)
        assertTrue(provider.boundGroups.isEmpty())
        scanner.close()
    }

    @Test
    fun `unbind completion waits for an in-flight ML Kit task to drain`() {
        val future = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val pendingResult = TaskCompletionSource<List<Barcode>>()
        val barcodeScanner = FakeBarcodeScanner()
        var attachedPreview: PreviewView? = null
        val analyzer = MlKitImageAnalyzer(
            previewViewProvider = { attachedPreview },
            guideProvider = { CameraGuide.forFormat(ScanFormat.QR) },
            onPayload = {},
            onError = {},
            mainExecutor = directExecutor,
            scannerFactory = { barcodeScanner },
            processFrame = { _, _ -> pendingResult.task },
        )
        val dependencies = CameraScannerDependencies(
            providerFutureFactory = { future },
            scannerFactory = { barcodeScanner },
            mainExecutorFactory = { directExecutor },
            analysisExecutorFactory = { Executors.newSingleThreadExecutor() },
            analyzerFactory = { _, _, _, _, _, _ -> analyzer },
        )
        val scanner = CameraScanner(
            application,
            dependencies,
            onPayload = {},
            onStateChanged = {},
            onError = {},
        )
        val owner = StartedLifecycleOwner()
        val preview = previewView()
        attachedPreview = preview

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)
        future.complete(provider)
        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)

        val image = EmptyImageProxy()
        analyzer.analyze(image)
        var completed = false
        scanner.unbind { completed = true }

        assertTrue(provider.unbindCalls >= 2)
        assertTrue(!completed)
        assertEquals(CameraCaptureState.RUNNING, scanner.captureState)

        pendingResult.setResult(emptyList())

        assertTrue(completed)
        assertTrue(image.isClosed)
        assertEquals(CameraCaptureState.STOPPED, scanner.captureState)
        scanner.close()
    }

    @Test
    fun `analyzer creation failure reports scanner unavailable and does not bind`() {
        val future = ManualProviderFuture()
        val provider = RecordingCameraProvider()
        val errors = mutableListOf<CameraError>()
        val scanner = newScanner(
            futures = ArrayDeque(listOf(future)),
            scannerFactory = { throw IllegalStateException("model unavailable") },
            onError = { errors += it },
        )
        val owner = StartedLifecycleOwner()
        val preview = previewView()

        scanner.bind(owner, preview, expectedFormat = ScanFormat.QR)

        assertEquals(CameraCaptureState.ERROR, scanner.captureState)
        assertEquals(CameraErrorCode.SCANNER_UNAVAILABLE, errors.single().code)
        assertEquals(0, future.pendingListenerCount)
        assertTrue(provider.boundGroups.isEmpty())
        scanner.close()
    }

    @Test
    fun `stale ML Kit task callback is dropped after a format generation change`() {
        val firstScanner = FakeBarcodeScanner()
        val secondScanner = FakeBarcodeScanner()
        val pendingResult = TaskCompletionSource<List<Barcode>>()
        val scanners = ArrayDeque(listOf(firstScanner, secondScanner))
        val payloads = mutableListOf<ScanPayload>()
        val analyzer = MlKitImageAnalyzer(
            previewViewProvider = { null },
            guideProvider = { CameraGuide.forFormat(ScanFormat.QR) },
            onPayload = { payloads += it },
            onError = {},
            mainExecutor = directExecutor,
            scannerFactory = { scanners.removeFirst() },
            processFrame = { scanner, _ ->
                if (scanner === firstScanner) pendingResult.task
                else Tasks.forResult(emptyList())
            },
        )

        assertTrue(analyzer.updateExpectedFormat(ScanFormat.QR))
        val image = EmptyImageProxy()
        analyzer.analyze(image)
        assertTrue(analyzer.updateExpectedFormat(ScanFormat.CODE_128))

        pendingResult.setResult(emptyList())

        assertTrue(image.isClosed)
        assertTrue(payloads.isEmpty())
        assertEquals(1, firstScanner.closeCount)
        analyzer.close()
    }

    private fun newScanner(
        futures: ArrayDeque<ManualProviderFuture>,
        scannerFactory: (ScanFormat) -> BarcodeScanner = { FakeBarcodeScanner() },
        onError: (CameraError) -> Unit = {},
    ): CameraScanner {
        val dependencies = CameraScannerDependencies(
            providerFutureFactory = { futures.removeFirst() },
            scannerFactory = scannerFactory,
            mainExecutorFactory = { directExecutor },
            analysisExecutorFactory = { Executors.newSingleThreadExecutor() },
        )
        return CameraScanner(
            application,
            dependencies,
            onPayload = {},
            onStateChanged = {},
            onError = onError,
        )
    }

    private fun previewView(width: Int = 640, height: Int = 480): PreviewView =
        PreviewView(activity).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            activity.setContentView(this)
            layout(0, 0, width, height)
        }

    private class StartedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    private class ManualProviderFuture : CameraProviderFuture {
        private data class Listener(val callback: () -> Unit, val executor: Executor)

        private val listeners = mutableListOf<Listener>()
        private var resolvedProvider: CameraProviderAdapter? = null
        private var completed = false

        val pendingListenerCount: Int
            get() = listeners.size

        override fun addListener(listener: () -> Unit, executor: Executor) {
            if (completed) {
                executor.execute(listener)
            } else {
                listeners += Listener(listener, executor)
            }
        }

        override fun get(): CameraProviderAdapter =
            resolvedProvider ?: error("provider future is not complete")

        fun complete(provider: CameraProviderAdapter) {
            check(!completed)
            resolvedProvider = provider
            completed = true
            val callbacks = listeners.toList()
            listeners.clear()
            callbacks.forEach { listener ->
                listener.executor.execute(listener.callback)
            }
        }
    }

    private class RecordingCameraProvider : CameraProviderAdapter {
        val boundGroups = mutableListOf<UseCaseGroup>()
        var unbindCalls: Int = 0

        override fun hasBackCamera(): Boolean = true

        override fun unbind(vararg useCases: UseCase) {
            unbindCalls += 1
        }

        override fun bindToLifecycle(
            owner: LifecycleOwner,
            useCaseGroup: UseCaseGroup,
        ): Camera {
            boundGroups += useCaseGroup
            return cameraProxy()
        }
    }

    private class FakeBarcodeScanner : BarcodeScanner {
        var closeCount: Int = 0

        override fun getDetectorType(): Int = Detector.TYPE_BARCODE_SCANNING

        override fun getOptionalFeatures(): Array<Feature> = emptyArray()

        override fun process(image: Bitmap, rotationDegrees: Int): Task<List<Barcode>> =
            Tasks.forResult(emptyList())

        override fun process(image: Image, rotationDegrees: Int): Task<List<Barcode>> =
            Tasks.forResult(emptyList())

        override fun process(
            image: Image,
            rotationDegrees: Int,
            matrix: Matrix,
        ): Task<List<Barcode>> = Tasks.forResult(emptyList())

        override fun process(
            image: ByteBuffer,
            width: Int,
            height: Int,
            rotationDegrees: Int,
            imageFormat: Int,
        ): Task<List<Barcode>> = Tasks.forResult(emptyList())

        override fun process(image: InputImage): Task<List<Barcode>> =
            Tasks.forResult(emptyList())

        override fun process(image: MlImage): Task<List<Barcode>> =
            Tasks.forResult(emptyList())

        override fun close() {
            closeCount += 1
        }
    }

}

private fun cameraProxy(): Camera =
    java.lang.reflect.Proxy.newProxyInstance(
        Camera::class.java.classLoader,
        arrayOf(Camera::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    } as Camera

private class EmptyImageProxy : ImageProxy {
    var isClosed: Boolean = false

    override fun close() {
        isClosed = true
    }

    override fun getCropRect(): Rect = Rect()

    override fun setCropRect(rect: Rect?) = Unit

    override fun getFormat(): Int = 0

    override fun getHeight(): Int = 0

    override fun getWidth(): Int = 0

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

    override fun getImageInfo(): ImageInfo = error("not needed by test processor")

    override fun getImage(): Image? = null
}
