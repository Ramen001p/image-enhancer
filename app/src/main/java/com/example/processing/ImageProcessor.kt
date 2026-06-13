package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageProcessor {

    /**
     * Applies a 3x3 sharpening convolution matrix in a background thread.
     * Higher amount means stronger details, effectively removing blurness.
     */
    suspend fun sharpen(src: Bitmap, amount: Float): Bitmap = withContext(Dispatchers.Default) {
        if (amount <= 0.05f) return@withContext src
        
        val width = src.width
        val height = src.height
        val srcPixels = IntArray(width * height)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        
        val destPixels = IntArray(width * height)
        
        // Dynamic kernel values depending on amount
        // Center weight expands while edge weights shrink
        val factor = amount.coerceIn(0f, 1f)
        val edgeWeight = -factor
        val centerWeight = 1f - (4f * edgeWeight)
        
        for (y in 1 until height - 1) {
            val offset = y * width
            for (x in 1 until width - 1) {
                val idx = offset + x
                
                val c = srcPixels[idx]
                val n = srcPixels[idx - width]
                val s = srcPixels[idx + width]
                val w = srcPixels[idx - 1]
                val e = srcPixels[idx + 1]
                
                val rC = (c shr 16) and 0xFF
                val gC = (c shr 8) and 0xFF
                val bC = c and 0xFF
                
                val rSum = rC * centerWeight + (
                    ((n shr 16) and 0xFF) + 
                    ((s shr 16) and 0xFF) + 
                    ((w shr 16) and 0xFF) + 
                    ((e shr 16) and 0xFF)
                ) * edgeWeight
                
                val gSum = gC * centerWeight + (
                    ((n shr 8) and 0xFF) + 
                    ((s shr 8) and 0xFF) + 
                    ((w shr 8) and 0xFF) + 
                    ((e shr 8) and 0xFF)
                ) * edgeWeight
                
                val bSum = bC * centerWeight + (
                    (n and 0xFF) + 
                    (s and 0xFF) + 
                    (w and 0xFF) + 
                    (e and 0xFF)
                ) * edgeWeight
                
                val r = rSum.toInt().coerceIn(0, 255)
                val g = gSum.toInt().coerceIn(0, 255)
                val b = bSum.toInt().coerceIn(0, 255)
                
                destPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        // Copy margins
        for (x in 0 until width) {
            destPixels[x] = srcPixels[x]
            destPixels[(height - 1) * width + x] = srcPixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            destPixels[y * width] = srcPixels[y * width]
            destPixels[y * width + (width - 1)] = srcPixels[y * width + (width - 1)]
        }
        
        val res = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        res.setPixels(destPixels, 0, width, 0, 0, width, height)
        return@withContext res
    }

    /**
     * Applies a 3x3 noise reduction low-pass smoothing filter in a background thread.
     */
    suspend fun denoise(src: Bitmap, amount: Float): Bitmap = withContext(Dispatchers.Default) {
        if (amount <= 0.05f) return@withContext src
        val width = src.width
        val height = src.height
        val srcPixels = IntArray(width * height)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        
        val destPixels = IntArray(width * height)
        
        val blend = amount.coerceIn(0f, 0.9f)
        val originalWeight = 1f - blend
        val avgWeight = blend / 9f
        
        for (y in 1 until height - 1) {
            val offset = y * width
            for (x in 1 until width - 1) {
                val idx = offset + x
                
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                
                for (ky in -1..1) {
                    val kOffset = (y + ky) * width
                    for (kx in -1..1) {
                        val pixel = srcPixels[kOffset + (x + kx)]
                        rSum += (pixel shr 16) and 0xFF
                        gSum += (pixel shr 8) and 0xFF
                        bSum += pixel and 0xFF
                    }
                }
                
                val c = srcPixels[idx]
                val rC = ((c shr 16) and 0xFF) * originalWeight + rSum * avgWeight
                val gC = ((c shr 8) and 0xFF) * originalWeight + gSum * avgWeight
                val bC = (c and 0xFF) * originalWeight + bSum * avgWeight
                
                val r = rC.toInt().coerceIn(0, 255)
                val g = gC.toInt().coerceIn(0, 255)
                val b = bC.toInt().coerceIn(0, 255)
                
                destPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        for (x in 0 until width) {
            destPixels[x] = srcPixels[x]
            destPixels[(height - 1) * width + x] = srcPixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            destPixels[y * width] = srcPixels[y * width]
            destPixels[y * width + (width - 1)] = srcPixels[y * width + (width - 1)]
        }
        
        val res = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        res.setPixels(destPixels, 0, width, 0, 0, width, height)
        return@withContext res
    }

    /**
     * Crops a bitmap based on normalised float bounds (0.0 to 1.0).
     */
    suspend fun crop(
        src: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = src.width
        val height = src.height
        
        val startX = (left * width).toInt().coerceIn(0, width - 2)
        val startY = (top * height).toInt().coerceIn(0, height - 2)
        val endX = (right * width).toInt().coerceIn(startX + 2, width)
        val endY = (bottom * height).toInt().coerceIn(startY + 2, height)
        
        val cropW = endX - startX
        val cropH = endY - startY
        
        Bitmap.createBitmap(src, startX, startY, cropW, cropH)
    }

    /**
     * Upscales a image by a scale factor, sharpening the result to preserve details.
     */
    suspend fun upscale(src: Bitmap, scale: Float): Bitmap = withContext(Dispatchers.Default) {
        if (scale <= 1.0f) return@withContext src
        val targetWidth = (src.width * scale).toInt()
        val targetHeight = (src.height * scale).toInt()
        
        val scaled = Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        // Sharpen details slightly to make upscaling look stellar!
        return@withContext sharpen(scaled, 0.2f)
    }

    /**
     * Creates a standard 20-element ColorMatrix array based on manual enhancement sliders:
     * - brightness: -100 to 100
     * - contrast: -100 to 100
     * - saturation: -100 to 100
     * - hue: -180 to 180 (degrees)
     */
    fun createColorMatrix(
        brightness: Float, // -100 to 100 base
        contrast: Float,   // -100 to 100 base
        saturation: Float, // -100 to 100 base
        hue: Float        // -180 to 180 degrees
    ): FloatArray {
        // Base identity matrix array
        val matrix = FloatArray(20) { idx -> if (idx % 6 == 0) 1f else 0f }

        // Helper to multiply two 4x5 matrices representing color transforms
        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(20)
            for (r in 0 until 4) {
                val rowOffset = r * 5
                for (c in 0 until 4) {
                    var sum = 0f
                    for (i in 0 until 4) {
                        sum += a[rowOffset + i] * b[i * 5 + c]
                    }
                    out[rowOffset + c] = sum
                }
                // Translation column
                out[rowOffset + 4] = a[rowOffset + 0] * b[4] +
                                     a[rowOffset + 1] * b[9] +
                                     a[rowOffset + 2] * b[14] +
                                     a[rowOffset + 3] * b[19] +
                                     a[rowOffset + 4]
            }
            return out
        }

        var current = matrix

        // 1. Apply Brightness offset (-255 to 255)
        val bOffset = (brightness / 100f) * 128f
        val brightnessMat = floatArrayOf(
            1f, 0f, 0f, 0f, bOffset,
            0f, 1f, 0f, 0f, bOffset,
            0f, 0f, 1f, 0f, bOffset,
            0f, 0f, 0f, 1f, 0f
        )
        current = multiply(brightnessMat, current)

        // 2. Apply Contrast scale around midpoint
        val cFactor = if (contrast >= 0) {
            1f + (contrast / 100f) * 1.5f
        } else {
            1f + (contrast / 100f) * 0.75f
        }.coerceAtLeast(0f)
        val translate = 128f * (1f - cFactor)
        val contrastMat = floatArrayOf(
            cFactor, 0f, 0f, 0f, translate,
            0f, cFactor, 0f, 0f, translate,
            0f, 0f, cFactor, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        current = multiply(contrastMat, current)

        // 3. Apply Saturation
        val sFactor = if (saturation >= 0) {
            1f + (saturation / 100f) * 1.5f
        } else {
            1f + (saturation / 100f)
        }.coerceIn(0f, 3f)
        
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        
        val satMat = floatArrayOf(
            lumR * (1f - sFactor) + sFactor, lumG * (1f - sFactor),           lumB * (1f - sFactor),           0f, 0f,
            lumR * (1f - sFactor),           lumG * (1f - sFactor) + sFactor, lumB * (1f - sFactor),           0f, 0f,
            lumR * (1f - sFactor),           lumG * (1f - sFactor),           lumB * (1f - sFactor) + sFactor, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        current = multiply(satMat, current)

        // 4. Hue Rotation
        if (hue != 0f) {
            val hueRad = (hue * Math.PI / 180.0).toFloat()
            val cosVal = Math.cos(hueRad.toDouble()).toFloat()
            val sinVal = Math.sin(hueRad.toDouble()).toFloat()
            
            val hMat = floatArrayOf(
                (lumR + cosVal * (1f - lumR) + sinVal * (-lumR)),
                (lumG + cosVal * (-lumG) + sinVal * (-lumG)),
                (lumB + cosVal * (-lumB) + sinVal * (1f - lumB)),
                0f, 0f,
                
                (lumR + cosVal * (-lumR) + sinVal * (0.143f)),
                (lumG + cosVal * (1f - lumG) + sinVal * (0.140f)),
                (lumB + cosVal * (-lumB) + sinVal * (-0.283f)),
                0f, 0f,
                
                (lumR + cosVal * (-lumR) + sinVal * (-(1f - lumR))),
                (lumG + cosVal * (-lumG) + sinVal * (lumG)),
                (lumB + cosVal * (1f - lumB) + sinVal * (lumB)),
                0f, 0f,
                
                0f, 0f, 0f, 1f, 0f
            )
            current = multiply(hMat, current)
        }

        return current
    }

    /**
     * Physically bakes the ColorMatrix edits into a brand new Bitmap in the background.
     */
    suspend fun bakeColorMatrix(src: Bitmap, matrixArray: FloatArray): Bitmap = withContext(Dispatchers.Default) {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrixArray))
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return@withContext result
    }
}
