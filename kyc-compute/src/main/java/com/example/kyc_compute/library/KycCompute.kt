package com.example.kyc_compute.library

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

enum class Feedback{
    BLUR, GLARE, BRIGHTNESS, FRAMING
}

class KycCompute (
    private val scope: CoroutineScope,
    private val onScore: (DocumentScore) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {
    data class DocumentScore(
        val edgeScore: Int,
        val brightnessScore: Double,
        val isGlare: Boolean,
        val readabilityScore: Int,
        val averageScore: Int,
        val corners: Int,
        val blurScore: Double
    ) {
        fun checkThreshold() {

        }
    }


    suspend fun analyzeFrame(image: Mat): DocumentScore{
        return runPreliminaryChecks(image)
    }

    suspend fun runPreliminaryChecks(image: Mat): DocumentScore = coroutineScope{
        val brightnessCheck = async { computeBrightnessScore(image) }
        val blurCheck = async { computeBlurriness(image) }
        val cornersCheck = async { computeCorners(image) }
        val glareCheck = async { isGlare(image, 0, 0.0) }

        val brightnessScore = brightnessCheck.await()
        val blurScore = blurCheck.await()
        val corners = cornersCheck.await()
        val isGlare = glareCheck.await()

        return@coroutineScope DocumentScore(
            0,
            brightnessScore,
            isGlare = isGlare,
            readabilityScore = 0,
            averageScore = 0,
            corners = corners,
            blurScore = blurScore
        )
    }

    private val isProcessing = AtomicBoolean(false)

    companion object {
        @Volatile
        private var isOpenCvLoaded = false

        private fun ensureOpenCvLoaded() {
            if (isOpenCvLoaded) return

            synchronized(this) {
                if (isOpenCvLoaded) return
                check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
                isOpenCvLoaded = true
            }
        }
    }

    override fun analyze(p0: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            p0.close()
            return
        }

        val mat = try {
            imageProxyToMat(p0)
        } catch (e: Throwable) {
            p0.close()
            isProcessing.set(false)
            onError(e)
            return
        }

        p0.close()

        scope.launch {
            try {
                val score = analyzeFrame(mat)
                onScore(score)
            } catch (e: Throwable) {
                onError(e)
            } finally {
                mat.release()
                isProcessing.set(false)
            }
        }
    }


    fun imageProxyToMat(image: ImageProxy): Mat {
        ensureOpenCvLoaded()

        val buffer = image.planes[0].buffer

        val mat = Mat(
            image.height,
            image.width,
            CvType.CV_8UC4
        )

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        mat.put(0, 0, bytes)

        return mat
    }
}
