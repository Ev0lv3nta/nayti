package app.nayti.indexer

import app.nayti.ml.runtime.ocr.OcrOrtRuntime
import app.nayti.ml.runtime.ocr.OrtOcrInferenceEngine
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.OcrDao
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelPackUnavailableException(message: String) : Exception(message)

data class InstalledOcrPack(
    val registryEntry: ModelPackEntity,
    val payloadDirectory: Path,
) {
    val componentHash: String = registryEntry.manifestSha256
}

/** Resolves only immutable pack directories previously registered after signed installation. */
class InstalledOcrPackResolver(
    private val registry: ModelPackDao,
    modelPackRoot: Path,
) {
    private val root = modelPackRoot.toAbsolutePath().normalize()

    suspend fun resolve(packId: String, packVersion: String): InstalledOcrPack =
        withContext(Dispatchers.IO) {
            val entry = registry.pack(packId, packVersion)
                ?: throw ModelPackUnavailableException("Requested model pack is not installed")
            if (entry.status != ModelPackStatus.INSTALLED_CANDIDATE) {
                throw ModelPackUnavailableException("Requested model pack is not executable")
            }
            val relative = safeRelativePath(entry.relativeDirectory)
            val expectedDirectory = "$packVersion-${entry.manifestSha256.take(16)}"
            if (
                relative.nameCount != 2 ||
                relative.getName(0).toString() != packId ||
                relative.getName(1).toString() != expectedDirectory
            ) {
                throw ModelPackUnavailableException("Model pack registry path does not match its identity")
            }
            val directory = root.resolve(relative).normalize()
            if (!directory.startsWith(root)) {
                throw ModelPackUnavailableException("Model pack path escaped private storage")
            }
            val packRoot = directory.parent
            val payload = directory.resolve("payload")
            if (
                Files.isSymbolicLink(root) ||
                Files.isSymbolicLink(packRoot) ||
                Files.isSymbolicLink(directory) ||
                Files.isSymbolicLink(payload) ||
                !Files.isDirectory(directory) ||
                !Files.isDirectory(payload)
            ) {
                throw ModelPackUnavailableException("Installed model pack directory is missing or unsafe")
            }
            val manifest = directory.resolve("manifest.json")
            if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) {
                throw ModelPackUnavailableException("Installed model pack manifest is missing or unsafe")
            }
            val manifestLength = Files.size(manifest)
            if (manifestLength !in 1..MaximumManifestBytes) {
                throw ModelPackUnavailableException("Installed model pack manifest has an invalid size")
            }
            if (sha256(manifest) != entry.manifestSha256) {
                throw ModelPackUnavailableException("Installed model pack manifest identity changed")
            }
            InstalledOcrPack(entry, payload)
        }

    private fun safeRelativePath(value: String): Path {
        val names = value.split('/')
        if (value.isBlank() || value.startsWith('/') || value.contains('\\')) {
            throw ModelPackUnavailableException("Model pack registry path is not relative")
        }
        if (names.any { name -> name.isEmpty() || name == "." || name == ".." }) {
            throw ModelPackUnavailableException("Model pack registry path contains an unsafe segment")
        }
        val candidate = root.resolve(value).normalize()
        if (!candidate.startsWith(root)) {
            throw ModelPackUnavailableException("Model pack registry path escaped private storage")
        }
        return root.relativize(candidate)
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(HashBufferBytes)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val MaximumManifestBytes = 1024L * 1024
        const val HashBufferBytes = 64 * 1024
    }
}

/** Owns the native OCR sessions for exactly one bounded execution window. */
class OcrExecutionSession private constructor(
    val pack: InstalledOcrPack,
    val executor: OcrChannelExecutor,
    private val engine: OrtOcrInferenceEngine,
) : AutoCloseable {
    override fun close() = engine.close()

    companion object {
        suspend fun open(
            packId: String,
            packVersion: String,
            resolver: InstalledOcrPackResolver,
            ocr: OcrDao,
            decoder: BoundedMediaDecoder,
            clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
        ): OcrExecutionSession {
            val pack = resolver.resolve(packId, packVersion)
            val engine =
                withContext(Dispatchers.Default) {
                    OrtOcrInferenceEngine(OcrOrtRuntime.open(pack.payloadDirectory))
                }
            return OcrExecutionSession(
                pack = pack,
                executor = OcrChannelExecutor(ocr, decoder, engine, clock = clock),
                engine = engine,
            )
        }
    }
}
