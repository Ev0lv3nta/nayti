package app.nayti.ml.runtime.pack

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.util.Base64

class TrustedModelPackKey(val keyId: String, publicKeyBytes: ByteArray) {
    internal val publicKeyBytes: ByteArray = publicKeyBytes.clone()

    init {
        require(publicKeyBytes.size == Ed25519PublicKeyBytes)
    }

    private companion object {
        const val Ed25519PublicKeyBytes = 32
    }
}

object AlphaModelPackTrust {
    val keys: Map<String, TrustedModelPackKey> =
        listOf(
            trustedKey(
                expectedKeyId = "19923be917bc15ccaf15de59f5f78ca5",
                x509Base64 = "MCowBQYDK2VwAyEAWyRMH9iqNisOYN6+XUdcFp7eviSdmEI8O1xcPYE0CSA=",
            ),
        ).associateBy(TrustedModelPackKey::keyId)
}

internal object ModelPackSignature {
    private val DomainSeparator = "NAYTI_MODEL_PACK_SIGNATURE_V1\u0000".toByteArray(Charsets.US_ASCII)

    fun verify(key: TrustedModelPackKey, manifest: ByteArray, signature: ByteArray) {
        try {
            val message = ByteArray(DomainSeparator.size + manifest.size)
            DomainSeparator.copyInto(message)
            manifest.copyInto(message, DomainSeparator.size)
            Ed25519Verify(key.publicKeyBytes).verify(signature, message)
        } catch (failure: Exception) {
            throw ModelPackException("Model pack signature is invalid", failure)
        }
    }
}

private fun trustedKey(expectedKeyId: String, x509Base64: String): TrustedModelPackKey {
    val encoded = Base64.getDecoder().decode(x509Base64)
    val actualKeyId = MessageDigest.getInstance("SHA-256").digest(encoded).toHex().take(32)
    check(actualKeyId == expectedKeyId) { "Embedded model-pack key ID mismatch" }
    val x509Prefix = "302a300506032b6570032100".hexToByteArray()
    if (encoded.size != x509Prefix.size + 32 || !encoded.copyOfRange(0, x509Prefix.size).contentEquals(x509Prefix)) {
        throw IllegalStateException("Embedded model-pack key is not canonical Ed25519 X.509")
    }
    return TrustedModelPackKey(actualKeyId, encoded.copyOfRange(x509Prefix.size, encoded.size))
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { octet -> octet.toInt(16).toByte() }.toByteArray()
