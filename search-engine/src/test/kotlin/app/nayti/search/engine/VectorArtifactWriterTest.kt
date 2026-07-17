package app.nayti.search.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorArtifactWriterTest {
    @Test
    fun segmentEncodingIsCanonicalAndMatchesV1Layout() {
        val id = UUID.fromString("12345678-1234-5678-90ab-cdef01234567")
        val records = listOf(
            VectorSegmentRecord(41, 41, 0, byteArrayOf(1, 2, 3)),
            VectorSegmentRecord(42, 42, 0, byteArrayOf(-1, -2, -3)),
        )
        val first = VectorSegmentV1Writer.encode(VectorSegmentChannel.VISUAL, Hash, records, id)
        val second = VectorSegmentV1Writer.encode(VectorSegmentChannel.VISUAL, Hash, records, id)
        assertArrayEquals(first.bytes, second.bytes)
        assertEquals(first.sha256, second.sha256)

        val header = ByteBuffer.wrap(first.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals("NAYTIVEC".encodeToByteArray(), first.bytes.copyOfRange(0, 8))
        assertEquals(1, header.getShort(8).toInt())
        assertEquals(3, header.getInt(16))
        assertEquals(2, header.getInt(20))
        assertEquals(192, header.getLong(36))
        assertEquals(198, first.bytes.size)
        assertTrue(first.bytes.copyOfRange(176, 192).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, -2, -3), first.bytes.copyOfRange(192, 198))
    }

    @Test
    fun manifestEncodingPreservesExplicitSegmentOrder() {
        val encoded = VectorManifestV1.encode(
            revision = "visual-r1",
            generationId = "visual-g1",
            parentRevision = null,
            channel = VectorSegmentChannel.VISUAL,
            embeddingSpaceHash = Hash,
            dimension = 768,
            segments = listOf(
                VectorManifestSegment("segments/a.naytivec", 100, "a".repeat(64), 2),
                VectorManifestSegment("segments/b.naytivec", 120, "b".repeat(64), 3),
            ),
        )
        val text = encoded.bytes.decodeToString()
        assertEquals(5, encoded.recordCount)
        assertTrue(text.indexOf("segments/a.naytivec") < text.indexOf("segments/b.naytivec"))
        assertTrue(text.endsWith("\n"))
    }

    private companion object {
        val Hash = "5a".repeat(32)
    }
}
