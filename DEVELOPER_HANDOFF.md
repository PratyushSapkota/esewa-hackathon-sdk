# KYC Compute SDK Developer Handoff

This document is written for an AI agent or developer integrating the `kyc-compute` SDK into an existing Android app.

## Integration Goal

Use the `kyc-compute` AAR to add citizenship document detection, live capture feedback, quality validation, hold-to-capture behavior, and final cropped document output.

The SDK is a CameraX `ImageAnalysis.Analyzer`. The consuming app owns camera permission, CameraX preview setup, still capture setup, and UI. The SDK owns ONNX inference, document quality checks, final crop generation, and final cropped image URI creation.

## AAR File

Use this AAR linked at:

```text
https://github.com/AryanxSethi/Images/blob/main/kyc-compute-v0.2-release.aar
```

The AAR includes:

- `citizenship.onnx` under `assets/citizenship.onnx`
- SDK classes
- consumer ProGuard rules

## Required Consumer App Setup

Add the AAR to the consuming app, commonly under:

```text
app/libs/kyc-compute-release.aar
```

Add dependencies in the consumer app module:

```kotlin
dependencies {
    implementation(files("libs/kyc-compute-release.aar"))

    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("org.opencv:opencv:4.13.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
}
```

Ensure repositories exist:

```kotlin
repositories {
    google()
    mavenCentral()
}
```

Add camera permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Request camera permission at runtime before binding CameraX.

## Public SDK API

Import only these SDK types:

```kotlin
import com.example.kyc_compute.library.KycCompute
import com.example.kyc_compute.library.KycConfig
import com.example.kyc_compute.library.KycResult
import com.example.kyc_compute.library.Feedback
import com.example.kyc_compute.library.DetectedBox
```

Do not use internal implementation classes or OpenCV helpers from the SDK.

## Main Types

### KycCompute

```kotlin
class KycCompute(
    context: Context,
    scope: CoroutineScope,
    onResult: (KycResult) -> Unit,
    onFeedback: (Feedback) -> Unit = {},
    onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer
```

Use this as the CameraX `ImageAnalysis` analyzer.

### KycConfig

```kotlin
data class KycConfig(
    val brightnessMin: Double = 40.0,
    val brightnessMax: Double = 210.0,
    val sharpnessMin: Double = 6000.0,
    val glareMax: Double = 0.2,
    val boxMarginRatio: Float = 0.08f,
    val holdDurationMillis: Long = 3_000L,
    val jpegQuality: Int = 95,
)
```

Configure with:

```kotlin
kycCompute.setConfig(
    KycConfig(
        brightnessMin = 40.0,
        brightnessMax = 210.0,
        sharpnessMin = 6000.0,
        glareMax = 0.2,
        boxMarginRatio = 0.08f,
        holdDurationMillis = 3_000L,
        jpegQuality = 95,
    )
)
```

### Feedback

```kotlin
enum class Feedback {
    BLUR,
    GLARE,
    BRIGHTNESS,
    FRAMING,
    NO_OBJECT,
    COMPLETE,
    HOLD
}
```

Meanings:

- `NO_OBJECT`: no document detected.
- `FRAMING`: document detected but too close to frame edge or partially outside frame.
- `BRIGHTNESS`: cropped document is too dark or too bright.
- `BLUR`: cropped document sharpness is below threshold.
- `GLARE`: cropped document glare is above threshold.
- `HOLD`: all checks pass; user must hold steady for 3 seconds.
- `COMPLETE`: capture completed; cropped output is available.

### KycResult

```kotlin
data class KycResult(
    val brightnessScore: Double,
    val glareScore: Double,
    val sharpnessScore: Double,
    val detections: List<DetectedBox>,
    val frameWidth: Int,
    val frameHeight: Int,
    val capturedFrame: Bitmap?,
    val capturedImageUri: Uri?,
    val sourceFrameWidth: Int,
    val sourceFrameHeight: Int,
    val documentBox: DetectedBox?,
)
```

Important fields:

- `brightnessScore`: brightness score from cropped document.
- `sharpnessScore`: Laplacian variance sharpness score from cropped document. Higher is sharper.
- `glareScore`: glare ratio from cropped document.
- `detections`: live detection boxes before final capture. Usually empty in final cropped result.
- `capturedFrame`: final cropped document bitmap. Available after `COMPLETE`.
- `capturedImageUri`: library-created URI pointing to final cropped document image. Available after `COMPLETE`.
- `documentBox`: analyzer-space box used to crop high-resolution still image.

### DetectedBox

```kotlin
data class DetectedBox(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val confidence: Float,
    val className: String,
)
```

Coordinates are in analyzer frame coordinates.

## Recommended Integration Flow

1. Request camera permission.
2. Create `PreviewView` or use the app's existing camera preview surface.
3. Create CameraX `Preview`.
4. Create CameraX `ImageAnalysis` with `OUTPUT_IMAGE_FORMAT_RGBA_8888`.
5. Create CameraX `ImageCapture` for high-quality final still capture.
6. Create one `KycCompute` instance.
7. Set `KycCompute` as the `ImageAnalysis` analyzer.
8. Bind `Preview`, `ImageCapture`, and `ImageAnalysis` to lifecycle.
9. Display `Feedback` in UI.
10. Draw live boxes from `KycResult.detections` if desired.
11. When feedback becomes `COMPLETE`, call `ImageCapture.takePicture(...)` once.
12. Convert the still `ImageProxy` to a rotated `Bitmap`.
13. Pass that bitmap to `kycCompute.captureHighQualityImage(bitmap)`.
14. Use returned `KycResult.capturedImageUri` for upload/persistence.
15. Show returned `capturedFrame` in UI if preview is needed.

