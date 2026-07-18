package app.nayti.indexer

import app.nayti.search.engine.NativeVectorIndex
import app.nayti.storage.ActivationSnapshotEntity
import app.nayti.storage.ActivationSnapshotChannelEntity
import app.nayti.storage.ActivationSnapshotFormat
import app.nayti.storage.VectorGenerationState
import app.nayti.storage.VectorIndexDao
import app.nayti.storage.VectorManifestEntity
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Verifies the complete immutable closure of a snapshot before activation or startup serving. */
class VectorSnapshotIntegrityVerifier(
    rootDirectory: File,
    private val dao: VectorIndexDao,
) {
    private val root = rootDirectory.canonicalFile

    suspend fun verify(
        snapshot: ActivationSnapshotEntity,
        deepVerifySegments: Boolean,
        candidateChannels: List<ActivationSnapshotChannelEntity>? = null,
    ): Boolean {
        if (
            snapshot.formatVersion != ActivationSnapshotFormat.Current ||
            snapshot.engineContractVersion != NativeVectorIndex.contractVersion()
        ) {
            return false
        }
        val pack = dao.modelPack(snapshot.packId, snapshot.packVersion) ?: return false
        if (pack.manifestSha256 != snapshot.packManifestSha256) return false
        val revisions = listOfNotNull(snapshot.semanticManifestRevision, snapshot.visualManifestRevision)
        if (revisions.isEmpty()) return false
        val channelOverrides = candidateChannels?.associateBy(ActivationSnapshotChannelEntity::channel)
        return revisions.all { revision ->
            val manifest = dao.manifest(revision) ?: return@all false
            val generation = dao.generation(manifest.generationId) ?: return@all false
            val component =
                channelOverrides?.get(manifest.channel)
                    ?: dao.snapshotChannel(snapshot.snapshotId, manifest.channel)
                    ?: return@all false
            if (
                generation.channel != manifest.channel ||
                component.generationId != generation.generationId ||
                component.manifestRevision != manifest.revision ||
                component.pipelineVersion != generation.pipelineVersion ||
                component.componentHash != generation.componentHash ||
                component.embeddingSpaceHash != generation.embeddingSpaceHash ||
                generation.state !in setOf(VectorGenerationState.BUILDING, VectorGenerationState.SEALED)
            ) {
                return@all false
            }
            verifyManifest(manifest, deepVerifySegments)
        }
    }

    private suspend fun verifyManifest(manifest: VectorManifestEntity, deepVerifySegments: Boolean): Boolean {
        val manifestFile = resolveRelative(manifest.relativePath) ?: return false
        if (!verifyFile(manifestFile, manifest.byteLength, manifest.sha256, verifyHash = true)) return false
        val entries = dao.manifestSegments(manifest.revision)
        if (entries.size != manifest.segmentCount) return false
        return entries.all { entry ->
            val artifact = dao.segment(entry.segmentSha256) ?: return@all false
            val file = resolveRelative(artifact.relativePath) ?: return@all false
            if (!verifyFile(file, artifact.byteLength, artifact.sha256, verifyHash = deepVerifySegments)) {
                return@all false
            }
            if (!deepVerifySegments) return@all true
            runCatching {
                NativeVectorIndex.mappedRecordCount(
                    path = file.absolutePath,
                    expectedLength = artifact.byteLength,
                    expectedSha256 = decodeSha256(artifact.sha256),
                ) == artifact.recordCount
            }.getOrDefault(false)
        }
    }

    private fun verifyFile(file: File, length: Long, sha256: String, verifyHash: Boolean): Boolean =
        file.isFile && file.length() == length && (!verifyHash || sha256Hex(file) == sha256)

    private fun resolveRelative(path: String): File? {
        val resolved = runCatching { root.resolve(path).canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun decodeSha256(value: String): ByteArray =
        ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
