package app.nayti.search.engine.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashV1Test {
    @Test
    fun identicalPixelsProduceStableHash() {
        val image = fixture(47, 29)

        assertEquals(17_876_130_732_124_735L, PerceptualHashV1.compute(image))
    }

    @Test
    fun uniformBrightnessDoesNotChangeHash() {
        val black = ArgbImage(32, 32, IntArray(32 * 32) { 0xff000000.toInt() })
        val white = ArgbImage(32, 32, IntArray(32 * 32) { 0xffffffff.toInt() })

        assertEquals(PerceptualHashV1.compute(black), PerceptualHashV1.compute(white))
    }

    @Test
    fun changedStructureProducesNonZeroSymmetricDistance() {
        val original = PerceptualHashV1.compute(fixture(47, 29))
        val mirrored = PerceptualHashV1.compute(fixture(47, 29, mirror = true))

        assertNotEquals(original, mirrored)
        assertEquals(
            PerceptualHashV1.hammingDistance(original, mirrored),
            PerceptualHashV1.hammingDistance(mirrored, original),
        )
        assertTrue(PerceptualHashV1.hammingDistance(original, mirrored) in 1..64)
    }

    @Test
    fun transparentRgbIsCompositedOnWhite() {
        val transparentBlack = ArgbImage(8, 8, IntArray(64) { 0x00000000 })
        val opaqueWhite = ArgbImage(8, 8, IntArray(64) { 0xffffffff.toInt() })

        assertEquals(PerceptualHashV1.compute(opaqueWhite), PerceptualHashV1.compute(transparentBlack))
    }

    private fun fixture(width: Int, height: Int, mirror: Boolean = false): ArgbImage =
        ArgbImage(
            width = width,
            height = height,
            pixels =
                IntArray(width * height) { index ->
                    val x = index % width
                    val y = index / width
                    val sourceX = if (mirror) width - 1 - x else x
                    val red = sourceX * 255 / (width - 1)
                    val green = y * 255 / (height - 1)
                    val blue = if ((sourceX / 5 + y / 3) % 2 == 0) 220 else 24
                    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                },
        )
}
