package app.nayti.ml.runtime.ocr

import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrDetectorContractTest {
    @Test
    fun resizePlannerMatchesPinnedPaddleContract() {
        assertPlan(1_280, 960, 1_280, 960)
        assertPlan(960, 736, 960, 736)
        assertPlan(200, 100, 1_472, 736)
        assertPlan(1_000, 100, 4_000, 384)
        assertPlan(1_920, 1_920, 1_920, 1_920)
    }

    @Test
    fun normalizationIsBgrNchwContractOrder() {
        val normalized = OcrDetectorContract.normalizedBgr(0xff3366cc.toInt())
        assertEquals((0xcc / 255.0f - 0.485f) / 0.229f, normalized[0], 1e-6f)
        assertEquals((0x66 / 255.0f - 0.456f) / 0.224f, normalized[1], 1e-6f)
        assertEquals((0x33 / 255.0f - 0.406f) / 0.225f, normalized[2], 1e-6f)
    }

    @Test
    fun directBufferIsExclusiveBoundedAndReusable() {
        OcrTensorBufferPool(maximumFloats = 32).use { pool ->
            pool.acquire(longArrayOf(1, 2, 4)).use { lease ->
                assertArrayEquals(longArrayOf(1, 2, 4), lease.shape)
                assertEquals(ByteOrder.nativeOrder(), lease.writableBuffer().order())
                assertThrows(IllegalStateException::class.java) {
                    pool.acquire(longArrayOf(1))
                }
            }
            pool.acquire(longArrayOf(32)).use { lease ->
                assertEquals(32, lease.readableBuffer().remaining())
            }
            assertThrows(IllegalArgumentException::class.java) {
                pool.acquire(longArrayOf(33))
            }
        }
    }

    private fun assertPlan(
        sourceWidth: Int,
        sourceHeight: Int,
        tensorWidth: Int,
        tensorHeight: Int,
    ) {
        val plan = OcrDetectorResizePlanner.plan(sourceWidth, sourceHeight)
        assertEquals(tensorWidth, plan.tensorWidth)
        assertEquals(tensorHeight, plan.tensorHeight)
    }
}
