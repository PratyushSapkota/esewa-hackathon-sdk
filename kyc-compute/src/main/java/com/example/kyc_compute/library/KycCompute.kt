package com.example.kyc_compute.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class Feedback {
    BLUR, GLARE, BRIGHTNESS, FRAMING, NO_OBJECT, COMPLETE, HOLD
}

data class KycConfig(
    val brightnessMin: Double = 40.0,
    val brightnessMax: Double = 210.0,
    val sharpnessMin: Double = 6000.0,
    val glareMax: Double = 0.2,
    val boxMarginRatio: Float = 0.08f,
)

data class KycResult(
    val brightnessScore: Double,
    val glareScore: Double,
    val sharpnessScore: Double,
    val detections: List<DetectedBox> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val capturedFrame: Bitmap? = null,
    val capturedImageUri: Uri? = null,
    val sourceFrameWidth: Int = 0,
    val sourceFrameHeight: Int = 0,
    val documentBox: DetectedBox? = null,
)

class KycCompute(
    context: Context,
    private val scope: CoroutineScope,
    private val onResult: (KycResult) -> Unit,
    private val onFeedback: (Feedback) -> Unit = {},
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val appContext = context.applicationContext
    private val objectDetector = ObjectDetector(appContext)

    @Volatile
    private var config = KycConfig()

    @Volatile
    private var completedResult: KycResult? = null

    @Volatile
    private var holdStartedAtMillis: Long = 0L

    @Volatile
    private var bestHoldResult: KycResult? = null

    private val isProcessing = AtomicBoolean(false)

    fun setConfig(config: KycConfig) {
        this.config = config
    }

    fun captureHighQualityImage(image: Bitmap): KycResult? {
        val result = completedResult ?: return null
        val box = result.documentBox ?: return result
        val sourceWidth = result.sourceFrameWidth.takeIf { it > 0 } ?: return result
        val sourceHeight = result.sourceFrameHeight.takeIf { it > 0 } ?: return result

        val croppedBitmap = cropBitmap(image, box, sourceWidth, sourceHeight)
        val uri = saveCapturedBitmap(croppedBitmap)
        val updatedResult = result.copy(
            capturedFrame = croppedBitmap,
            capturedImageUri = uri,
            frameWidth = croppedBitmap.width,
            frameHeight = croppedBitmap.height,
            detections = emptyList(),
        )

        result.capturedImageUri?.deleteFileUri()
        completedResult = updatedResult
        bestHoldResult = updatedResult
        return updatedResult
    }

    internal suspend fun analyzeFrame(image: Mat): KycResult {
        completedResult?.let { return it }

        val detections = objectDetector.detect(image)
        if (detections.isEmpty()) {
            resetHold()
            onFeedback(Feedback.NO_OBJECT)
            return emptyResult(image.cols(), image.rows())
        }

        val currentConfig = config
        val documentBox = detections.bestFullyVisibleBox(image.cols(), image.rows(), currentConfig)
        if (documentBox == null) {
            resetHold()
            onFeedback(Feedback.FRAMING)
            return emptyResult(
                frameWidth = image.cols(),
                frameHeight = image.rows(),
                detections = detections,
            )
        }

        val documentImage = cropDocument(image, documentBox)
        val checks = try {
            runQualityChecks(documentImage)
        } finally {
            documentImage.release()
        }

        val qualityFeedback = checks.toFeedback(currentConfig)
        val result = KycResult(
            brightnessScore = checks.brightnessScore,
            glareScore = checks.glareScore,
            sharpnessScore = checks.sharpnessScore,
            detections = detections,
            frameWidth = image.cols(),
            frameHeight = image.rows(),
            sourceFrameWidth = image.cols(),
            sourceFrameHeight = image.rows(),
            documentBox = documentBox,
        )

        if (qualityFeedback != Feedback.COMPLETE) {
            resetHold()
            onFeedback(qualityFeedback)
            return result
        }

        updateBestHoldResult(image, documentBox, result)

        val feedback = resolveHoldFeedback(qualityFeedback)
        onFeedback(feedback)

        if (feedback != Feedback.COMPLETE) return result

        return (bestHoldResult ?: createCapturedResult(image, documentBox, result)).also { completed ->
            completedResult = completed
        }
    }

    override fun analyze(image: ImageProxy) {
        if (completedResult != null) {
            image.close()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            image.close()
            return
        }

        val rotationDegrees = image.imageInfo.rotationDegrees
        val mat = try {
            imageProxyToMat(image)
        } catch (e: Throwable) {
            image.close()
            isProcessing.set(false)
            onError(e)
            return
        }

        image.close()

        scope.launch {
            var rotatedMat: Mat? = null
            try {
                rotatedMat = rotateMat(mat, rotationDegrees)
                val result = analyzeFrame(rotatedMat)
                onResult(result)
            } catch (e: Throwable) {
                onError(e)
            } finally {
                if (rotatedMat !== null && rotatedMat !== mat) rotatedMat.release()
                mat.release()
                isProcessing.set(false)
            }
        }
    }

    private fun resolveHoldFeedback(qualityFeedback: Feedback): Feedback {
        if (qualityFeedback != Feedback.COMPLETE) {
            resetHold()
            return qualityFeedback
        }

        val now = System.currentTimeMillis()
        if (holdStartedAtMillis == 0L) {
            holdStartedAtMillis = now
        }

        return if (now - holdStartedAtMillis >= HOLD_DURATION_MILLIS) {
            Feedback.COMPLETE
        } else {
            Feedback.HOLD
        }
    }

    private fun resetHold() {
        holdStartedAtMillis = 0L
        clearBestHoldResult()
    }

    private fun updateBestHoldResult(image: Mat, documentBox: DetectedBox, result: KycResult) {
        val currentBest = bestHoldResult
        if (currentBest != null && currentBest.sharpnessScore >= result.sharpnessScore) return

        val nextBest = createCapturedResult(image, documentBox, result)
        bestHoldResult = nextBest
        currentBest?.capturedFrame?.recycle()
        currentBest?.capturedImageUri?.deleteFileUri()
    }

    private fun clearBestHoldResult() {
        val result = bestHoldResult ?: return
        if (result.capturedFrame !== completedResult?.capturedFrame) {
            result.capturedFrame?.recycle()
        }
        if (result.capturedImageUri != completedResult?.capturedImageUri) {
            result.capturedImageUri?.deleteFileUri()
        }
        bestHoldResult = null
    }

    private suspend fun runQualityChecks(image: Mat): QualityChecks = coroutineScope {
        val brightnessImage = image.clone()
        val sharpnessImage = image.clone()
        val glareImage = image.clone()

        try {
            val brightnessCheck = async { computeBrightnessScore(brightnessImage) }
            val sharpnessCheck = async { computeBlurriness(sharpnessImage) }
            val glareCheck = async { isGlare(glareImage) }

            QualityChecks(
                brightnessScore = brightnessCheck.await(),
                sharpnessScore = sharpnessCheck.await(),
                glareScore = glareCheck.await(),
            )
        } finally {
            brightnessImage.release()
            sharpnessImage.release()
            glareImage.release()
        }
    }

    private fun QualityChecks.toFeedback(config: KycConfig): Feedback {
        return when {
            brightnessScore < config.brightnessMin || brightnessScore > config.brightnessMax -> Feedback.BRIGHTNESS
            sharpnessScore < config.sharpnessMin -> Feedback.BLUR
            glareScore > config.glareMax -> Feedback.GLARE
            else -> Feedback.COMPLETE
        }
    }

    private fun List<DetectedBox>.bestFullyVisibleBox(
        frameWidth: Int,
        frameHeight: Int,
        config: KycConfig,
    ): DetectedBox? {
        val horizontalMargin = frameWidth * config.boxMarginRatio
        val verticalMargin = frameHeight * config.boxMarginRatio

        return filter { box ->
            box.left > horizontalMargin &&
                box.top > verticalMargin &&
                box.right < frameWidth - horizontalMargin &&
                box.bottom < frameHeight - verticalMargin
        }.maxByOrNull { it.confidence }
    }

    private fun cropDocument(image: Mat, box: DetectedBox): Mat {
        val left = box.left.toInt().coerceIn(0, image.cols() - 1)
        val top = box.top.toInt().coerceIn(0, image.rows() - 1)
        val right = box.right.toInt().coerceIn(left + 1, image.cols())
        val bottom = box.bottom.toInt().coerceIn(top + 1, image.rows())
        val roi = image.submat(CvRect(left, top, right - left, bottom - top))
        val cropped = roi.clone()
        roi.release()
        return cropped
    }

    private fun createCapturedResult(image: Mat, documentBox: DetectedBox, result: KycResult): KycResult {
        val documentImage = cropDocument(image, documentBox)
        return try {
            val bitmap = captureBitmap(documentImage)
            val uri = saveCapturedBitmap(bitmap)
            result.copy(
                capturedFrame = bitmap,
                capturedImageUri = uri,
                frameWidth = documentImage.cols(),
                frameHeight = documentImage.rows(),
                detections = emptyList(),
                sourceFrameWidth = result.frameWidth,
                sourceFrameHeight = result.frameHeight,
                documentBox = documentBox,
            )
        } finally {
            documentImage.release()
        }
    }

    private fun cropBitmap(bitmap: Bitmap, box: DetectedBox, sourceWidth: Int, sourceHeight: Int): Bitmap {
        val scaleX = bitmap.width.toFloat() / sourceWidth
        val scaleY = bitmap.height.toFloat() / sourceHeight
        val left = (box.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right * scaleX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom * scaleY).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun captureBitmap(image: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(image.cols(), image.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(image, bitmap)
        return bitmap
    }

    private fun saveCapturedBitmap(bitmap: Bitmap): Uri {
        val directory = File(appContext.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "document_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        return Uri.fromFile(file)
    }

    private fun Uri.deleteFileUri() {
        if (scheme == "file") File(path.orEmpty()).delete()
    }

    private fun emptyResult(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<DetectedBox> = emptyList(),
    ): KycResult {
        return KycResult(
            brightnessScore = 0.0,
            glareScore = 0.0,
            sharpnessScore = 0.0,
            detections = detections,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
    }

    private fun imageProxyToMat(image: ImageProxy): Mat {
        ensureOpenCvLoaded()

        val buffer = image.planes[0].buffer
        val mat = Mat(image.height, image.width, CvType.CV_8UC4)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        mat.put(0, 0, bytes)
        return mat
    }

    private fun rotateMat(mat: Mat, rotationDegrees: Int): Mat {
        if (rotationDegrees == 0) return mat

        val rotated = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, rotated, Core.ROTATE_180)
            270 -> Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return mat
        }
        return rotated
    }

    private data class QualityChecks(
        val brightnessScore: Double,
        val sharpnessScore: Double,
        val glareScore: Double,
    )

    private companion object {
        const val HOLD_DURATION_MILLIS = 3_000L
        const val CAPTURE_DIRECTORY = "kyc_compute_capture"
        const val JPEG_QUALITY = 95

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
}
