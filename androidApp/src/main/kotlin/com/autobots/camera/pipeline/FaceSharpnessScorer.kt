package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Laplacian-variance sharpness on a face ROI (higher = sharper).
 */
object FaceSharpnessScorer {
    private const val NORMALIZED_FACE_SIZE = 128

    /**
     * Score on a fixed-size face crop so thresholds match across 1080p and 4K.
     */
    fun scoreNormalized(bitmap: Bitmap, face: Rect, targetSize: Int = NORMALIZED_FACE_SIZE): Double {
        val padX = (face.width() * 0.1f).toInt()
        val padY = (face.height() * 0.1f).toInt()
        val left = max(0, face.left - padX)
        val top = max(0, face.top - padY)
        val right = min(bitmap.width, face.right + padX)
        val bottom = min(bitmap.height, face.bottom + padY)
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) return 0.0

        val crop = try {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (_: Throwable) {
            return 0.0
        }
        return try {
            val longest = max(width, height)
            val scale = targetSize.toFloat() / longest
            val scaledW = max(8, (width * scale).toInt())
            val scaledH = max(8, (height * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(crop, scaledW, scaledH, true)
            crop.recycle()
            try {
                scorePixels(scaled)
            } finally {
                scaled.recycle()
            }
        } catch (_: Throwable) {
            crop.recycle()
            0.0
        }
    }

    private fun scorePixels(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return 0.0

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = luminance(pixels[y * width + x])
                val lap = abs(
                    4 * center -
                        luminance(pixels[(y - 1) * width + x]) -
                        luminance(pixels[(y + 1) * width + x]) -
                        luminance(pixels[y * width + (x - 1)]) -
                        luminance(pixels[y * width + (x + 1)]),
                )
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
