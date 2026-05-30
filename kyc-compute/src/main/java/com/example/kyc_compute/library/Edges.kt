package com.example.kyc_compute.library

import android.graphics.PointF
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

fun computeCorners(image: Mat): Int {
    val corners = detectFourCorneredContour(image) ?: return 0
//    return if (corners != null && corners.size == 4) 100 else 0
    return corners.size
}


fun detectFourCorneredContour(image: Mat): List<PointF>? {

    val gray = Mat()
    Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)

    // Bilateral Filter for surface smoothing while preserving edges
    val smooth = Mat()
    Imgproc.bilateralFilter(gray, smooth, 9, 75.0, 75.0)

    // Canny with lower thresholds to allow "soft" edges
    val edges = Mat()
    Imgproc.Canny(smooth, edges, 30.0, 100.0)

    // Morphological Closing to fuse fragmented/soft edges
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(
        edges,
        contours,
        hierarchy,
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )

    var foundPoints: List<PointF>? = null
    val imgArea = image.width() * image.height().toDouble()

    // Filter and sort by area
    val sortedContours = contours.filter { Imgproc.contourArea(it) > imgArea * 0.10 }
        .sortedByDescending { Imgproc.contourArea(it) }

    for (contour in sortedContours) {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()

        // Try to find a 4-point polygon
        // Relaxed epsilon to handle slightly soft/curved corners
        for (i in 1..15) {
            val epsilon = (0.01 * i) * Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

            if (approx.total().toInt() == 4) {
                val pts = approx.toArray()
                if (Imgproc.isContourConvex(MatOfPoint(*pts))) {
                    foundPoints = sortPoints(pts).map { PointF(it.x.toFloat(), it.y.toFloat()) }
                    break
                }
            }
        }
        if (foundPoints != null) break
    }

    image.release()
    gray.release()
    smooth.release()
    edges.release()
    hierarchy.release()
    kernel.release()

    return foundPoints
}

private fun sortPoints(pts: Array<Point>): Array<Point> {
    if (pts.size != 4) return pts
    val cx = pts.map { it.x }.average()
    val cy = pts.map { it.y }.average()
    val sorted = pts.sortedBy { Math.atan2(it.y - cy, it.x - cx) }
    val tlIndex = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
    return Array(4) { i -> sorted[(tlIndex + i) % 4] }
}