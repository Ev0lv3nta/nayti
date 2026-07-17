package app.nayti.ml.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeInstrumentedTest {
    @Test
    fun loadsNativeRuntimeAndReadsContractVersion() {
        assertEquals(1, NativeRuntime.contractVersion())
    }
}
