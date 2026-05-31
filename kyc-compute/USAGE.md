# KYC Compute Usage

`kyc-compute` provides a CameraX analyzer for document capture quality checks. It detects citizenship document sides with an ONNX model, validates framing and image quality, waits for a stable hold period, and outputs a cropped captured document image.

## What It Does

- Detects `citizenship_back` and `citizenship_front` objects.
- Draws/returns detection boxes while scanning.
- Checks brightness, sharpness, glare, and framing on the detected document crop.
- Emits feedback for UI guidance.
- Uses a 3-second `HOLD` state before completing.
- Saves a final cropped document image from the library module and returns its `Uri`.
- Supports replacing the analyzer-frame crop with a higher-quality CameraX still capture.

## Add The Library

If this module is included in the same Android project:

```kotlin
dependencies {
    implementation(project(":kyc-compute"))
}
```

Required repositories:

```kotlin
repositories {
    google()
    mavenCentral()
}
```

Required permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

The library depends on:

- CameraX
- OpenCV
- ONNX Runtime Android
- bundled `citizenship.onnx` asset

## Main API

Create a `KycCompute` analyzer:

```kotlin
val kycCompute = KycCompute(
    context = context,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    onResult = { result ->
        // Receives detection boxes, scores, and final capture output.
    },
    onFeedback = { feedback ->
        // Receives current user guidance state.
    },
    onError = { throwable ->
        // Handle analysis/runtime errors.
    }
)
```

Set it as a CameraX analyzer:

```kotlin
imageAnalysis.setAnalyzer(analysisExecutor, kycCompute)
```

## Inputs

### Constructor Inputs

```kotlin
KycCompute(
    context: Context,
    scope: CoroutineScope,
    onResult: (KycResult) -> Unit,
    onFeedback: (Feedback) -> Unit = {},
    onError: (Throwable) -> Unit
)
```

- `context`: Android context used for assets, cache files, OpenCV, and ONNX setup.
- `scope`: Coroutine scope used for frame processing.
- `onResult`: Called with current scores, detections, and final captured output.
- `onFeedback`: Called with current capture guidance.
- `onError`: Called if processing fails.

### Configuration

Use `setConfig(...)` to customize capture rules:

```kotlin
kycCompute.setConfig(
    KycConfig(
        brightnessMin = 40.0,
        brightnessMax = 210.0,
        sharpnessMin = 15000.0,
        glareMax = 0.3,
        boxMarginRatio = 0.08f,
        holdDurationMillis = 3_000L,
        jpegQuality = 95,
    )
)
```

Fields:

- `brightnessMin`: Minimum allowed brightness score.
- `brightnessMax`: Maximum allowed brightness score.
- `sharpnessMin`: Minimum allowed sharpness score. If below this value, feedback is `BLUR`.
- `glareMax`: Maximum allowed glare score.
- `boxMarginRatio`: Required spacing between detected box and frame edge. `0.08f` means 8% margin.
- `holdDurationMillis`: Time the document must remain valid before capture completes.
- `jpegQuality`: JPEG compression quality for saved captured images.

### High-Quality Still Input

After `Feedback.COMPLETE`, optionally pass a high-resolution still bitmap to the library:

```kotlin
val updatedScore = kycCompute.captureHighQualityImage(bitmap)
```

This maps the completed analyzer box onto the still image, crops it, saves it from the library cache, and returns an updated `KycResult`.

## Outputs

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

Meaning:

- `NO_OBJECT`: No document was detected.
- `FRAMING`: A document was detected, but it is too close to or outside the frame edge.
- `BRIGHTNESS`: Document crop is too dark or too bright.
- `BLUR`: Document crop is not sharp enough.
- `GLARE`: Document crop has too much glare.
- `HOLD`: Everything is valid; keep the device steady for 3 seconds.
- `COMPLETE`: Capture is complete and final output is available.

### KycResult

`onResult` returns:

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

