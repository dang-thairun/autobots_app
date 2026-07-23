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
    fun score(bitmap: Bitmap, face: Rect): Double {
        val padX = (face.width() * 0.1f).toInt()
        val padY = (face.height() * 0.1f).toInt()
        val left = max(0, face.left - padX)
        val top = max(0, face.top - padY)
        val right = min(bitmap.width, face.right + padX)
        val bottom = min(bitmap.height, face.bottom + padY)
        val width = right - left
        val height = bottom - top
        if (width < 8 || height < 8) return 0.0

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

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
