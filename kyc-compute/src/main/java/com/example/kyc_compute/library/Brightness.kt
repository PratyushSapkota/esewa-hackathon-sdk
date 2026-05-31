package com.example.kyc_compute.library

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

internal fun computeBrightnessScore(imageMat: Mat): Double {
    val lab = Mat()
    val channels = ArrayList<Mat>()
    var meanBrightness = 0.0

    try {
        // 1. Convert to LAB color space
        Imgproc.cvtColor(imageMat, lab, Imgproc.COLOR_BGR2Lab)

        // 2. Split the Mat into 3 separate channels (L, A, B)
        Core.split(lab, channels)

        if (channels.isNotEmpty()) {
            val lChannel = channels[0] // Reference the L channel

            // 3. Calculate mean brightness of the L channel
            val meanScalar: Scalar = Core.mean(lChannel)
            meanBrightness = meanScalar.`val`[0] // Extract the actual number
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        // Safe Cleanup: Release the parent LAB matrix
        lab.release()
        // Release all split channels safely exactly once
        for (m in channels) {
            m.release()
        }
    }

    return meanBrightness
}
