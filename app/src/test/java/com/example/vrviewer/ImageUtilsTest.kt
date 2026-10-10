package com.example.vrviewer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.nio.ByteBuffer

class ImageUtilsTest {

    private fun createMockImage(width: Int = 640, height: Int = 480): Image {
        val image = mock(Image::class.java)
        `when`(image.format).thenReturn(ImageFormat.YUV_420_888)
        `when`(image.width).thenReturn(width)
        `when`(image.height).thenReturn(height)

        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)

        val yPlane = mock(Image.Plane::class.java)
        `when`(yPlane.buffer).thenReturn(yBuffer)
        `when`(yPlane.rowStride).thenReturn(width)
        `when`(yPlane.pixelStride).thenReturn(1)

        val uPlane = mock(Image.Plane::class.java)
        `when`(uPlane.buffer).thenReturn(uBuffer)
        `when`(uPlane.rowStride).thenReturn(width / 2)
        `when`(uPlane.pixelStride).thenReturn(1)

        val vPlane = mock(Image.Plane::class.java)
        `when`(vPlane.buffer).thenReturn(vBuffer)
        `when`(vPlane.rowStride).thenReturn(width / 2)
        `when`(vPlane.pixelStride).thenReturn(1)

        `when`(image.planes).thenReturn(arrayOf(yPlane, uPlane, vPlane))
        return image
    }

    private fun createMockImageProxy(width: Int = 640, height: Int = 480): ImageProxy {
        val proxy = mock(ImageProxy::class.java)
        `when`(proxy.format).thenReturn(ImageFormat.YUV_420_888)
        `when`(proxy.width).thenReturn(width)
        `when`(proxy.height).thenReturn(height)

        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)

        val yPlane = mock(ImageProxy.PlaneProxy::class.java)
        `when`(yPlane.buffer).thenReturn(yBuffer)
        `when`(yPlane.rowStride).thenReturn(width)
        `when`(yPlane.pixelStride).thenReturn(1)

        val uPlane = mock(ImageProxy.PlaneProxy::class.java)
        `when`(uPlane.buffer).thenReturn(uBuffer)
        `when`(uPlane.rowStride).thenReturn(width / 2)
        `when`(uPlane.pixelStride).thenReturn(1)

        val vPlane = mock(ImageProxy.PlaneProxy::class.java)
        `when`(vPlane.buffer).thenReturn(vBuffer)
        `when`(vPlane.rowStride).thenReturn(width / 2)
        `when`(vPlane.pixelStride).thenReturn(1)

        `when`(proxy.planes).thenReturn(arrayOf(yPlane, uPlane, vPlane))
        return proxy
    }

    @Test
    fun testBufferCacheReuse() {
        val mockBitmap = mock(Bitmap::class.java)
        mockStatic(Bitmap::class.java).use { bitmapMock ->
            bitmapMock.`when`<Bitmap> {
                Bitmap.createBitmap(org.mockito.kotlin.any<IntArray>(), anyInt(), anyInt(), anyInt(), anyInt(), org.mockito.kotlin.any())
            }.thenReturn(mockBitmap)

            val cache = threadLocalYuvBufferCache.get()!!
            val image = createMockImage(640, 480)

            // First call populates cache
            image.planes[0].buffer.rewind()
            image.planes[1].buffer.rewind()
            image.planes[2].buffer.rewind()
            image.toBitmapForHandTracking(256)

            val cachedArgbFirst = cache.getArgb(160 * 120)

            // Second call reuses same cached IntArray instance
            image.planes[0].buffer.rewind()
            image.planes[1].buffer.rewind()
            image.planes[2].buffer.rewind()
            image.toBitmapForHandTracking(256)

            val cachedArgbSecond = cache.getArgb(160 * 120)

            assertSame(cachedArgbFirst, cachedArgbSecond)
        }
    }

    @Test
    fun testCustomOutBufferUsage() {
        val mockBitmap = mock(Bitmap::class.java)
        var passedArray: IntArray? = null
        mockStatic(Bitmap::class.java).use { bitmapMock ->
            bitmapMock.`when`<Bitmap> {
                Bitmap.createBitmap(org.mockito.kotlin.any<IntArray>(), anyInt(), anyInt(), anyInt(), anyInt(), org.mockito.kotlin.any())
            }.thenAnswer { invocation ->
                passedArray = invocation.getArgument(0)
                mockBitmap
            }

            val image = createMockImage(640, 480)
            val customBuffer = IntArray(160 * 120)
            val bmp = image.toBitmapForHandTracking(256, customBuffer)

            assertNotNull(bmp)
            assertSame(customBuffer, passedArray)
        }
    }

    @Test
    fun testImageProxyExtensions() {
        val mockBitmap = mock(Bitmap::class.java)
        mockStatic(Bitmap::class.java).use { bitmapMock ->
            bitmapMock.`when`<Bitmap> {
                Bitmap.createBitmap(org.mockito.kotlin.any<IntArray>(), anyInt(), anyInt(), anyInt(), anyInt(), org.mockito.kotlin.any())
            }.thenReturn(mockBitmap)

            val proxy = createMockImageProxy(640, 480)

            proxy.planes[0].buffer.rewind()
            proxy.planes[1].buffer.rewind()
            proxy.planes[2].buffer.rewind()
            val bmp1 = proxy.toBitmapFast()
            assertNotNull(bmp1)

            proxy.planes[0].buffer.rewind()
            proxy.planes[1].buffer.rewind()
            proxy.planes[2].buffer.rewind()
            val bmp2 = proxy.toBitmapFastHalf()
            assertNotNull(bmp2)

            proxy.planes[0].buffer.rewind()
            proxy.planes[1].buffer.rewind()
            proxy.planes[2].buffer.rewind()
            val bmp3 = proxy.toBitmapForHandTracking(256)
            assertNotNull(bmp3)
        }
    }

    @Test
    fun testToBitmapForHandTrackingPerformanceBenchmark() {
        val mockBitmap = mock(Bitmap::class.java)
        mockStatic(Bitmap::class.java).use { bitmapMock ->
            bitmapMock.`when`<Bitmap> {
                Bitmap.createBitmap(org.mockito.kotlin.any<IntArray>(), anyInt(), anyInt(), anyInt(), anyInt(), org.mockito.kotlin.any())
            }.thenReturn(mockBitmap)

            val image = createMockImage(640, 480)

            // Warmup
            repeat(50) {
                image.planes[0].buffer.rewind()
                image.planes[1].buffer.rewind()
                image.planes[2].buffer.rewind()
                image.toBitmapForHandTracking(256)
            }

            val iterations = 1000
            val startTime = System.nanoTime()

            repeat(iterations) {
                image.planes[0].buffer.rewind()
                image.planes[1].buffer.rewind()
                image.planes[2].buffer.rewind()
                val bmp = image.toBitmapForHandTracking(256)
                assertNotNull(bmp)
            }

            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            println("Optimized total time for $iterations iterations: ${durationMs} ms (${durationMs / iterations} ms/op)")
        }
    }
}
