package app.nayti.ml.runtime.visual

import app.nayti.ml.runtime.pack.ModelPackException
import app.nayti.ml.runtime.pack.ModelPackManifestParser
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Identity shared by the compatible SigLIP2 image tower, text tower, tokenizer and preprocessing. */
object Siglip2EmbeddingSpaceIdentity {
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
        val manifest = ModelPackManifestParser.parse(Files.readAllBytes(manifestPath))
        val selected = RequiredArtifacts.map { path ->
            manifest.files.singleOrNull { file -> file.path == path }
                ?: throw ModelPackException("SigLIP2 artifact is missing from the pack manifest: $path")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field(IdentityVersion)
        selected.forEach { artifact ->
            digest.field(artifact.path)
            digest.field(artifact.sha256)
            digest.long(artifact.length)
        }
        digest.int(Siglip2Contract.ImageEdge)
        digest.int(Siglip2Contract.DecodeLongSide)
        digest.int(Siglip2Contract.EmbeddingDimension)
        digest.field(Siglip2Contract.ResizeContract)
        digest.field("rgb-x-over-127.5-minus-1-nchw")
        digest.field(Siglip2Contract.OrtVersion)
        digest.field(Siglip2Contract.VectorEncodingVersion)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        int(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.int(value: Int) = update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

    private fun MessageDigest.long(value: Long) = update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

    private const val IdentityVersion = "nayti-siglip2-embedding-space-v1"
    private val RequiredArtifacts =
        listOf(
            "models/siglip2_image.ort",
            "models/siglip2_text.ort",
            "models/siglip2_tokenizer.ort",
        )
}
