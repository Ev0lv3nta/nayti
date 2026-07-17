package app.nayti.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PerceptualHashCodecTest {
    @Test
    fun identityIsDeterministicAndBindsEveryPublicationField() {
        val draft = draft()
        val identity = PerceptualHashCodec.identity(draft)

        assertEquals(identity, PerceptualHashCodec.identity(draft))
        assertEquals(140, identity.byteLength)
        assertNotEquals(identity, PerceptualHashCodec.identity(draft.copy(hashBits = draft.hashBits + 1)))
        assertNotEquals(identity, PerceptualHashCodec.identity(draft.copy(assetId = 8)))
        assertNotEquals(identity, PerceptualHashCodec.identity(draft.copy(accessRevision = 4)))
        assertNotEquals(identity, PerceptualHashCodec.identity(draft.copy(pipelineVersion = "phash-v2")))
    }

    @Test
    fun rejectsInvalidIdentityFields() {
        assertThrows(IllegalArgumentException::class.java) {
            PerceptualHashCodec.identity(draft().copy(assetId = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerceptualHashCodec.identity(draft().copy(componentHash = "not-a-sha"))
        }
    }

    private fun draft(): PerceptualHashDraft =
        PerceptualHashDraft(
            assetId = 7,
            sourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            accessRevision = 3,
            pipelineVersion = "phash-v1",
            componentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            hashBits = 0x1234_5678_0abc_def0,
        )
}
