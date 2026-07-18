package app.nayti.indexer

import app.nayti.ml.runtime.ocr.OcrOrtRuntime
import app.nayti.ml.runtime.ocr.OrtOcrInferenceEngine
import app.nayti.ml.runtime.semantic.User2Contract
import app.nayti.ml.runtime.semantic.User2EmbeddingSpaceIdentity
import app.nayti.ml.runtime.semantic.User2OrtRuntime
import app.nayti.ml.runtime.visual.Siglip2Contract
import app.nayti.ml.runtime.visual.Siglip2EmbeddingSpaceIdentity
import app.nayti.ml.runtime.visual.Siglip2ImageOrtRuntime
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.storage.IndexChannel
import app.nayti.storage.IndexStateDao
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import app.nayti.storage.OcrDao
import app.nayti.storage.OcrSemanticDao
import app.nayti.storage.PerceptualHashDao
import app.nayti.storage.VectorGenerationEntity
import app.nayti.storage.VectorGenerationState
import app.nayti.storage.VectorIndexDao
import java.io.File
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

/** Owns USER2 sessions and one compatible semantic vector generation for a bounded window. */
class OcrSemanticExecutionSession private constructor(
    val pack: InstalledOcrPack,
    val generation: VectorGenerationEntity,
    val executor: OcrSemanticChannelExecutor,
    private val runtime: User2OrtRuntime,
) : AutoCloseable {
    override fun close() = runtime.close()

    companion object {
        suspend fun open(
            packId: String,
            packVersion: String,
            resolver: InstalledOcrPackResolver,
            indexState: IndexStateDao,
            semantic: OcrSemanticDao,
            vectors: VectorIndexDao,
            vectorRoot: File,
            candidateSnapshotId: String? = null,
            clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
        ): OcrSemanticExecutionSession {
            val pack = resolver.resolve(packId, packVersion)
            val embeddingSpaceHash =
                withContext(Dispatchers.IO) {
                    User2EmbeddingSpaceIdentity.calculate(pack.payloadDirectory.parent)
                }
            val generationId =
                "semantic-${pack.componentHash.take(12)}-${embeddingSpaceHash.take(32)}"
            val existing = vectors.generation(generationId)
            val generation =
                existing
                    ?: VectorGenerationEntity(
                        generationId = generationId,
                        channel = IndexChannel.OCR_SEMANTIC,
                        packId = pack.registryEntry.packId,
                        packVersion = pack.registryEntry.packVersion,
                        pipelineVersion = OcrSemanticChannelExecutor.PipelineVersion,
                        componentHash = pack.componentHash,
                        embeddingSpaceHash = embeddingSpaceHash,
                        dimension = User2Contract.EmbeddingDimension,
                        state = VectorGenerationState.BUILDING,
                        createdAtMillis = clock.nowMillis(),
                        sealedAtMillis = null,
                    ).also { vectors.createGeneration(it) }
            check(
                generation.channel == IndexChannel.OCR_SEMANTIC &&
                    generation.packId == pack.registryEntry.packId &&
                    generation.packVersion == pack.registryEntry.packVersion &&
                    generation.pipelineVersion == OcrSemanticChannelExecutor.PipelineVersion &&
                    generation.componentHash == pack.componentHash &&
                    generation.embeddingSpaceHash == embeddingSpaceHash &&
                    generation.dimension == User2Contract.EmbeddingDimension &&
                    generation.state == VectorGenerationState.BUILDING
            ) { "Installed USER2 generation does not match its execution contract" }

            val runtime =
                withContext(Dispatchers.Default) {
                    User2OrtRuntime.open(pack.payloadDirectory)
                }
            return OcrSemanticExecutionSession(
                pack = pack,
                generation = generation,
                executor =
                    OcrSemanticChannelExecutor(
                        indexState = indexState,
                        semantic = semantic,
                        embedding = User2SemanticEmbeddingEngine(runtime),
                        publisher =
                            VectorPublicationStore(
                                rootDirectory = vectorRoot,
                                dao = vectors,
                                nowMillis = clock::nowMillis,
                            ).let { store ->
                                if (candidateSnapshotId == null) {
                                    VectorStoreSemanticPublisher(store)
                                } else {
                                    ShadowVectorStoreSemanticPublisher(store, vectors, candidateSnapshotId)
                                }
                            },
                        generationId = generation.generationId,
                        clock = clock,
                    ),
                runtime = runtime,
            )
        }
    }
}

