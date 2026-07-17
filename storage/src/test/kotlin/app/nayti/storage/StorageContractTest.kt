package app.nayti.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageContractTest {
    @Test
    fun `database identity is stable`() {
        assertEquals("nayti.db", StorageContract.DatabaseFileName)
        assertEquals(1, StorageContract.InitialSchemaVersion)
    }
}
