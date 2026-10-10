package com.example.vrviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    @Test
    fun initialValue_returnsRawValue() {
        val filter = OneEuroFilter()
        val initialX = 42.5f
        val timestampMs = 1000L

        val result = filter.filter(initialX, timestampMs)

        assertEquals("First filtered value should equal initial raw value", initialX, result, 0.0001f)
    }

    @Test
    fun constantSignal_maintainsValue() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f, dCutoff = 1.0f)
        val value = 10.0f
        var timestamp = 1000L

        // Initialize filter
        filter.filter(value, timestamp)

        // Feed constant value over multiple frames
        for (i in 1..20) {
            timestamp += 33L // ~30 fps
            val output = filter.filter(value, timestamp)
            assertEquals("Output should remain constant for constant input", value, output, 0.0001f)
        }
    }

    @Test
    fun noisySignal_reducesJitter() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.0f, dCutoff = 1.0f)
        val baseValue = 50.0f
        var timestamp = 1000L

        // Initialize filter
        filter.filter(baseValue, timestamp)

        var totalRawJitter = 0.0f
        var totalFilteredJitter = 0.0f
        var previousFiltered = baseValue

        // Simulate 30 frames of noisy signal oscillating +/- 2.0 around baseValue
        for (i in 1..30) {
            timestamp += 33L
            val noise = if (i % 2 == 0) 2.0f else -2.0f
            val rawValue = baseValue + noise
            val filteredValue = filter.filter(rawValue, timestamp)

            totalRawJitter += abs(rawValue - baseValue)
            totalFilteredJitter += abs(filteredValue - previousFiltered)
            previousFiltered = filteredValue
        }

        assertTrue(
            "Filtered jitter ($totalFilteredJitter) should be significantly less than raw jitter ($totalRawJitter)",
            totalFilteredJitter < totalRawJitter * 0.5f
        )
    }

    @Test
    fun adaptiveCutoff_betaEffectOnHighSpeedMotion() {
        // Filter with beta = 0 (no adaptive cutoff) vs filter with beta = 0.1 (high adaptive cutoff on motion)
        val filterNoBeta = OneEuroFilter(minCutoff = 0.5f, beta = 0.0f, dCutoff = 1.0f)
        val filterWithBeta = OneEuroFilter(minCutoff = 0.5f, beta = 0.1f, dCutoff = 1.0f)

        var timestamp = 1000L
        val startVal = 0.0f
        val stepVal = 100.0f

        filterNoBeta.filter(startVal, timestamp)
        filterWithBeta.filter(startVal, timestamp)

        // Sudden step change on next frame
        timestamp += 33L
        val outNoBeta = filterNoBeta.filter(stepVal, timestamp)
        val outWithBeta = filterWithBeta.filter(stepVal, timestamp)

        // With beta > 0, fast speed motion increases cutoff, resulting in higher responsiveness (closer to stepVal)
        assertTrue(
            "Filter with beta ($outWithBeta) should respond faster to sudden step change than filter without beta ($outNoBeta)",
            outWithBeta > outNoBeta
        )
    }

    @Test
    fun sameTimestamp_handlesZeroDtGracefully() {
        val filter = OneEuroFilter()
        val timestamp = 1000L

        filter.filter(10.0f, timestamp)
        // Send frame with identical timestamp (dt = 0)
        val outputSameTime = filter.filter(20.0f, timestamp)

        assertFalse("Output should not be NaN on zero dt", outputSameTime.isNaN())
        assertFalse("Output should not be Infinite on zero dt", outputSameTime.isInfinite())

        // Send frame with earlier timestamp (dt < 0)
        val outputPastTime = filter.filter(25.0f, timestamp - 100L)
        assertFalse("Output should not be NaN on negative dt", outputPastTime.isNaN())
        assertFalse("Output should not be Infinite on negative dt", outputPastTime.isInfinite())
    }

    @Test
    fun reset_clearsPreviousState() {
        val filter = OneEuroFilter()
        var timestamp = 1000L

        filter.filter(10.0f, timestamp)
        timestamp += 33L
        filter.filter(20.0f, timestamp)

        // Reset state
        filter.reset()

        // Next call should behave like initial call
        val newRawVal = 100.0f
        val outputAfterReset = filter.filter(newRawVal, 5000L)

        assertEquals(
            "After reset(), filter should return the exact new input value on first frame",
            newRawVal,
            outputAfterReset,
            0.0001f
        )
    }

    @Test
    fun customFilterParameters() {
        val filter = OneEuroFilter(minCutoff = 2.0f, beta = 0.05f, dCutoff = 2.0f)
        val val1 = filter.filter(5.0f, 1000L)
        assertEquals(5.0f, val1, 0.0001f)

        val val2 = filter.filter(15.0f, 1033L)
        assertTrue("Filtered value should be between previous and current input", val2 > 5.0f && val2 < 15.0f)
    }
}
