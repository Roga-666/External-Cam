package com.muh.usbcameraviewer.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class LivenessTest {
    @Test
    fun `bright samples are live regardless of history`() {
        assertTrue(samplesIndicateLive(previous = null, current = intArrayOf(0, 0, 200, 0)))
    }

    @Test
    fun `dark but changing samples are live (sensor noise)`() {
        assertTrue(samplesIndicateLive(previous = intArrayOf(3, 5, 2, 4), current = intArrayOf(3, 6, 2, 4)))
    }

    @Test
    fun `dark identical samples are dead`() {
        assertFalse(samplesIndicateLive(previous = intArrayOf(3, 5, 2, 4), current = intArrayOf(3, 5, 2, 4)))
    }

    @Test
    fun `dark samples without history are dead`() {
        assertFalse(samplesIndicateLive(previous = null, current = intArrayOf(3, 5, 2, 4)))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BakeOrientationTest {
    private fun bitmap2x1(
        left: Int,
        right: Int,
    ): Bitmap =
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, left)
            setPixel(1, 0, right)
        }

    @Test
    fun `no-op returns the same bitmap`() {
        val src = bitmap2x1(Color.RED, Color.BLUE)
        assertEquals(src, bakeOrientation(src, rotationDegrees = 0, mirrored = false))
    }

    @Test
    fun `90 degree rotation swaps dimensions`() {
        val out = bakeOrientation(bitmap2x1(Color.RED, Color.BLUE), rotationDegrees = 90, mirrored = false)
        assertEquals(1, out.width)
        assertEquals(2, out.height)
    }

    // Robolectric's native raster drops negative-scale draws, so mirror correctness is asserted
    // on the transform matrix (pure math) rather than on rendered pixels.
    @Test
    fun `mirror matrix maps left pixel to right`() {
        val m = orientationMatrix(width = 2, height = 1, degrees = 0, mirrored = true)
        val point = floatArrayOf(0.5f, 0.5f) // center of the left pixel
        m.mapPoints(point)
        assertEquals(1.5f, point[0], 1e-4f)
        assertEquals(0.5f, point[1], 1e-4f)
    }

    @Test
    fun `90 degree matrix maps into swapped-dimension rect`() {
        val m = orientationMatrix(width = 2, height = 1, degrees = 90, mirrored = false)
        val topLeft = floatArrayOf(0.5f, 0.5f)
        m.mapPoints(topLeft)
        // Output rect is 1x2; the left source pixel lands in the top output pixel.
        assertEquals(0.5f, topLeft[0], 1e-4f)
        assertEquals(0.5f, topLeft[1], 1e-4f)
        val right = floatArrayOf(1.5f, 0.5f)
        m.mapPoints(right)
        assertEquals(0.5f, right[0], 1e-4f)
        assertEquals(1.5f, right[1], 1e-4f)
    }

    @Test
    fun `rotation normalizes negative and over-360 input`() {
        val out = bakeOrientation(bitmap2x1(Color.RED, Color.BLUE), rotationDegrees = 450, mirrored = false)
        assertEquals(1, out.width)
        assertEquals(2, out.height)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PreviewTransformTest {
    private fun shownRect(
        degrees: Int,
        mirrored: Boolean = false,
    ): RectF {
        val m = previewTransformMatrix(640, 480, 1080f, 1920f, degrees, mirrored)
        val rect = RectF(0f, 0f, 1080f, 1920f)
        m.mapRect(rect)
        return rect
    }

    private fun assertRect(
        expected: RectF,
        actual: RectF,
    ) {
        assertEquals(expected.left, actual.left, 1e-3f)
        assertEquals(expected.top, actual.top, 1e-3f)
        assertEquals(expected.right, actual.right, 1e-3f)
        assertEquals(expected.bottom, actual.bottom, 1e-3f)
    }

    @Test
    fun `unrotated stream letterboxes to 4-3 within portrait view`() {
        assertRect(RectF(0f, 555f, 1080f, 1365f), shownRect(degrees = 0))
    }

    @Test
    fun `quarter-turned stream letterboxes with inverted aspect`() {
        assertRect(RectF(0f, 240f, 1080f, 1680f), shownRect(degrees = 90))
    }

    @Test
    fun `mirror does not change the letterbox bounds`() {
        assertRect(shownRect(degrees = 0), shownRect(degrees = 0, mirrored = true))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VisibleStreamRectTest {
    @Test
    fun `zoom 1 sees the whole stream`() {
        val r = visibleStreamRect(640, 480, 1080f, 1920f, 0, false, 1f, 0f, 0f)
        assertEquals(Rect(0, 0, 640, 480), r)
    }

    @Test
    fun `centered 2x zoom sees the middle quarter`() {
        val r = visibleStreamRect(480, 480, 480f, 480f, 0, false, 2f, 0f, 0f)
        assertEquals(Rect(120, 120, 360, 360), r)
    }

    @Test
    fun `pan right shifts the visible region left in stream coords`() {
        val r = visibleStreamRect(480, 480, 480f, 480f, 0, false, 2f, 120f, 0f)
        assertEquals(Rect(60, 120, 300, 360), r)
    }
}
