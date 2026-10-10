package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.nio.ByteBuffer

class ImageProxyExtTest {

    private fun createMockImageProxy(width: Int = 1280, height: Int = 720, format: Int = ImageFormat.YUV_420_888): ImageProxy {
        val imageProxy = mock(ImageProxy::class.java)
        `when`(imageProxy.format).thenReturn(format)
        `when`(imageProxy.width).thenReturn(width)
        `when`(imageProxy.height).thenReturn(height)

        if (format != ImageFormat.YUV_420_888) return imageProxy

        val yPlane = mock(ImageProxy.PlaneProxy::class.java)
        val uPlane = mock(ImageProxy.PlaneProxy::class.java)
        val vPlane = mock(ImageProxy.PlaneProxy::class.java)

        val yBuffer = ByteBuffer.allocateDirect(width * height)
        val uBuffer = ByteBuffer.allocateDirect((width / 2) * (height / 2))
        val vBuffer = ByteBuffer.allocateDirect((width / 2) * (height / 2))

        for (i in 0 until yBuffer.capacity()) {
            yBuffer.put((i % 256).toByte())
        }
        yBuffer.flip()

        for (i in 0 until uBuffer.capacity()) {
            uBuffer.put((128).toByte())
        }
        uBuffer.flip()

        for (i in 0 until vBuffer.capacity()) {
            vBuffer.put((128).toByte())
        }
        vBuffer.flip()

        `when`(yPlane.buffer).thenReturn(yBuffer)
        `when`(yPlane.rowStride).thenReturn(width)
        `when`(yPlane.pixelStride).thenReturn(1)

        `when`(uPlane.buffer).thenReturn(uBuffer)
        `when`(uPlane.rowStride).thenReturn(width / 2)
        `when`(uPlane.pixelStride).thenReturn(1)

        `when`(vPlane.buffer).thenReturn(vBuffer)
        `when`(vPlane.rowStride).thenReturn(width / 2)
        `when`(vPlane.pixelStride).thenReturn(1)

        `when`(imageProxy.planes).thenReturn(arrayOf(yPlane, uPlane, vPlane))

        return imageProxy
    }

    @Test
    fun testToBitmapForHandTrackingEdgeCases() {
        val mockBitmap = mock(Bitmap::class.java)
        val bitmapClassMock: MockedStatic<Bitmap> = mockStatic(Bitmap::class.java)

        bitmapClassMock.`when`<Bitmap> {
            Bitmap.createBitmap(
                any(IntArray::class.java),
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(Bitmap.Config::class.java)
            )
        }.thenReturn(mockBitmap)

        // Non-YUV format returns null
        val invalidFormatProxy = createMockImageProxy(1280, 720, format = ImageFormat.JPEG)
        assertNull(invalidFormatProxy.toBitmapForHandTracking(256))

        // Dynamic dimension scaling (small frame -> larger frame)
        val smallProxy = createMockImageProxy(320, 240)
        assertNotNull(smallProxy.toBitmapForHandTracking(256))

        val largeProxy = createMockImageProxy(1920, 1080)
        assertNotNull(largeProxy.toBitmapForHandTracking(256))

        bitmapClassMock.close()
    }

    @Test
    fun benchmarkToBitmapForHandTracking() {
        val mockBitmap = mock(Bitmap::class.java)
        val bitmapClassMock: MockedStatic<Bitmap> = mockStatic(Bitmap::class.java)

        bitmapClassMock.`when`<Bitmap> {
            Bitmap.createBitmap(
                any(IntArray::class.java),
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(Bitmap.Config::class.java)
            )
        }.thenReturn(mockBitmap)

        val imageProxy = createMockImageProxy(1280, 720)

        // Warmup
        repeat(50) {
            imageProxy.planes[0].buffer.rewind()
            imageProxy.planes[1].buffer.rewind()
            imageProxy.planes[2].buffer.rewind()
            imageProxy.toBitmapForHandTracking(256)
        }

        // Benchmark
        val iterations = 2000
        val startTime = System.nanoTime()
        repeat(iterations) {
            imageProxy.planes[0].buffer.rewind()
            imageProxy.planes[1].buffer.rewind()
            imageProxy.planes[2].buffer.rewind()
            val bmp = imageProxy.toBitmapForHandTracking(256)
            assertNotNull(bmp)
        }
        val endTime = System.nanoTime()

        val totalMs = (endTime - startTime) / 1_000_000.0
        val avgMs = totalMs / iterations
        println("BENCHMARK_RESULT: $iterations iterations took ${totalMs} ms (Average: ${avgMs} ms / frame)")

        bitmapClassMock.close()
    }
}