/** Owns the SigLIP2 image session and one compatible visual generation for a bounded window. */
class VisualExecutionSession private constructor(
    val pack: InstalledOcrPack,
    val generation: VectorGenerationEntity,
    val executor: VisualChannelExecutor,
    private val runtime: Siglip2ImageOrtRuntime,
) : AutoCloseable {
    override fun close() = runtime.close()

    companion object {
        suspend fun open(
            packId: String,
            packVersion: String,
            resolver: InstalledOcrPackResolver,
            indexState: IndexStateDao,
            semantic: OcrSemanticDao,
            hashes: PerceptualHashDao,
            vectors: VectorIndexDao,
            decoder: BoundedMediaDecoder,
            vectorRoot: File,
            candidateSnapshotId: String? = null,
            clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
        ): VisualExecutionSession {
            val pack = resolver.resolve(packId, packVersion)
            val embeddingSpaceHash =
                withContext(Dispatchers.IO) {
                    Siglip2EmbeddingSpaceIdentity.calculate(pack.payloadDirectory.parent)
                }
            val generationId =
                "visual-${pack.componentHash.take(12)}-${embeddingSpaceHash.take(32)}"
            val existing = vectors.generation(generationId)
            val generation =
                existing
                    ?: VectorGenerationEntity(
                        generationId = generationId,
                        channel = IndexChannel.VISUAL,
                        packId = pack.registryEntry.packId,
                        packVersion = pack.registryEntry.packVersion,
                        pipelineVersion = Siglip2Contract.PipelineVersion,
                        componentHash = pack.componentHash,
                        embeddingSpaceHash = embeddingSpaceHash,
                        dimension = Siglip2Contract.EmbeddingDimension,
                        state = VectorGenerationState.BUILDING,
                        createdAtMillis = clock.nowMillis(),
                        sealedAtMillis = null,
                    ).also { vectors.createGeneration(it) }
            check(
                generation.channel == IndexChannel.VISUAL &&
                    generation.packId == pack.registryEntry.packId &&
                    generation.packVersion == pack.registryEntry.packVersion &&
                    generation.pipelineVersion == Siglip2Contract.PipelineVersion &&
                    generation.componentHash == pack.componentHash &&
                    generation.embeddingSpaceHash == embeddingSpaceHash &&
                    generation.dimension == Siglip2Contract.EmbeddingDimension &&
                    generation.state == VectorGenerationState.BUILDING
            ) { "Installed SigLIP2 generation does not match its execution contract" }

            val runtime =
                withContext(Dispatchers.Default) {
                    Siglip2ImageOrtRuntime.open(pack.payloadDirectory)
                }
            return VisualExecutionSession(
                pack = pack,
                generation = generation,
                executor =
                    VisualChannelExecutor(
                        indexState = indexState,
                        semantic = semantic,
                        hashes = hashes,
                        decoder = decoder,
                        embedding = Siglip2VisualEmbeddingEngine(runtime),
                        publisher =
                            VectorPublicationStore(
                                rootDirectory = vectorRoot,
                                dao = vectors,
                                nowMillis = clock::nowMillis,
                            ).let { store ->
                                if (candidateSnapshotId == null) {
                                    VectorStoreVisualPublisher(store)
                                } else {
                                    ShadowVectorStoreVisualPublisher(store, vectors, candidateSnapshotId)
                                }
                            },
                        generationId = generation.generationId,
                        componentHash = pack.componentHash,
                        clock = clock,
                    ),
                runtime = runtime,
            )
        }
    }
}
