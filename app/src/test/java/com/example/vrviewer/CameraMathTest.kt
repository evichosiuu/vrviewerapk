package com.example.vrviewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraMathTest {

    private val delta = 1e-5f

    @Test
    fun cameraToScreen_centerPoint_returnsZeroNdc() {
        // Point dead center in front of the camera (Z negative in OpenGL convention)
        val local = floatArrayOf(0f, 0f, -2.0f)
        val halfFovXTan = 1.0f
        val halfFovYTan = 0.75f

        val result = CameraMath.cameraToScreen(local, halfFovXTan, halfFovYTan)

        assertNotNull("Expected non-null result for point in front of camera", result)
        result!!
        assertEquals("Result array size should be 2 (ndcX, ndcY)", 2, result.size)
        assertEquals("NDC X should be 0 at center", 0f, result[0], delta)
        assertEquals("NDC Y should be 0 at center", 0f, result[1], delta)
    }

    @Test
    fun cameraToScreen_offCenterFovBounds_calculatesNdcCorrectly() {
        // Point at FOV limits (X = 2.0, Y = -1.5 at Z = -2.0)
        val local = floatArrayOf(2.0f, -1.5f, -2.0f)
        val halfFovXTan = 1.0f
        val halfFovYTan = 0.75f

        val result = CameraMath.cameraToScreen(local, halfFovXTan, halfFovYTan)

        assertNotNull("Expected non-null result", result)
        result!!
        assertEquals("Result array size should be 2 (ndcX, ndcY)", 2, result.size)
        // z = -(-2.0) = 2.0
        // ndcX = 2.0 / (2.0 * 1.0) = 1.0
        // ndcY = -1.5 / (2.0 * 0.75) = -1.0
        assertEquals("NDC X at right boundary", 1.0f, result[0], delta)
        assertEquals("NDC Y at bottom boundary", -1.0f, result[1], delta)
    }

    @Test
    fun cameraToScreen_behindCamera_returnsNull() {
        // Point behind the camera (+Z direction)
        val local = floatArrayOf(0f, 0f, 1.0f)
        val result = CameraMath.cameraToScreen(local, 1.0f, 1.0f)

        assertNull("Expected null for point behind camera", result)
    }

    @Test
    fun cameraToScreen_atOrigin_returnsNull() {
        // Point at camera position (z = 0)
        val local = floatArrayOf(0f, 0f, 0f)
        val result = CameraMath.cameraToScreen(local, 1.0f, 1.0f)

        assertNull("Expected null for point at origin", result)
    }

    @Test
    fun cameraToScreen_nearClippingThreshold_handlesBoundary() {
        // Depth at near clipping threshold z = 0.01f (z <= 0.01f returns null)
        val localAtThreshold = floatArrayOf(0f, 0f, -0.01f)
        val resultAtThreshold = CameraMath.cameraToScreen(localAtThreshold, 1.0f, 1.0f)
        assertNull("Expected null for z <= 0.01", resultAtThreshold)

        // Depth slightly beyond threshold z = 0.011f
        val localBeyondThreshold = floatArrayOf(0f, 0f, -0.011f)
        val resultBeyondThreshold = CameraMath.cameraToScreen(localBeyondThreshold, 1.0f, 1.0f)
        assertNotNull("Expected non-null for z > 0.01", resultBeyondThreshold)
        resultBeyondThreshold!!
        assertEquals("Result array size should be 2", 2, resultBeyondThreshold.size)
        assertEquals("NDC X at center", 0f, resultBeyondThreshold[0], delta)
        assertEquals("NDC Y at center", 0f, resultBeyondThreshold[1], delta)
    }

    @Test
    fun cameraToScreen_roundTripWithScreenToCameraLocal_matchesOriginalLocal() {
        val originalLocal = floatArrayOf(0.6f, -0.4f, -1.5f)
        val depth = -originalLocal[2]
        val halfFovXTan = 0.8f
        val halfFovYTan = 0.6f

        val screen = CameraMath.cameraToScreen(originalLocal, halfFovXTan, halfFovYTan)
        assertNotNull("Screen projection should not be null", screen)
        screen!!

        val reconstructed = CameraMath.screenToCameraLocal(
            ndcX = screen[0],
            ndcY = screen[1],
            depth = depth,
            halfFovXTan = halfFovXTan,
            halfFovYTan = halfFovYTan
        )

        assertArrayEquals("Reconstructed local position should match original", originalLocal, reconstructed, delta)
    }
}
