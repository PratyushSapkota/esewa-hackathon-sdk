package com.example.kyc_compute.library

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

fun computeBlurriness(sourceImage: Mat): Double {
    val grayImage = Mat()
    val laplacianImage = Mat()
    val meanMat = MatOfDouble()
    val stdDevMat = MatOfDouble()

    // 1. Convert source image (e.g., RGBA) to Grayscale
    Imgproc.cvtColor(sourceImage, grayImage, Imgproc.COLOR_BGR2GRAY)

    // 2. Apply Laplacian transformation
    // CvType.CV_16S avoids data overflow/clipping of negative values
    Imgproc.Laplacian(grayImage, laplacianImage, CvType.CV_64F, 3, 1.0, 0.0, Core.BORDER_DEFAULT)

    // 3. Calculate Variance
    val stdDev = stdDevMat.get(0, 0)[0]
    val variance = stdDev * stdDev

    // Free native memory
    grayImage.release()
    laplacianImage.release()
    meanMat.release()
    stdDevMat.release()

    return variance
}