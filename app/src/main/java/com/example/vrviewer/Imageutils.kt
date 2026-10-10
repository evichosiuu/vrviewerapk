package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

internal object YuvBufferPool {
    private val argbPool = object : ThreadLocal<IntArray>() {
        override fun initialValue(): IntArray? = null
    }
    private val yBytesPool = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray? = null
    }
    private val uBytesPool = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray? = null
    }
    private val vBytesPool = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray? = null
    }

    fun getArgbBuffer(minSize: Int): IntArray {
        var buf = argbPool.get()
        if (buf == null || buf.size < minSize) {
            buf = IntArray(minSize)
            argbPool.set(buf)
        }
        return buf
    }

    fun getYBuffer(minSize: Int): ByteArray {
        var buf = yBytesPool.get()
        if (buf == null || buf.size < minSize) {
            buf = ByteArray(minSize)
            yBytesPool.set(buf)
        }
        return buf
    }

    fun getUBuffer(minSize: Int): ByteArray {
        var buf = uBytesPool.get()
        if (buf == null || buf.size < minSize) {
            buf = ByteArray(minSize)
            uBytesPool.set(buf)
        }
        return buf
    }

    fun getVBuffer(minSize: Int): ByteArray {
        var buf = vBytesPool.get()
        if (buf == null || buf.size < minSize) {
            buf = ByteArray(minSize)
            vBytesPool.set(buf)
        }
        return buf
    }
}

private const val IMG_COEF_R_V = 89858   // 1.370705 * 65536
private const val IMG_COEF_G_U = 22124   // 0.337633 * 65536
private const val IMG_COEF_G_V = 45744   // 0.698001 * 65536
private const val IMG_COEF_B_U = 113563  // 1.732446 * 65536

fun Image.toBitmapFastHalf(outBuffer: IntArray? = null): Bitmap? {
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
    val neededSize = W2 * H2
    val argb = outBuffer?.takeIf { it.size >= neededSize } ?: YuvBufferPool.getArgbBuffer(neededSize)

    for (row in 0 until H2) {
        val srcRow = row * 2
        val yRowBase = srcRow * yRowStride
        val uvRowBase = (srcRow / 2) * uvRowStride
        val outRowBase = row * W2
        for (col in 0 until W2) {
            val srcCol = col * 2
            val yIdx   = yRowBase + srcCol
            val uvIdx  = uvRowBase + (srcCol / 2) * uvPixelStride

            val y = (yBuf.get(yIdx).toInt() and 0xFF)
            val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
            val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

            val r = (y + ((IMG_COEF_R_V * v) shr 16)).coerceIn(0, 255)
            val g = (y - ((IMG_COEF_G_U * u) shr 16) - ((IMG_COEF_G_V * v) shr 16)).coerceIn(0, 255)
            val b = (y + ((IMG_COEF_B_U * u) shr 16)).coerceIn(0, 255)

            argb[outRowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, W2, H2, Bitmap.Config.ARGB_8888)
}

fun Image.toBitmapForHandTracking(maxDim: Int = 256, outBuffer: IntArray? = null): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val srcW = width
    val srcH = height

    val yRemaining = yPlane.buffer.remaining()
    val uRemaining = uPlane.buffer.remaining()
    val vRemaining = vPlane.buffer.remaining()

    val yBytes = YuvBufferPool.getYBuffer(yRemaining)
    yPlane.buffer.get(yBytes, 0, yRemaining)
    val uBytes = YuvBufferPool.getUBuffer(uRemaining)
    uPlane.buffer.get(uBytes, 0, uRemaining)
    val vBytes = YuvBufferPool.getVBuffer(vRemaining)
    vPlane.buffer.get(vBytes, 0, vRemaining)

    val longSide = maxOf(srcW, srcH)
    var sample = (longSide + maxDim - 1) / maxDim
    if (sample < 1) sample = 1
    if (sample % 2 != 0) sample += 1

    val outW = srcW / sample
    val outH = srcH / sample
    if (outW <= 0 || outH <= 0) return null

    val neededSize = outW * outH
    val argb = outBuffer?.takeIf { it.size >= neededSize } ?: YuvBufferPool.getArgbBuffer(neededSize)

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

    return Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
}
