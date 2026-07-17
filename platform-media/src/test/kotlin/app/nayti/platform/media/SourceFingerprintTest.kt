package app.nayti.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceFingerprintTest {
    @Test
    fun presentationMetadataDoesNotInvalidateDerivedData() {
        val original = observation(displayName = "IMG_1.jpg", relativePath = "DCIM/Camera/")
        val renamed = original.copy(displayName = "renamed.jpg", relativePath = "Pictures/")

        assertEquals(original.fingerprint, renamed.fingerprint)
    }

    @Test
    fun sourceEvidenceChangesFingerprint() {
        val original = observation(displayName = "IMG_1.jpg", relativePath = "DCIM/")

        assertNotEquals(original.fingerprint, original.copy(sizeBytes = 43).fingerprint)
        assertNotEquals(original.fingerprint, original.copy(generationModified = 10).fingerprint)
        assertNotEquals(original.fingerprint, original.copy(orientationDegrees = 90).fingerprint)
    }

    private fun observation(displayName: String, relativePath: String) =
        MediaObservation(
            key = MediaKey("external_primary", 7),
            mimeType = "image/jpeg",
            sizeBytes = 42,
            width = 10,
            height = 20,
            orientationDegrees = 0,
            generationAdded = 8,
            generationModified = 9,
            dateTakenMillis = 1_000,
            dateModifiedSeconds = 2,
            displayName = displayName,
            bucketId = 3,
            bucketDisplayName = "Camera",
            relativePath = relativePath,
            isPending = false,
            isTrashed = false,
        )
}