## CameraX Example

```kotlin
val analysisExecutor = Executors.newSingleThreadExecutor()
val computeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val preview = Preview.Builder().build().also {
    it.surfaceProvider = previewView.surfaceProvider
}

val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()

val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .build()

val kycCompute = KycCompute(
    context = context,
    scope = computeScope,
    onResult = { result ->
        // Switch to main thread before updating UI state.
        latestResult = result
    },
    onFeedback = { feedback ->
        // Switch to main thread before updating UI state.
        latestFeedback = feedback
    },
    onError = { throwable ->
        // Log or show error.
    }
)

imageAnalysis.setAnalyzer(analysisExecutor, kycCompute)

cameraProvider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    imageCapture,
    imageAnalysis,
)
```

## High-Quality Final Capture

The SDK immediately provides a cropped analyzer-frame fallback on `COMPLETE`. For better quality, the consumer should trigger `ImageCapture` once and pass the still bitmap back to the SDK.

Use a guard boolean so still capture is requested only once:

```kotlin
var isStillCaptureRequested = false

fun maybeCaptureStill(
    feedback: Feedback,
    imageCapture: ImageCapture,
    kycCompute: KycCompute,
) {
    if (feedback != Feedback.COMPLETE || isStillCaptureRequested) return

    isStillCaptureRequested = true
    imageCapture.takePicture(
        analysisExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    image.toBitmapOrNull()
                } finally {
                    image.close()
                }

                if (bitmap == null) return

                val updatedResult = try {
                    kycCompute.captureHighQualityImage(bitmap)
                } finally {
                    bitmap.recycle()
                }

                // updatedResult?.capturedImageUri is the final SDK-owned cropped URI.
                // updatedResult?.capturedFrame is the final cropped bitmap for preview.
            }

            override fun onError(exception: ImageCaptureException) {
                // Keep using the analyzer-frame fallback result already emitted by SDK.
            }
        }
    )
}
```

JPEG `ImageProxy` conversion helper:

```kotlin
private fun ImageProxy.toBitmapOrNull(): Bitmap? {
    if (format != ImageFormat.JPEG) return null

    val buffer = planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotationDegrees = imageInfo.rotationDegrees
    if (rotationDegrees == 0) return bitmap

    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
```

## UI Recommendations

Show feedback prominently:

```kotlin
Text(text = feedback.name)
```

Show quality scores only after a detection exists:

```kotlin
if (result.detections.isNotEmpty()) {
    Text("Brightness: ${result.brightnessScore}")
    Text("Sharpness: ${result.sharpnessScore}")
    Text("Glare: ${result.glareScore}")
}
```

Show final cropped image on a white background:

```kotlin
val capturedFrame = result.capturedFrame
if (feedback == Feedback.COMPLETE && capturedFrame != null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = capturedFrame.asImageBitmap(),
            contentDescription = "Captured document crop",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
```

For upload or persistence, prefer the SDK URI:

```kotlin
val finalUri = result.capturedImageUri
```

## Important Behavior Notes

- `KycCompute` stops analyzing after `COMPLETE`.
- `HOLD` lasts 3 continuous valid seconds.
- If quality drops during `HOLD`, feedback falls back and the hold timer resets.
- Scores are computed on the detected document crop, not the full frame.
- `BLUR` means `sharpnessScore < sharpnessMin`; the metric itself is sharpness, not blur.
- The ONNX model is bundled in the AAR. Consumers do not copy it manually.
- The final URI is currently a `file://` URI in app-private cache.
- If the consumer needs to share the image outside the app, wrap the file with `FileProvider` or upload it directly from app-private storage.

## Cleanup

On screen disposal or lifecycle end:

```kotlin
computeScope.cancel()
analysisExecutor.shutdown()
```

Always close still-capture `ImageProxy` objects:

```kotlin
try {
    // Decode image.
} finally {
    image.close()
}
```

Recycle temporary still bitmaps after passing them to the SDK:

```kotlin
val updatedResult = try {
    kycCompute.captureHighQualityImage(bitmap)
} finally {
    bitmap.recycle()
}
```

Do not recycle `KycResult.capturedFrame` while the UI is displaying it.

## Common Pitfalls

- Do not create a new `KycCompute` on every frame.
- Do not call `ImageCapture.takePicture(...)` repeatedly after `COMPLETE`; guard it with a boolean.
- Do not update Compose/View state directly from CameraX background callbacks; switch to the main thread.
- Do not use analyzer-frame crop as the only final output if image quality matters; call `captureHighQualityImage(...)` with a still bitmap.
- Do not forget `OUTPUT_IMAGE_FORMAT_RGBA_8888` for `ImageAnalysis`.
- Do not expect the ONNX model to be secret. It is packaged in the AAR/APK assets and can be extracted.

## Minimal Acceptance Checklist

The integration is complete when:

- Camera preview opens.
- Feedback changes through `NO_OBJECT`, `FRAMING`, quality states, `HOLD`, and `COMPLETE`.
- Boxes appear while detecting, if the consumer UI draws them.
- `KycResult.capturedFrame` is non-null after completion.
- `KycResult.capturedImageUri` is non-null after completion.
- High-quality still capture updates the final result after `COMPLETE`.
- Final image preview shows only the cropped document on a white background.
