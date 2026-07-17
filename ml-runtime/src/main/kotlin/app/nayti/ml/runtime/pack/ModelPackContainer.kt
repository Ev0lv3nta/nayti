package app.nayti.ml.runtime.pack

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ModelPackSource {
    fun openStream(): InputStream
}

fun interface ModelPackStorageBudget {
    fun allocatableBytes(path: Path): Long
}

class FileModelPackSource(private val path: Path) : ModelPackSource {
    override fun openStream(): InputStream = Files.newInputStream(path)
}

fun interface ModelPackCandidateInstaller {
    suspend fun install(source: ModelPackSource): VerifiedModelPack
}

class ModelPackInstaller(
    private val root: Path,
    private val trustedKeys: Map<String, TrustedModelPackKey>,
    private val policy: ModelPackPolicy,
    private val storageBudget: ModelPackStorageBudget,
    private val payloadValidator: ModelPackPayloadValidator,
    private val minimumFreeBytesAfterInstall: Long = 512L * 1024 * 1024,
) : ModelPackCandidateInstaller {
    override suspend fun install(source: ModelPackSource): VerifiedModelPack =
        withContext(Dispatchers.IO) {
            Files.createDirectories(root)
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
                throw ModelPackException("Model pack root is not a private directory")
            }
            cleanupInterruptedImports()
            val token = UUID.randomUUID().toString()
            val incoming = root.resolve(".incoming-$token.naytipack")
            val staging = root.resolve(".staging-$token")
            try {
                copyBounded(source, incoming)
                val staged = verifyAndExtract(incoming, staging)
                payloadValidator.validate(staged.validationCandidate(staging))
                verifyPayload(staging.resolve("payload"), staged.manifest)
                publish(staged, staging)
            } finally {
                Files.deleteIfExists(incoming)
                if (Files.exists(staging)) deleteTree(staging)
            }
        }

    suspend fun cleanupInterruptedImports() =
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(root)) return@withContext
            Files.list(root).use { children ->
                children.filter { child ->
                    val name = child.fileName.toString()
                    name.startsWith(".incoming-") || name.startsWith(".staging-")
                }.forEach { child ->
                    if (Files.isDirectory(child)) deleteTree(child) else Files.deleteIfExists(child)
                }
            }
        }

    private fun copyBounded(source: ModelPackSource, destination: Path) {
        val maximumContainerBytes =
            ModelPackManifestParser.MaxTotalPayloadBytes +
                ModelPackManifestParser.MaxManifestBytes +
                ContainerOverheadBytes
        var copied = 0L
        source.openStream().use { input ->
            FileOutputStream(destination.toFile()).use { output ->
                val buffer = ByteArray(CopyBufferBytes)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    copied = Math.addExact(copied, count.toLong())
                    if (copied > maximumContainerBytes) throw ModelPackException("Model pack exceeds container size cap")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun verifyAndExtract(container: Path, staging: Path): StagedPack {
        if (Files.isSymbolicLink(container) || !Files.isRegularFile(container)) {
            throw ModelPackException("Model pack container is not a regular file")
        }
        Files.createDirectory(staging)
        val payloadRoot = staging.resolve("payload")
        Files.createDirectory(payloadRoot)
        DataInputStream(BufferedInputStream(Files.newInputStream(container), CopyBufferBytes)).use { input ->
            val magic = input.readExact(Magic.size)
            if (!magic.contentEquals(Magic)) throw ModelPackException("Invalid model pack magic")
            val manifestLength = input.readInt()
            val signatureLength = input.readUnsignedShort()
            val reserved = input.readUnsignedShort()
            if (manifestLength !in 1..ModelPackManifestParser.MaxManifestBytes) {
                throw ModelPackException("Manifest length is outside the allowed range")
            }
            if (signatureLength != SignatureBytes || reserved != 0) {
                throw ModelPackException("Invalid model pack header")
            }
            val manifestBytes = input.readExact(manifestLength)
            val signature = input.readExact(signatureLength)
            val manifest = ModelPackManifestParser.parse(manifestBytes)
            val trustedKey = trustedKeys[manifest.keyId] ?: throw ModelPackException("Model pack key is not trusted")
            ModelPackSignature.verify(trustedKey, manifestBytes, signature)
            policy.validate(manifest)
            val requiredFree = Math.addExact(manifest.totalPayloadBytes, minimumFreeBytesAfterInstall)
            if (storageBudget.allocatableBytes(staging) < requiredFree) {
                throw ModelPackException("Insufficient private storage for model pack")
            }

            manifest.files.forEach { descriptor -> extractFile(input, payloadRoot, descriptor) }
            if (input.read() != -1) throw ModelPackException("Model pack contains trailing payload")
            writeSynced(staging.resolve("manifest.json"), manifestBytes)
            writeSynced(staging.resolve("signature.ed25519"), signature)
            val manifestSha256 = MessageDigest.getInstance("SHA-256").digest(manifestBytes).toHex()
            return StagedPack(manifest, manifestSha256)
        }
    }

    private fun extractFile(input: DataInputStream, payloadRoot: Path, descriptor: ModelPackFile) {
        val destination = payloadRoot.resolve(descriptor.path).normalize()
        if (!destination.startsWith(payloadRoot)) throw ModelPackException("Payload path escapes staging")
        Files.createDirectories(destination.parent)
        if (Files.exists(destination)) throw ModelPackException("Payload destination already exists")
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = descriptor.length
        FileOutputStream(destination.toFile()).use { output ->
            val buffer = ByteArray(CopyBufferBytes)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw ModelPackException("Truncated payload: ${descriptor.path}")
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        if (digest.digest().toHex() != descriptor.sha256) {
            throw ModelPackException("Payload SHA-256 mismatch: ${descriptor.path}")
        }
    }

    private fun publish(staged: StagedPack, staging: Path): VerifiedModelPack {
        val directoryName = "${staged.manifest.packVersion}-${staged.manifestSha256.take(16)}"
        val packRoot = root.resolve(staged.manifest.packId)
        Files.createDirectories(packRoot)
        val destination = packRoot.resolve(directoryName)
        if (Files.exists(destination)) {
            val existingManifest = destination.resolve("manifest.json")
            if (!Files.isRegularFile(existingManifest) || sha256(existingManifest) != staged.manifestSha256) {
                throw ModelPackException("Installed candidate path collision")
            }
            verifyPayload(destination.resolve("payload"), staged.manifest)
            deleteTree(staging)
        } else {
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: Exception) {
                throw ModelPackException("Atomic candidate publication failed", failure)
            }
        }
        return VerifiedModelPack(
            packId = staged.manifest.packId,
            packVersion = staged.manifest.packVersion,
            keyId = staged.manifest.keyId,
            manifestSha256 = staged.manifestSha256,
            payloadBytes = staged.manifest.totalPayloadBytes,
            directory = destination,
        )
    }

    private fun verifyPayload(payloadRoot: Path, manifest: ModelPackManifest) {
        manifest.files.forEach { descriptor ->
            val file = payloadRoot.resolve(descriptor.path).normalize()
            if (!file.startsWith(payloadRoot) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                throw ModelPackException("Installed candidate payload is missing: ${descriptor.path}")
            }
            if (Files.size(file) != descriptor.length || sha256(file) != descriptor.sha256) {
                throw ModelPackException("Installed candidate payload is corrupt: ${descriptor.path}")
            }
        }
    }

    private data class StagedPack(val manifest: ModelPackManifest, val manifestSha256: String) {
        fun validationCandidate(staging: Path) =
            ModelPackValidationCandidate(
                packId = manifest.packId,
                packVersion = manifest.packVersion,
                keyId = manifest.keyId,
                manifestSha256 = manifestSha256,
                payloadBytes = manifest.totalPayloadBytes,
                directory = staging,
            )
    }

    private companion object {
        val Magic = "NAYTIPK1".toByteArray(Charsets.US_ASCII)
        const val SignatureBytes = 64
        const val CopyBufferBytes = 4 * 1024 * 1024
        const val ContainerOverheadBytes = 16L + SignatureBytes
    }
}

private fun DataInputStream.readExact(length: Int): ByteArray {
    val result = ByteArray(length)
    try {
        readFully(result)
    } catch (failure: java.io.EOFException) {
        throw ModelPackException("Truncated model pack header, manifest, or signature", failure)
    }
    return result
}

private fun writeSynced(path: Path, bytes: ByteArray) {
    FileOutputStream(path.toFile()).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
