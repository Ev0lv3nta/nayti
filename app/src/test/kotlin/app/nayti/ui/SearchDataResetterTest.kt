package app.nayti.ui

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchDataResetterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun boundedDeleteDoesNotFollowSymbolicLinksOutsideRoot() {
        val root = temporaryFolder.newFolder("vectors").toPath()
        val nested = Files.createDirectories(root.resolve("segments"))
        Files.writeString(nested.resolve("segment.bin"), "segment")
        val outside = temporaryFolder.newFile("outside.bin").toPath()
        Files.writeString(outside, "keep")
        Files.createSymbolicLink(root.resolve("outside-link"), outside)

        deleteBoundedTree(root, maximumEntries = 10)

        assertFalse(Files.exists(root))
        assertTrue(Files.exists(outside))
    }

    @Test(expected = IllegalStateException::class)
    fun boundedDeleteStopsAnUnexpectedlyLargeTree() {
        val root = temporaryFolder.newFolder("oversized").toPath()
        Files.writeString(root.resolve("first"), "1")
        Files.writeString(root.resolve("second"), "2")

        deleteBoundedTree(root, maximumEntries = 1)
    }
}
