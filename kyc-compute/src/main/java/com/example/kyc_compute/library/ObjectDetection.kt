package com.example.kyc_compute.library

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

data class DetectedBox(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val confidence: Float,
    val className: String,
)

internal class ObjectDetector(context: Context) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = environment.createSession(modelBytes)
        inputName = session.inputNames.first()
    }

    fun detect(frame: Mat): List<DetectedBox> {
        val originalWidth = frame.cols().toFloat()
        val originalHeight = frame.rows().toFloat()
        val input = preprocess(frame)

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input.values),
            longArrayOf(1, CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val output = results[0]
                val shape = (output.info as? TensorInfo)?.shape ?: longArrayOf()
                Log.d(TAG, "ONNX output shape: ${shape.contentToString()}")

                val values = flattenFloats(output.value)
                return decodeYoloOutput(values, shape, input, originalWidth, originalHeight)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun decodeYoloOutput(
        output: FloatArray,
        shape: LongArray,
        input: LetterboxInput,
        originalWidth: Float,
        originalHeight: Float,
    ): List<DetectedBox> {
        if (shape.size < 3 || output.isEmpty()) return emptyList()

        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        val attrs: Int
        val boxes: Int
        val transposed: Boolean

        if (dim1 <= 32 && dim2 > dim1) {
            attrs = dim1
            boxes = dim2
            transposed = true
        } else {
            boxes = dim1
            attrs = dim2
            transposed = false
        }

        val candidates = buildList {
            for (boxIndex in 0 until boxes) {
                val row = FloatArray(attrs) { attrIndex ->
                    if (transposed) output[attrIndex * boxes + boxIndex] else output[boxIndex * attrs + attrIndex]
                }
                decodeCandidate(row, input, originalWidth, originalHeight)?.let(::add)
            }
        }

        return nonMaxSuppression(candidates)
    }

    private fun decodeCandidate(
        row: FloatArray,
        input: LetterboxInput,
        originalWidth: Float,
        originalHeight: Float,
    ): DetectedBox? {
        if (row.size < 6) return null

        val classScoresStart = when {
            row.size == 6 -> 4
            row.size >= 7 -> 5
            else -> return null
        }

        var bestClassIndex = 0
        var bestClassScore = Float.NEGATIVE_INFINITY
        for (index in CLASS_NAMES.indices) {
            val scoreIndex = classScoresStart + index
            if (scoreIndex >= row.size) break

            if (row[scoreIndex] > bestClassScore) {
                bestClassScore = row[scoreIndex]
                bestClassIndex = index
            }
        }

        val confidence = if (classScoresStart == 5) row[4] * bestClassScore else bestClassScore
        if (confidence < CONFIDENCE_THRESHOLD) return null

        val outputScale = if (row[0] <= 2f && row[1] <= 2f && row[2] <= 2f && row[3] <= 2f) INPUT_SIZE.toFloat() else 1f
        val x = row[0] * outputScale
        val y = row[1] * outputScale
        val width = row[2] * outputScale
        val height = row[3] * outputScale

        val left: Float
        val top: Float
        val right: Float
        val bottom: Float

        if (width > x && height > y) {
            left = x
            top = y
            right = width
            bottom = height
        } else {
            left = x - width / 2f
            top = y - height / 2f
            right = x + width / 2f
            bottom = y + height / 2f
        }

        val unpaddedLeft = (left - input.padX) / input.scale
        val unpaddedTop = (top - input.padY) / input.scale
        val unpaddedRight = (right - input.padX) / input.scale
        val unpaddedBottom = (bottom - input.padY) / input.scale

        return DetectedBox(
            left = unpaddedLeft.coerceIn(0f, originalWidth),
            right = unpaddedRight.coerceIn(0f, originalWidth),
            top = unpaddedTop.coerceIn(0f, originalHeight),
            bottom = unpaddedBottom.coerceIn(0f, originalHeight),
            confidence = confidence,
            className = CLASS_NAMES[bestClassIndex],
        )
    }

    private fun nonMaxSuppression(boxes: List<DetectedBox>): List<DetectedBox> {
        val selected = mutableListOf<DetectedBox>()
        val remaining = boxes.sortedByDescending { it.confidence }.toMutableList()

        while (remaining.isNotEmpty()) {
            val current = remaining.removeAt(0)
            selected.add(current)
            remaining.removeAll { other ->
                current.className == other.className && intersectionOverUnion(current, other) > NMS_THRESHOLD
            }
        }

        return selected
    }

    private fun intersectionOverUnion(first: DetectedBox, second: DetectedBox): Float {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val firstArea = maxOf(0f, first.right - first.left) * maxOf(0f, first.bottom - first.top)
        val secondArea = maxOf(0f, second.right - second.left) * maxOf(0f, second.bottom - second.top)
        return intersection / (firstArea + secondArea - intersection + 1e-6f)
    }

    private fun preprocess(frame: Mat): LetterboxInput {
        val rgb = Mat()
        Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_RGBA2RGB)

        val scale = minOf(INPUT_SIZE.toFloat() / rgb.cols(), INPUT_SIZE.toFloat() / rgb.rows())
        val resizedWidth = (rgb.cols() * scale).toInt()
        val resizedHeight = (rgb.rows() * scale).toInt()
        val padX = (INPUT_SIZE - resizedWidth) / 2f
        val padY = (INPUT_SIZE - resizedHeight) / 2f

        val resized = Mat()
        Imgproc.resize(rgb, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()))

        val letterboxed = Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_8UC3, Scalar(114.0, 114.0, 114.0))
        val roi = letterboxed.submat(Rect(padX.toInt(), padY.toInt(), resizedWidth, resizedHeight))
        resized.copyTo(roi)
        letterboxed.convertTo(letterboxed, CvType.CV_32FC3, 1.0 / 255.0)

        val input = FloatArray(1 * CHANNELS * INPUT_SIZE * INPUT_SIZE)
        val channels = ArrayList<Mat>()
        Core.split(letterboxed, channels)

        var inputIndex = 0
        for (channel in 0 until CHANNELS) {
            val channelData = FloatArray(INPUT_SIZE * INPUT_SIZE)
            channels[channel].get(0, 0, channelData)
            for (value in channelData) {
                input[inputIndex++] = value
            }
        }

        rgb.release()
        resized.release()
        roi.release()
        letterboxed.release()
        channels.forEach { it.release() }
        return LetterboxInput(
            values = input,
            scale = scale,
            padX = padX,
            padY = padY,
        )
    }

    private fun flattenFloats(value: Any?): FloatArray {
        val values = mutableListOf<Float>()

        fun collect(item: Any?) {
            when (item) {
                is FloatArray -> values.addAll(item.asIterable())
                is Array<*> -> item.forEach(::collect)
                is Number -> values.add(item.toFloat())
            }
        }

        collect(value)
        return values.toFloatArray()
    }

    private companion object {
        const val TAG = "ObjectDetector"
        const val MODEL_ASSET = "citizenship.onnx"
        const val INPUT_SIZE = 640
        const val CHANNELS = 3
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val NMS_THRESHOLD = 0.45f
        val CLASS_NAMES = listOf("citizenship_back", "citizenship_front")
    }

    private data class LetterboxInput(
        val values: FloatArray,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )
}
