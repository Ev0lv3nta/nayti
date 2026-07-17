package app.nayti.ml.runtime.semantic

import app.nayti.ml.runtime.pack.ModelPackException
import app.nayti.ml.runtime.pack.ModelPackManifestParser
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Identity of the exact USER2 embedding space, independent from unrelated pack components. */
object User2EmbeddingSpaceIdentity {
    fun calculate(installedPackDirectory: Path): String {
        val root = installedPackDirectory.toAbsolutePath().normalize()
        val manifestPath = root.resolve("manifest.json").normalize()
        if (
            !manifestPath.startsWith(root) ||
            Files.isSymbolicLink(manifestPath) ||
            !Files.isRegularFile(manifestPath)
        ) {
            throw ModelPackException("Installed model pack manifest is missing or unsafe")
        }
        val manifestLength = Files.size(manifestPath)
        if (manifestLength !in 1..ModelPackManifestParser.MaxManifestBytes.toLong()) {
            throw ModelPackException("Installed model pack manifest has an invalid size")
        }
        val raw = Files.readAllBytes(manifestPath)
        val manifest = ModelPackManifestParser.parse(raw)
        val selected = RequiredArtifacts.map { path ->
            manifest.files.singleOrNull { file -> file.path == path }
                ?: throw ModelPackException("USER2 artifact is missing from the pack manifest: $path")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.field(IdentityVersion)
        selected.forEach { artifact ->
            digest.field(artifact.path)
            digest.field(artifact.sha256)
            digest.long(artifact.length)
        }
        digest.int(User2Contract.SequenceLength)
        digest.int(User2Contract.EmbeddingDimension)
        digest.int(User2Contract.SpecialTokenCount)
        digest.int(User2Contract.MaximumContentTokens)
        digest.field(User2Contract.DocumentPrefix)
        digest.field(User2Contract.QueryPrefix)
        digest.field(User2Contract.OrtVersion)
        digest.field(User2Contract.VectorEncodingVersion)
        digest.field("unicode-nfc")
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        int(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.int(value: Int) = update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

    private fun MessageDigest.long(value: Long) = update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

    private const val IdentityVersion = "nayti-user2-embedding-space-v1"
    private val RequiredArtifacts = listOf("models/user2_encoder.ort", "models/user2_tokenizer.ort")
}
