package app.nayti.search.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun exactTopKScansVerifiedSegmentAndRejectsIncompatibleSpace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val segmentFile = context.cacheDir.resolve("native-exact-top-k.naytivec")
        val embeddingHash = ByteArray(32) { 0x5a }
        val segment = VectorSegmentV1Writer.encode(
            channel = VectorSegmentChannel.OCR_SEMANTIC,
            embeddingSpaceHash = embeddingHash.toHex(),
            records = listOf(
                VectorSegmentRecord(101, 41, 0, byteArrayOf(1, 2, 3)),
                VectorSegmentRecord(102, 42, 0, byteArrayOf(3, 0, -1)),
                VectorSegmentRecord(103, 43, 0, byteArrayOf(2, 2, 0)),
            ),
            segmentId = UUID.fromString("12345678-1234-5678-90ab-cdef01234567"),
        )
        segmentFile.writeBytes(segment.bytes)
        try {
            val hits = NativeVectorIndex.exactTopK(
                path = segmentFile.absolutePath,
                expectedLength = segment.bytes.size.toLong(),
                expectedSha256 = segment.sha256.hexToBytes(),
                query = byteArrayOf(2, 1, 0),
                k = 2,
                channel = VectorSegmentChannel.OCR_SEMANTIC,
                embeddingSpaceHash = embeddingHash,
            )

            assertEquals(
                listOf(
                    NativeVectorSearchHit(recordId = 102, assetId = 42, ordinal = 0, score = 6),
                    NativeVectorSearchHit(recordId = 103, assetId = 43, ordinal = 0, score = 6),
                ),
                hits,
            )
            assertTrue(
                runCatching {
                    NativeVectorIndex.exactTopK(
                        path = segmentFile.absolutePath,
                        expectedLength = segment.bytes.size.toLong(),
                        expectedSha256 = segment.sha256.hexToBytes(),
                        query = byteArrayOf(2, 1, 0),
                        k = 2,
                        channel = VectorSegmentChannel.OCR_SEMANTIC,
                        embeddingSpaceHash = ByteArray(32) { 0x6b },
                    )
                }.isFailure,
            )
            assertArrayEquals(embeddingHash, segment.embeddingSpaceHash.hexToBytes())
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

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
