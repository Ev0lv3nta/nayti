package app.nayti.ml.runtime.pack

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackManifestTest {
    @Test
    fun canonicalManifestRoundTripsAndParses() {
        val raw = CanonicalJson.encode(minimalManifest())

        val parsed = ModelPackManifestParser.parse(raw)

        assertEquals("nayti-test", parsed.packId)
        assertEquals("0.1.0-alpha.1", parsed.packVersion)
        assertEquals(7L, parsed.totalPayloadBytes)
        assertTrue(raw.contentEquals(CanonicalJson.encode(CanonicalJson.parseCanonical(raw))))
    }

    @Test
    fun duplicateKeysFloatsAndNonCanonicalJsonAreRejected() {
        assertFails("Duplicate JSON key") {
            CanonicalJson.parseCanonical("{\"a\":1,\"a\":2}\n".toByteArray())
        }
        assertFails("Floating-point") {
            CanonicalJson.parseCanonical("{\"a\":1.0}\n".toByteArray())
        }
        assertFails("not canonical") {
            CanonicalJson.parseCanonical("{\"b\":2,\"a\":1}\n".toByteArray())
        }
        assertFails("not canonical") {
            CanonicalJson.parseCanonical("{\"a\":1}".toByteArray())
        }
    }

    @Test
    fun ed25519SignatureUsesDomainSeparation() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).toHex().take(32)
        val trusted = TrustedModelPackKey(keyId, keyPair.public.encoded.takeLast(32).toByteArray())
        val manifest = CanonicalJson.encode(minimalManifest())
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update("NAYTI_MODEL_PACK_SIGNATURE_V1\u0000".toByteArray(Charsets.US_ASCII))
        signer.update(manifest)
        val signature = signer.sign()

        ModelPackSignature.verify(trusted, manifest, signature)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertFails("signature is invalid") {
            ModelPackSignature.verify(trusted, manifest, signature)
        }
    }

    @Test
    fun embeddedAlphaKeyHasPinnedIdentity() {
        assertEquals(
            setOf("19923be917bc15ccaf15de59f5f78ca5"),
            AlphaModelPackTrust.keys.keys,
        )
    }

    private fun minimalManifest(): JsonValue.ObjectValue =
        obj(
            "schemaVersion" to integer(1),
            "packId" to string("nayti-test"),
            "packVersion" to string("0.1.0-alpha.1"),
            "keyId" to string("0".repeat(32)),
            "compatibility" to obj("engineApi" to integer(1)),
            "runtime" to obj("format" to string("ORT")),
            "components" to array(obj("componentId" to string("test"))),
            "files" to
                array(
                    obj(
                        "path" to string("models/test.ort"),
                        "role" to string("model"),
                        "length" to integer(7),
                        "sha256" to string("0".repeat(64)),
                    ),
                ),
            "provenance" to obj("containsUserData" to JsonValue.BooleanValue(false)),
        )

    private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*entries))
    private fun array(vararg values: JsonValue) = JsonValue.ArrayValue(values.toList())
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun integer(value: Long) = JsonValue.IntegerValue(value)

    private fun assertFails(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected ModelPackException containing '$message', got $failure", failure is ModelPackException)
        assertTrue(failure?.message.orEmpty().contains(message, ignoreCase = true))
    }
}
