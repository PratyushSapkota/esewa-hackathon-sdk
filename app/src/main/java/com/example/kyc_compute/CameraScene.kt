package com.example.kyc_compute

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.kyc_compute.library.Feedback
import com.example.kyc_compute.library.KycCompute
import com.example.kyc_compute.library.KycResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

@Composable
fun CameraScreen() {

    var feedback by remember {
        mutableStateOf<Feedback>(Feedback.NO_OBJECT)
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val computeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var analyzer by remember { mutableStateOf<KycCompute?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isStillCaptureRequested by remember { mutableStateOf(false) }
    var documentScore by remember { mutableStateOf<KycResult?>(null) }
    var computeError by remember { mutableStateOf<Throwable?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            computeScope.cancel()
            analysisExecutor.shutdown()
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        return
    }

    LaunchedEffect(feedback, imageCapture, analyzer, isStillCaptureRequested) {
        val capture = imageCapture
        val currentAnalyzer = analyzer
        if (feedback != Feedback.COMPLETE || capture == null || currentAnalyzer == null || isStillCaptureRequested) {
            return@LaunchedEffect
        }

        isStillCaptureRequested = true
        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toBitmapOrNull()
                    } finally {
                        image.close()
                    }

                    if (bitmap == null) return

                    val updatedScore = try {
                        currentAnalyzer.captureHighQualityImage(bitmap)
                    } finally {
                        bitmap.recycle()
                    }

                    ContextCompat.getMainExecutor(context).execute {
                        if (updatedScore != null) {
                            computeError = null
                            documentScore = updatedScore
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    ContextCompat.getMainExecutor(context).execute {
                        computeError = exception
                    }
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->

                val previewView = PreviewView(ctx)

                val cameraProviderFuture =
                    ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({

                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()

                    preview.surfaceProvider =
                        previewView.surfaceProvider

                    val cameraSelector =
                        CameraSelector.DEFAULT_BACK_CAMERA

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    imageCapture = capture

                    val computeAnalyzer = KycCompute(
                        context = ctx,
                        scope = computeScope,
                        onResult = { score ->
                            ContextCompat.getMainExecutor(ctx).execute {
                                computeError = null
                                documentScore = score
                            }
                        },
                        onFeedback = { nextFeedback ->
                            ContextCompat.getMainExecutor(ctx).execute {
                                feedback = nextFeedback
                            }
                        },
                        onError = { error ->
                            ContextCompat.getMainExecutor(ctx).execute {
                                computeError = error
                            }
                        }
                    )
                    analyzer = computeAnalyzer

                    imageAnalysis.setAnalyzer(
                        analysisExecutor,
                        computeAnalyzer
                    )

                    cameraProvider.unbindAll()

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        capture,
                        imageAnalysis
                    )

                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        val capturedFrame = documentScore?.capturedFrame
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

        DetectionBoxOverlay(
            score = documentScore,
            modifier = Modifier.fillMaxSize()
        )

        DocumentScoreOverlay(
            score = documentScore,
            feedback = feedback,
            error = computeError,
            modifier = if (feedback == Feedback.COMPLETE) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            } else {
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            }
        )
    }
}

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

@Composable
private fun DetectionBoxOverlay(
    score: KycResult?,
    modifier: Modifier = Modifier,
) {
    if (score == null || score.frameWidth == 0 || score.frameHeight == 0) return

    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            isAntiAlias = true
        }
    }
    val labelBackgroundPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(190, 20, 20, 20)
            style = Paint.Style.FILL
        }
    }

    Canvas(modifier = modifier) {
        val scale = maxOf(size.width / score.frameWidth, size.height / score.frameHeight)
        val offsetX = (size.width - score.frameWidth * scale) / 2f
        val offsetY = (size.height - score.frameHeight * scale) / 2f

        score.detections.forEach { detection ->
            val left = detection.left * scale + offsetX
            val top = detection.top * scale + offsetY
            val right = detection.right * scale + offsetX
            val bottom = detection.bottom * scale + offsetY
            val boxColor = if (detection.className == "citizenship-front") {
                Color(0xFF00E5FF)
            } else {
                Color(0xFFFFD54F)
            }

            drawRect(
                color = boxColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = 4.dp.toPx())
            )

            val label = "${detection.className} ${"%.2f".format(detection.confidence)}"
            val labelY = maxOf(38f, top - 10f)
            val labelWidth = labelPaint.measureText(label) + 18f
            drawContext.canvas.nativeCanvas.drawRect(
                left,
                labelY - 36f,
                left + labelWidth,
                labelY + 8f,
                labelBackgroundPaint
            )
            drawContext.canvas.nativeCanvas.drawText(label, left + 9f, labelY, labelPaint)
        }
    }
}

@Composable
private fun DocumentScoreOverlay(
    score: KycResult?,
    feedback: Feedback,
    error: Throwable?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = 260.dp)
            .background(Color.Black.copy(alpha = 0.62f), MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Feedback",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )

        if (error != null) {
            Text(
                text = "Error: ${error.message ?: error::class.java.simpleName}",
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        ScoreLine("Status", feedback.name)

        if (score != null && score.detections.isNotEmpty()) {
            ScoreLine("Brightness", "%.1f".format(score.brightnessScore))
            ScoreLine("Sharpness", "%.1f".format(score.sharpnessScore))
            ScoreLine("Glare", "%.3f".format(score.glareScore))
        }
    }
}

@Composable
private fun ScoreLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        color = Color.White,
        style = MaterialTheme.typography.bodySmall
    )
}
