package com.example.kyc_compute.library

import android.graphics.PointF
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.opencv.core.Mat

enum class Feedback{
    BLUR, GLARE, BRIGHTNESS, FRAMING
}

class KycCompute {
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

}