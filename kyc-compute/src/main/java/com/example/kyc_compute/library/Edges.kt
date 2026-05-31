package com.example.kyc_compute.library

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal data class EdgeResult(val cornerCount: Int)

internal fun detectEdgesAndCountCorners(sourceMat: Mat): EdgeResult {
    val gray = Mat()
    Imgproc.cvtColor(sourceMat, gray, Imgproc.COLOR_RGBA2GRAY)

    val blurred = Mat()
    Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

    val edges = Mat()
    Imgproc.Canny(blurred, edges, 50.0, 150.0)

    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    var totalCorners = 0

    for (contour in contours) {
        if (Imgproc.contourArea(contour) < 100.0) continue

        val contour2f = MatOfPoint2f()
        contour.convertTo(contour2f, CvType.CV_32F)

        val approx = MatOfPoint2f()
        val peri = Imgproc.arcLength(contour2f, true)
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

        totalCorners += approx.toList().size

        contour2f.release()
        approx.release()
    }

    edges.release()
    hierarchy.release()
    contours.forEach { it.release() }
    gray.release()
    blurred.release()

    return EdgeResult(totalCorners)
}
