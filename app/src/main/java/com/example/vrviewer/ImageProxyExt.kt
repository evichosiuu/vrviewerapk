package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

private val tlIntBuffer = object : ThreadLocal<IntArray>() {
    override fun initialValue(): IntArray = IntArray(0)
}

private val tlYBuffer = object : ThreadLocal<ByteArray>() {
    override fun initialValue(): ByteArray = ByteArray(0)
}

private val tlUBuffer = object : ThreadLocal<ByteArray>() {
    override fun initialValue(): ByteArray = ByteArray(0)
}

private val tlVBuffer = object : ThreadLocal<ByteArray>() {
    override fun initialValue(): ByteArray = ByteArray(0)
}

private fun getIntBuffer(minSize: Int): IntArray {
    var buf = tlIntBuffer.get()!!
    if (buf.size < minSize) {
        buf = IntArray(minSize)
        tlIntBuffer.set(buf)
    }
    return buf
}

private fun getByteBuffer(tl: ThreadLocal<ByteArray>, minSize: Int): ByteArray {
    var buf = tl.get()!!
    if (buf.size < minSize) {
        buf = ByteArray(minSize)
        tl.set(buf)
    }
    return buf
}

fun ImageProxy.toBitmapFast(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride   // 1 para planar, 2 para semiplanar (NV12/NV21)

    val W = width
    val H = height
    val argb = getIntBuffer(W * H)

    for (row in 0 until H) {
        val yRow = row * yRowStride
        val uvRow = (row / 2) * uvRowStride
        val outRow = row * W
        for (col in 0 until W) {
            val yIdx = yRow + col
            val uvCol = (col / 2) * uvPixelStride

            val y = (yBuf.get(yIdx).toInt() and 0xFF)
            val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
            val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128

            val r = (y + (1.370705f * v)).toInt().coerceIn(0, 255)
            val g = (y - (0.337633f * u) - (0.698001f * v)).toInt().coerceIn(0, 255)
            val b = (y + (1.732446f * u)).toInt().coerceIn(0, 255)

            argb[outRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, 0, W, W, H, Bitmap.Config.ARGB_8888)
}


fun ImageProxy.toBitmapFastHalf(): Bitmap? {
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
    val argb = getIntBuffer(W2 * H2)

    for (row in 0 until H2) {
        val srcRow = row * 2
        val yRow = srcRow * yRowStride
        val uvRow = (srcRow / 2) * uvRowStride
        val outRow = row * W2
        for (col in 0 until W2) {
            val srcCol = col * 2
            val yIdx   = yRow + srcCol
            val uvCol  = (srcCol / 2) * uvPixelStride

            val y = (yBuf.get(yIdx).toInt() and 0xFF)
            val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
            val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128

            val r = (y + (1.370705f * v)).toInt().coerceIn(0, 255)
            val g = (y - (0.337633f * u) - (0.698001f * v)).toInt().coerceIn(0, 255)
            val b = (y + (1.732446f * u)).toInt().coerceIn(0, 255)

            argb[outRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, 0, W2, W2, H2, Bitmap.Config.ARGB_8888)
}


private const val COEF_R_V = 89858   // 1.370705 * 65536
private const val COEF_G_U = 22124   // 0.337633 * 65536
private const val COEF_G_V = 45744   // 0.698001 * 65536
private const val COEF_B_U = 113563  // 1.732446 * 65536


fun ImageProxy.toBitmapForHandTracking(maxDim: Int = 256): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    val srcW = width
    val srcH = height

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer

    val yLen = yBuf.remaining()
    val uLen = uBuf.remaining()
    val vLen = vBuf.remaining()

    val yBytes = getByteBuffer(tlYBuffer, yLen)
    yBuf.get(yBytes, 0, yLen)
    val uBytes = getByteBuffer(tlUBuffer, uLen)
    uBuf.get(uBytes, 0, uLen)
    val vBytes = getByteBuffer(tlVBuffer, vLen)
    vBuf.get(vBytes, 0, vLen)

    val longSide = maxOf(srcW, srcH)
    var sample = (longSide + maxDim - 1) / maxDim
    if (sample < 1) sample = 1
    if (sample % 2 != 0) sample += 1

    val outW = srcW / sample
    val outH = srcH / sample
    if (outW <= 0 || outH <= 0) return null

    val argb = getIntBuffer(outW * outH)

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

            val r = (y + ((COEF_R_V * v) shr 16)).coerceIn(0, 255)
            val g = (y - ((COEF_G_U * u) shr 16) - ((COEF_G_V * v) shr 16)).coerceIn(0, 255)
            val b = (y + ((COEF_B_U * u) shr 16)).coerceIn(0, 255)

            argb[outRowBase + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    return Bitmap.createBitmap(argb, 0, outW, outW, outH, Bitmap.Config.ARGB_8888)
}
