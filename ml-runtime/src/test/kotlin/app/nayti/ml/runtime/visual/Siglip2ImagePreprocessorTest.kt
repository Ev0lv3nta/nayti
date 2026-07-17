package app.nayti.ml.runtime.visual

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Siglip2ImagePreprocessorTest {
    @Test
    fun normalizesRgbIntoPlanarNchwWithoutChannelSwap() {
        val pixels = IntArray(Siglip2Contract.ImageEdge * Siglip2Contract.ImageEdge)
        pixels[0] = 0xff000000.toInt()
        pixels[1] = 0xffffffff.toInt()
        pixels[2] = 0xffff0000.toInt()

        val tensor = Siglip2ImagePreprocessor.normalizeRgbToNchw(pixels)
        val plane = pixels.size

        assertEquals(-1.0f, tensor[0])
        assertEquals(1.0f, tensor[1])
        assertEquals(1.0f, tensor[2])
        assertEquals(-1.0f, tensor[plane + 2])
        assertEquals(-1.0f, tensor[2 * plane + 2])
        assertEquals(3 * plane, tensor.size)
    }

    @Test
    fun resizeMatchesPinnedPillowBilinearGolden() {
        val source =
            intArrayOf(
                rgb(0, 0, 0), rgb(31, 7, 19), rgb(62, 14, 38), rgb(93, 21, 57), rgb(124, 28, 76),
                rgb(17, 53, 11), rgb(48, 60, 30), rgb(79, 67, 49), rgb(110, 74, 68), rgb(141, 81, 87),
                rgb(34, 106, 22), rgb(65, 113, 41), rgb(96, 120, 60), rgb(127, 127, 79), rgb(158, 134, 98),
            )
        val expected =
            intArrayOf(
                rgb(8, 2, 5), rgb(44, 10, 27), rgb(80, 18, 49), rgb(116, 26, 71),
                rgb(19, 35, 12), rgb(55, 43, 34), rgb(91, 51, 56), rgb(127, 59, 78),
                rgb(31, 75, 20), rgb(67, 83, 42), rgb(103, 91, 64), rgb(139, 99, 86),
                rgb(42, 108, 27), rgb(78, 116, 49), rgb(114, 124, 71), rgb(150, 132, 93),
            )

        assertArrayEquals(expected, Siglip2ImagePreprocessor.resizePillowBilinear(source, 5, 3, 4))
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
}
