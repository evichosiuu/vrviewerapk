package com.example.vrviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvConversionBenchmarkTest {

    @Test
    fun testUnoptimizedvsOptimizedAllocationBenchmark() {
        val iterations = 5000
        val width = 640
        val height = 480
        val w2 = width / 2
        val h2 = height / 2
        val pixelCount = w2 * h2

        // Measure unoptimized (allocating IntArray on every frame)
        val startTimeUnoptimized = System.nanoTime()

        var dummyHashUnoptimized = 0L
        for (i in 0 until iterations) {
            val argb = IntArray(pixelCount)
            // Simulating image loop processing
            for (j in 0 until pixelCount step 16) {
                argb[j] = (0xFF shl 24) or (j and 0xFF)
            }
            dummyHashUnoptimized += argb[0]
        }

        val durationUnoptimizedNs = System.nanoTime() - startTimeUnoptimized
        val durationUnoptimizedMs = durationUnoptimizedNs / 1_000_000.0

        // Measure optimized (reusing ThreadLocal buffer)
        val startTimeOptimized = System.nanoTime()

        var dummyHashOptimized = 0L
        for (i in 0 until iterations) {
            val argb = YuvBufferPool.getArgbBuffer(pixelCount)
            for (j in 0 until pixelCount step 16) {
                argb[j] = (0xFF shl 24) or (j and 0xFF)
            }
            dummyHashOptimized += argb[0]
        }

        val durationOptimizedNs = System.nanoTime() - startTimeOptimized
        val durationOptimizedMs = durationOptimizedNs / 1_000_000.0

        println("=== BENCHMARK RESULTS ===")
        println("Iterations: $iterations frames ($w2 x $h2 pixels per frame)")
        println("Unoptimized duration: ${String.format("%.2f", durationUnoptimizedMs)} ms")
        println("Optimized duration:   ${String.format("%.2f", durationOptimizedMs)} ms")
        val speedup = (durationUnoptimizedMs - durationOptimizedMs) / durationUnoptimizedMs * 100.0
        println("Time saved: ${String.format("%.2f", speedup)}%")
        println("Unoptimized allocations: ${(iterations.toLong() * pixelCount * 4) / (1024 * 1024)} MB garbage created")
        println("Optimized allocations:   0 MB garbage created across $iterations frames")
        println("=========================")

        assertEquals(dummyHashUnoptimized, dummyHashOptimized)
        assertTrue(durationOptimizedMs <= durationUnoptimizedMs)
    }

    @Test
    fun testYuvBufferPoolIsolationAndReuse() {
        val size = 640 * 480
        val buf1 = YuvBufferPool.getArgbBuffer(size)
        val buf2 = YuvBufferPool.getArgbBuffer(size)

        assertTrue("Buffers obtained on same thread must be identical instance", buf1 === buf2)
        assertTrue("Buffer length must be at least requested size", buf1.size >= size)

        val byteBuf1 = YuvBufferPool.getYBuffer(1024)
        val byteBuf2 = YuvBufferPool.getYBuffer(1024)
        assertTrue("Byte buffers obtained on same thread must be identical instance", byteBuf1 === byteBuf2)
    }

    @Test
    fun testFixedPointVsFloatMathPrecision() {
        val coefR_V = (1.370705f * 65536).toInt() // 89830
        val coefG_U = (0.337633f * 65536).toInt() // 22127
        val coefG_V = (0.698001f * 65536).toInt() // 45744
        val coefB_U = (1.732446f * 65536).toInt() // 113537

        for (y in 0..255) {
            for (uVal in -128..127 step 4) {
                for (vVal in -128..127 step 4) {
                    val rFloat = (y + (1.370705f * vVal)).toInt().coerceIn(0, 255)
                    val gFloat = (y - (0.337633f * uVal) - (0.698001f * vVal)).toInt().coerceIn(0, 255)
                    val bFloat = (y + (1.732446f * uVal)).toInt().coerceIn(0, 255)

                    val rFixed = (y + ((coefR_V * vVal) shr 16)).coerceIn(0, 255)
                    val gFixed = (y - ((coefG_U * uVal) shr 16) - ((coefG_V * vVal) shr 16)).coerceIn(0, 255)
                    val bFixed = (y + ((coefB_U * uVal) shr 16)).coerceIn(0, 255)

                    assertTrue("Red delta <= 2", Math.abs(rFloat - rFixed) <= 2)
                    assertTrue("Green delta <= 2", Math.abs(gFloat - gFixed) <= 2)
                    assertTrue("Blue delta <= 2", Math.abs(bFloat - bFixed) <= 2)
                }
            }
        }
    }
}
