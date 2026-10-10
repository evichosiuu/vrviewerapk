package com.example.vrviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UsbTrackingSenderBenchmarkTest {

    @Test
    fun benchmarkBuildPacket() {
        val sender = UsbTrackingSender(
            onStatus = {},
            onConnected = {},
            onFailed = {}
        )

        val reflectionMethod = UsbTrackingSender::class.java.getDeclaredMethod("buildPacket")
        reflectionMethod.isAccessible = true

        // Warmup
        repeat(10_000) {
            reflectionMethod.invoke(sender)
        }

        val iterations = 500_000

        System.gc()
        Thread.sleep(100)

        val startTime = System.nanoTime()

        var lastPacket: ByteArray? = null
        repeat(iterations) {
            lastPacket = reflectionMethod.invoke(sender) as ByteArray
        }

        val endTime = System.nanoTime()

        val totalTimeMs = (endTime - startTime) / 1_000_000.0
        val avgTimeUs = ((endTime - startTime) / 1000.0) / iterations

        assertNotNull(lastPacket)
        assertEquals(196, lastPacket!!.size)

        println("BENCHMARK_RESULT: Iterations=$iterations, TotalTimeMs=$totalTimeMs, AvgTimeUsPerOp=$avgTimeUs")
    }
}
