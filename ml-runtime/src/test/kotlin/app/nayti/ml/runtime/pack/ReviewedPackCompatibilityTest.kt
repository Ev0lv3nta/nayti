package app.nayti.ml.runtime.pack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedPackCompatibilityTest {
    @Test fun acceptsOnlyReviewedManifestForExactlyTheReviewedRelease() {
        val hash = ReviewedPackCompatibility.Alpha2ManifestSha256
        assertTrue(ReviewedPackCompatibility.accepts(2, hash))
        assertFalse(ReviewedPackCompatibility.accepts(1, hash))
        assertFalse(ReviewedPackCompatibility.accepts(3, hash))
        assertFalse(ReviewedPackCompatibility.accepts(Long.MAX_VALUE, hash))
        assertFalse(ReviewedPackCompatibility.accepts(2, null))
        assertFalse(ReviewedPackCompatibility.accepts(2, "0".repeat(64)))
    }
}
