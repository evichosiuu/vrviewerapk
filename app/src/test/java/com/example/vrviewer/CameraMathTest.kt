package com.example.vrviewer

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CameraMathTest {

    private val delta = 1e-5f

    @Test
    fun worldToCamera_identityTransform() {
        val camPos = floatArrayOf(0f, 0f, 0f)
        val camQuat = floatArrayOf(0f, 0f, 0f, 1f) // identity quaternion
        val worldPos = floatArrayOf(3f, -2f, 5f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(floatArrayOf(3f, -2f, 5f), localPos, delta)
    }

    @Test
    fun worldToCamera_pureTranslation() {
        val camPos = floatArrayOf(10f, 20f, 30f)
        val camQuat = floatArrayOf(0f, 0f, 0f, 1f)
        val worldPos = floatArrayOf(15f, 18f, 25f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(floatArrayOf(5f, -2f, -5f), localPos, delta)
    }

    @Test
    fun worldToCamera_samePosition() {
        val camPos = floatArrayOf(1.5f, -2.5f, 3.0f)
        val camQuat = floatArrayOf(0f, 0.70710678f, 0f, 0.70710678f)
        val worldPos = floatArrayOf(1.5f, -2.5f, 3.0f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(floatArrayOf(0f, 0f, 0f), localPos, delta)
    }

    @Test
    fun worldToCamera_pureRotation180DegreesAroundY() {
        val camPos = floatArrayOf(0f, 0f, 0f)
        val camQuat = floatArrayOf(0f, 1f, 0f, 0f) // 180 degrees around Y axis
        val worldPos = floatArrayOf(1f, 2f, 3f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        // 180 deg Y rotation maps (x, y, z) -> (-x, y, -z)
        assertArrayEquals(floatArrayOf(-1f, 2f, -3f), localPos, delta)
    }

    @Test
    fun worldToCamera_pureRotation90DegreesAroundY() {
        val camPos = floatArrayOf(0f, 0f, 0f)
        val qy = Math.sin(Math.PI / 4).toFloat() // sin(45deg)
        val qw = Math.cos(Math.PI / 4).toFloat() // cos(45deg)
        val camQuat = floatArrayOf(0f, qy, 0f, qw)
        val worldPos = floatArrayOf(1f, 0f, 0f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(floatArrayOf(0f, 0f, 1f), localPos, delta)
    }

    @Test
    fun worldToCamera_combinedTranslationAndRotation() {
        val camPos = floatArrayOf(5f, 5f, 5f)
        val camQuat = floatArrayOf(0f, 1f, 0f, 0f) // 180 degrees around Y
        val worldPos = floatArrayOf(6f, 7f, 8f) // relative = (1, 2, 3)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(floatArrayOf(-1f, 2f, -3f), localPos, delta)
    }

    @Test
    fun worldToCamera_roundTripWithCameraToWorld() {
        val camPos = floatArrayOf(1.5f, -2.3f, 4.1f)
        // Normalized quaternion: 0.2^2 + 0.4^2 + 0.4^2 + 0.8^2 = 0.04 + 0.16 + 0.16 + 0.64 = 1.0
        val camQuat = floatArrayOf(0.2f, 0.4f, 0.4f, 0.8f)
        val originalWorldPos = floatArrayOf(10.0f, -5.5f, 2.25f)

        val localPos = CameraMath.worldToCamera(camPos, camQuat, originalWorldPos)
        val restoredWorldPos = CameraMath.cameraToWorld(camPos, camQuat, localPos)

        assertArrayEquals(originalWorldPos, restoredWorldPos, delta)
    }

    @Test
    fun worldToCamera_roundTripFromCameraLocalToWorldAndBack() {
        val camPos = floatArrayOf(-5f, 12f, 3f)
        val camQuat = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f) // normalized quaternion
        val originalLocalPos = floatArrayOf(-1.2f, 3.4f, -5.6f)

        val worldPos = CameraMath.cameraToWorld(camPos, camQuat, originalLocalPos)
        val restoredLocalPos = CameraMath.worldToCamera(camPos, camQuat, worldPos)

        assertArrayEquals(originalLocalPos, restoredLocalPos, delta)
    }
}
