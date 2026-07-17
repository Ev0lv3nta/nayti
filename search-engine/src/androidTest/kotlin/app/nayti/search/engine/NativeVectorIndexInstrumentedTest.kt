package app.nayti.search.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVectorIndexInstrumentedTest {
    @Test
    fun loadsNativeIndexAndMatchesScalarKernel() {
        assertEquals(1, NativeVectorIndex.contractVersion())
        assertTrue(NativeVectorIndex.optimizedDotMatchesScalar(seed = 0x4e415954, cases = 500))
    }

    @Test
    fun mapsAndParsesSegmentFromAndroidFilesystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val segmentFile = context.cacheDir.resolve("native-vector-proof.naytivec")
        val segment = goldenSegment()
        segmentFile.writeBytes(segment)
        try {
            assertEquals(
                1,
                NativeVectorIndex.mappedRecordCount(
                    path = segmentFile.absolutePath,
                    expectedLength = segment.size.toLong(),
                    expectedSha256 = MessageDigest.getInstance("SHA-256").digest(segment),
                ),
            )
        } finally {
            segmentFile.delete()
        }
    }

    private fun goldenSegment(): ByteArray {
        val payloadOffset = 192
        return ByteBuffer.allocate(payloadOffset + 3)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("NAYTIVEC".encodeToByteArray())
                putShort(1)
                putShort(128)
                put(1)
                put(1)
                put(1)
                put(0)
                putInt(3)
                putInt(1)
                putShort(24)
                putShort(0)
                putLong(128)
                putLong(payloadOffset.toLong())
                putLong(3)
                putLong((payloadOffset + 3).toLong())
                position(60)
                put(0x5a)
                position(92)
                put(0x7f)
                position(128)
                putLong(41)
                putLong(41)
                putInt(0)
                putInt(0)
                position(payloadOffset)
                put(byteArrayOf(7, 8, 9))
            }
            .array()
    }
}
