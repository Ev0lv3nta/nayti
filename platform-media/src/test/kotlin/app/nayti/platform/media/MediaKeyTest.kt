package app.nayti.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaKeyTest {
    @Test
    fun `keeps volume and id as identity`() {
        val key = MediaKey(volumeName = "external_primary", mediaStoreId = 42)

        assertEquals("external_primary", key.volumeName)
        assertEquals(42, key.mediaStoreId)
    }

    @Test
    fun `rejects invalid identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaKey(volumeName = "", mediaStoreId = -1)
        }
    }
}