- `brightnessScore`: Brightness score computed from the detected document crop.
- `sharpnessScore`: Laplacian variance sharpness score computed from the detected document crop.
- `glareScore`: Glare ratio computed from the detected document crop.
- `detections`: Detected boxes during live analysis.
- `capturedFrame`: Final cropped document bitmap. Available on completion.
- `capturedImageUri`: Library-provided `Uri` for the saved cropped document image. Available on completion.
- `documentBox`: Analyzer-space box used for final cropping.

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

Coordinates are in the analyzer frame coordinate space.

## CameraX Integration Example

```kotlin
val analysisExecutor = Executors.newSingleThreadExecutor()
val computeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val preview = Preview.Builder().build()
preview.surfaceProvider = previewView.surfaceProvider

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
        // Update UI state on the main thread.
    },
    onFeedback = { feedback ->
        // Update guidance text on the main thread.
    },
    onError = { error ->
        // Show or log error.
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

## High-Quality Final Capture Flow

The analyzer frame is good for detection and scoring, but final image quality is better when using `ImageCapture`.

Recommended flow:

1. Use `KycCompute` as the `ImageAnalysis` analyzer.
2. Wait until `onFeedback` returns `Feedback.COMPLETE`.
3. Trigger `ImageCapture.takePicture(...)` once.
4. Convert the captured `ImageProxy` to a correctly rotated `Bitmap`.
5. Pass that bitmap to `kycCompute.captureHighQualityImage(bitmap)`.
6. Use the returned `KycResult.capturedImageUri` or `capturedFrame`.

Example:

```kotlin
imageCapture.takePicture(
    analysisExecutor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = try {
                image.toBitmapOrNull()
            } finally {
                image.close()
            }

            if (bitmap != null) {
                val updatedScore = try {
                    kycCompute.captureHighQualityImage(bitmap)
                } finally {
                    bitmap.recycle()
                }

                // updatedScore?.capturedImageUri is the final library-provided URI.
            }
        }

        override fun onError(exception: ImageCaptureException) {
            // Fallback to the analyzer-frame captured output already in KycResult.
        }
    }
)
```

Helper for JPEG `ImageProxy` conversion:

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

## UI Usage Example

Show current feedback:

```kotlin
Text(text = feedback.name)
```

Show scores when a document is detected:

```kotlin
if (score != null && score.detections.isNotEmpty()) {
    Text("Brightness: ${score.brightnessScore}")
    Text("Sharpness: ${score.sharpnessScore}")
    Text("Glare: ${score.glareScore}")
}
```

Show final cropped document preview:

```kotlin
val capturedFrame = score?.capturedFrame
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

Use the URI for upload or persistence:

```kotlin
val uri = score?.capturedImageUri
```

The URI points to an app-private cache file created by the library.

## Runtime Behavior

The capture flow is:

1. `NO_OBJECT` until a citizenship document is detected.
2. `FRAMING` until the detected box has enough spacing from frame edges.
3. `BRIGHTNESS`, `BLUR`, or `GLARE` if quality checks fail.
4. `HOLD` once all checks pass.
5. If checks stay valid for 3 seconds, `COMPLETE`.
6. On completion, analyzer processing stops and a final cropped image is available.

During `HOLD`, the library keeps the sharpest valid candidate and clears it if quality drops.

## Lifecycle Notes

- Run CameraX analysis on a background executor.
- Pass a background `CoroutineScope` to `KycCompute`.
- Close every `ImageProxy` received from CameraX still capture.
- Shut down executors and cancel scopes when the screen is disposed.
- The library stores final cropped images in app cache, under `kyc_compute_capture`.

## Limitations

- Hold duration is fixed at 3 seconds.
- `Feedback.BLUR` means `sharpnessScore < sharpnessMin`; the score is actually sharpness, not blur.
- Final still-crop quality depends on how well analyzer coordinates map to the high-resolution still image.
- `capturedImageUri` is a file URI in app-private cache, not a public `FileProvider` URI.
