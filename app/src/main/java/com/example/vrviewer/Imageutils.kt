package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

private val tlImageFastHalfArgbBuffer = ThreadLocal<IntArray>()

private val tlImageHandTrackingArgbBuffer = ThreadLocal<IntArray>()
private val tlImageHandTrackingYBuffer = ThreadLocal<ByteArray>()
private val tlImageHandTrackingUBuffer = ThreadLocal<ByteArray>()
private val tlImageHandTrackingVBuffer = ThreadLocal<ByteArray>()

private fun getOrCreateIntArray(tl: ThreadLocal<IntArray>, minSize: Int): IntArray {
    var arr = tl.get()
    if (arr == null || arr.size < minSize) {
        arr = IntArray(minSize)
        tl.set(arr)
    }
    return arr
}

private fun getOrCreateByteArray(tl: ThreadLocal<ByteArray>, minSize: Int): ByteArray {
    var arr = tl.get()
    if (arr == null || arr.size < minSize) {
        arr = ByteArray(minSize)
        tl.set(arr)
    }
    return arr
}

fun Image.toBitmapFastHalf(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yRowStride  = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val W2 = width  / 2
    val H2 = height / 2
    val argb = getOrCreateIntArray(tlImageFastHalfArgbBuffer, W2 * H2)

    for (row in 0 until H2) {
        val srcRow = row * 2
        for (col in 0 until W2) {
            val srcCol = col * 2
            val yIdx   = srcRow * yRowStride + srcCol
            val uvRow  = (srcRow / 2) * uvRowStride
            val uvCol  = (srcCol / 2) * uvPixelStride

            val y = (yBuf.get(yIdx).toInt() and 0xFF)
            val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
            val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128

            val r = (y + (1.370705f * v)).toInt().coerceIn(0, 255)
            val g = (y - (0.337633f * u) - (0.698001f * v)).toInt().coerceIn(0, 255)
            val b = (y + (1.732446f * u)).toInt().coerceIn(0, 255)

            argb[row * W2 + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, 0, W2, W2, H2, Bitmap.Config.ARGB_8888)
}


private const val IMG_COEF_R_V = 89858   // 1.370705 * 65536
private const val IMG_COEF_G_U = 22124   // 0.337633 * 65536
private const val IMG_COEF_G_V = 45744   // 0.698001 * 65536
private const val IMG_COEF_B_U = 113563  // 1.732446 * 65536

fun Image.toBitmapForHandTracking(maxDim: Int = 256): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val srcW = width
    val srcH = height

    val yBuf = yPlane.buffer.duplicate()
    val yRem = yBuf.remaining()
    val yBytes = getOrCreateByteArray(tlImageHandTrackingYBuffer, yRem)
    yBuf.get(yBytes, 0, yRem)

    val uBuf = uPlane.buffer.duplicate()
    val uRem = uBuf.remaining()
    val uBytes = getOrCreateByteArray(tlImageHandTrackingUBuffer, uRem)
    uBuf.get(uBytes, 0, uRem)

    val vBuf = vPlane.buffer.duplicate()
    val vRem = vBuf.remaining()
    val vBytes = getOrCreateByteArray(tlImageHandTrackingVBuffer, vRem)
    vBuf.get(vBytes, 0, vRem)

    val longSide = maxOf(srcW, srcH)
    var sample = (longSide + maxDim - 1) / maxDim
    if (sample < 1) sample = 1
    if (sample % 2 != 0) sample += 1

    val outW = srcW / sample
    val outH = srcH / sample
    if (outW <= 0 || outH <= 0) return null

    val argb = getOrCreateIntArray(tlImageHandTrackingArgbBuffer, outW * outH)

    for (row in 0 until outH) {
        val srcRow = row * sample
        val yRowBase = srcRow * yRowStride
        val uvRowBase = (srcRow / 2) * uvRowStride
        val outRowBase = row * outW
        for (col in 0 until outW) {
            val srcCol = col * sample
            val yIdx = yRowBase + srcCol
            val uvIdx = uvRowBase + (srcCol / 2) * uvPixelStride

            val y = yBytes[yIdx].toInt() and 0xFF
            val u = (uBytes[uvIdx].toInt() and 0xFF) - 128
            val v = (vBytes[uvIdx].toInt() and 0xFF) - 128

            val r = (y + ((IMG_COEF_R_V * v) shr 16)).coerceIn(0, 255)
            val g = (y - ((IMG_COEF_G_U * u) shr 16) - ((IMG_COEF_G_V * v) shr 16)).coerceIn(0, 255)
            val b = (y + ((IMG_COEF_B_U * u) shr 16)).coerceIn(0, 255)

            argb[outRowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, 0, outW, outW, outH, Bitmap.Config.ARGB_8888)
}