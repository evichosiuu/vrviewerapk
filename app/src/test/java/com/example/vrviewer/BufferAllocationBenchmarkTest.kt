package com.example.vrviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

class BufferAllocationBenchmarkTest {

    @Test
    fun benchmarkIntArrayAllocationVsReuse() {
        val width = 640
        val height = 480
        val w2 = width / 2
        val h2 = height / 2
        val requiredSize = w2 * h2
        val iterations = 1000

        // Benchmark 1: Per-frame allocation (baseline)
        System.gc()
        val memBeforeAlloc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startAllocTime = System.nanoTime()

        val allocatedArrays = ArrayList<IntArray>(iterations)
        for (i in 0 until iterations) {
            val argb = IntArray(requiredSize)
            argb[0] = i
            allocatedArrays.add(argb)
        }
        val allocDurationMs = (System.nanoTime() - startAllocTime) / 1_000_000.0
        val memAfterAlloc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val allocMemoryDeltaKB = (memAfterAlloc - memBeforeAlloc) / 1024.0

        // Benchmark 2: Buffer reuse using ThreadLocal
        System.gc()
        val threadLocalBuffer = object : ThreadLocal<IntArray>() {
            override fun initialValue(): IntArray? = null
        }
        fun getReusableBuffer(size: Int): IntArray {
            var buf = threadLocalBuffer.get()
            if (buf == null || buf.size < size) {
                buf = IntArray(size)
                threadLocalBuffer.set(buf)
            }
            return buf
        }

        val memBeforeReuse = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startReuseTime = System.nanoTime()

        for (i in 0 until iterations) {
            val argb = getReusableBuffer(requiredSize)
            argb[0] = i
        }
        val reuseDurationMs = (System.nanoTime() - startReuseTime) / 1_000_000.0
        val memAfterReuse = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val reuseMemoryDeltaKB = (memAfterReuse - memBeforeReuse) / 1024.0

        println("=== BENCHMARK RESULTS ($iterations iterations) ===")
        println("Baseline (Per-frame IntArray Allocation):")
        println("  Time: %.3f ms".format(allocDurationMs))
        println("  Allocated IntArray total bytes: %d KB".format((iterations * requiredSize * 4) / 1024))
        println("  Memory Delta: %.2f KB".format(allocMemoryDeltaKB))
        println("Optimized (ThreadLocal Reusable IntArray):")
        println("  Time: %.3f ms".format(reuseDurationMs))
        println("  Memory Delta: %.2f KB".format(reuseMemoryDeltaKB))
        println("=================================================")

        // Verification assertions
        val reusableBuf = getReusableBuffer(requiredSize)
        assertNotNull(reusableBuf)
        assertEquals(requiredSize, reusableBuf.size)
    }
}
