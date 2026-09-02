# Camera scanner adapter

`scanner:camera` is the production camera input boundary for CodeMatch. It
binds a back-camera `Preview` and `ImageAnalysis` use case to a lifecycle,
uses the bundled ML Kit barcode model, and emits only `ScanPayload` values
whose decoded QR/Code 128 bounds are inside the displayed guide.

## Integration

Register the module in the Android settings file and add it to the app's
release/runtime graph. The app owns the scanner instance so it can forward
payloads to `ScanUiAction.ScanReceived`:

```kotlin
val cameraScanner = remember(context) {
    CameraScanner(
        context = context,
        onPayload = { payload -> viewModel.onAction(ScanUiAction.ScanReceived(payload)) },
        onStateChanged = { state -> /* map RUNNING/STOPPED to the UI state */ },
        onError = { error -> /* show a payload-free camera error */ },
    )
}

CameraScannerHost(
    cameraScanner = cameraScanner,
    expectedFormat = scanState.expectedFormat,
    accessibilityDescription = stringResource(R.string.scan_camera_accessibility),
    requestPermission = false,
    onPermissionDenied = viewModel::onCameraPermissionDenied,
)
```

Set `expectedFormat = null` when the camera should not be active. The
production app owns permission state and requests, then passes
`requestPermission = false`; the host's default permission launcher remains
available for standalone embedding. The host draws the default QR or Code 128
guide and maps preview taps to CameraX AF/AE metering points. Use `CameraGuide`
when the feature has a custom guide overlay.

The module resolves these dependencies from the root version catalog:

- `androidx.camera:camera-camera2:1.6.2`
- `androidx.camera:camera-core:1.6.2`
- `androidx.camera:camera-lifecycle:1.6.2`
- `androidx.camera:camera-view:1.6.2`
- `com.google.mlkit:barcode-scanning:17.3.0` (bundled model)

ImageAnalysis uses `STRATEGY_KEEP_ONLY_LATEST` and an atomic in-flight gate.
Frames are closed immediately when dropped, and the accepted image is closed
after ML Kit completes. No image is copied or stored, and this module has no
network permission or network client.
