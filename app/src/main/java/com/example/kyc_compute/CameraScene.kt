package com.example.kyc_compute

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.kyc_compute.library.KycCompute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

@Composable
fun CameraScreen() {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val computeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var documentScore by remember { mutableStateOf<KycCompute.DocumentScore?>(null) }
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

                    imageAnalysis.setAnalyzer(
                        analysisExecutor,
                        KycCompute(
                            scope = computeScope,
                            onScore = { score ->
                                ContextCompat.getMainExecutor(ctx).execute {
                                    computeError = null
                                    documentScore = score
                                }
                            },
                            onError = { error ->
                                ContextCompat.getMainExecutor(ctx).execute {
                                    computeError = error
                                }
                            }
                        )
                    )

                    cameraProvider.unbindAll()

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        DocumentScoreOverlay(
            score = documentScore,
            error = computeError,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )
    }
}

@Composable
private fun DocumentScoreOverlay(
    score: KycCompute.DocumentScore?,
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
            text = "Document score",
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

        if (score == null) {
            Text(
                text = "Waiting for frame...",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        ScoreLine("Average", score.averageScore.toString())
        ScoreLine("Brightness", "%.1f".format(score.brightnessScore))
        ScoreLine("Blur", "%.1f".format(score.blurScore))
        ScoreLine("Corners", score.corners.toString())
        ScoreLine("Glare", if (score.isGlare) "Yes" else "No")
        ScoreLine("Readability", score.readabilityScore.toString())
        ScoreLine("Edges", score.edgeScore.toString())
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
