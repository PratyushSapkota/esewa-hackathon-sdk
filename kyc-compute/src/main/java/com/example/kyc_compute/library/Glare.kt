package com.example.kyc_compute.library

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

fun isGlare(imageMat: Mat, intensityThreshold: Int = 240, glare_area_ratio: Double = 0.015): Boolean {
    val BWmat = Mat()
    val binaryMask = Mat()

    try {
        // Convert to grayscale
        Imgproc.cvtColor(imageMat, BWmat, Imgproc.COLOR_BGR2GRAY)

        // Create binary mask: white pixels (1) where brightness >= intensityThreshold
        Imgproc.threshold(BWmat, binaryMask, intensityThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)

        // Count non-zero pixels (glare pixels)
        val glarePixels = Core.countNonZero(binaryMask)
        val totalPixels = imageMat.rows() * imageMat.cols()

        // Calculate glare area ratio
        val detectedGlareRatio = glarePixels.toDouble() / totalPixels

        // Return true if glare ratio exceeds threshold
        return detectedGlareRatio > glare_area_ratio
    } finally {
        BWmat.release()
        binaryMask.release()
    }
}