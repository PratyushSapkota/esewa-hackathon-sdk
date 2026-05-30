package com.example.kyc_compute.library

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

fun isGlare(imageMat: Mat): Double {
    val lookupTable = Mat(1, 256, CvType.CV_8U)
    val grayMat = Mat()
    val gammaCorrected = Mat()
    val thresholded = Mat()

    try {
        // 1. Ensure single-channel input
        when (imageMat.channels()) {
            1 -> imageMat.copyTo(grayMat)
            3 -> Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGRA2GRAY)
            else -> throw IllegalArgumentException("Unsupported channel count: ${imageMat.channels()}")
        }

        // 2. Build lookup table (mirrors: pow(i/255.0, 10) * 255.0)
        val tableData = ByteArray(256) { i ->
            (Math.pow(i / 255.0, 10.0) * 255.0)
                .coerceIn(0.0, 255.0)
                .toInt()
                .toByte()
        }
        lookupTable.put(0, 0, tableData)

        // 3. Apply gamma correction via LUT
        Core.LUT(grayMat, lookupTable, gammaCorrected)

        // 4. Apply Otsu threshold
        Imgproc.threshold(
            gammaCorrected, thresholded,
            0.0, 255.0,
            Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
        )

        // 5. Glare score = fraction of white (glare) pixels
        val whitePixels = Core.countNonZero(thresholded)
        return whitePixels.toDouble() / thresholded.total().toDouble()

    } finally {
        lookupTable.release()
        grayMat.release()
        gammaCorrected.release()
        thresholded.release()
    }
}