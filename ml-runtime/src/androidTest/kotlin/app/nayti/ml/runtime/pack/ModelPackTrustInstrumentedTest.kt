package app.nayti.ml.runtime.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPackTrustInstrumentedTest {
    @Test
    fun platformEd25519VerifiesPinnedAlphaKey() {
        val key = checkNotNull(AlphaModelPackTrust.keys[ExpectedKeyId])
        val signature = Base64.getDecoder().decode(SelfTestSignature)

        ModelPackSignature.verify(key, "nayti-alpha-key-self-test\n".toByteArray(), signature)

        assertEquals(ExpectedKeyId, key.keyId)
    }

    private companion object {
        const val ExpectedKeyId = "19923be917bc15ccaf15de59f5f78ca5"
        const val SelfTestSignature =
            "oPWkuxcD7kNN7ZTnFGTV5vrO0G/eXVwn6xPnRMYe/jVBL5+Zm6wRPcjH/N59ew87kRHcOjNbhsfu2ZZ0xx3qDg=="
    }
}
