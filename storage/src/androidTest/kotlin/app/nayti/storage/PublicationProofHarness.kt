package app.nayti.storage

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

enum class PublicationFailpoint {
    AFTER_TEMP_WRITE,
    AFTER_SEGMENT_FSYNC,
    AFTER_SEGMENT_RENAME,
    AFTER_SEGMENT_DIRECTORY_SYNC,
    AFTER_MANIFEST_FSYNC,
    AFTER_MANIFEST_RENAME,
    AFTER_MANIFEST_DIRECTORY_SYNC,
    BEFORE_DB_COMMIT,
    INSIDE_DB_TRANSACTION,
    AFTER_DB_COMMIT,
    AFTER_DELETE_INTENT,
    AFTER_DELETE_UNLINK,
    BEFORE_DELETE_DB_FINALIZE,
}

class SimulatedProcessDeath(val failpoint: PublicationFailpoint) : RuntimeException(failpoint.name)

data class PublishedProofSnapshot(
    val snapshotId: String,
    val manifestRevision: String,
)

data class ProofRecoveryReport(
    val activeBefore: String?,
    val activeAfter: String?,
    val abandonedPublications: Int,
    val expiredLeases: Int,
    val deletedTemps: Int,
    val deletedOrphans: Int,
    val replayedDeleteIntents: Int,
)

class PublicationProofHarness(
    private val root: File,
    private val database: PublicationProofDatabase,
) {
    private val dao = database.publicationDao()
    private val stagingDirectory = root.resolve("generation/staging")
    private val segmentDirectory = root.resolve("generation/segments")
    private val manifestDirectory = root.resolve("generation/manifests")

    init {
        check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory)
        check(segmentDirectory.mkdirs() || segmentDirectory.isDirectory)
        check(manifestDirectory.mkdirs() || manifestDirectory.isDirectory)
    }

    suspend fun publish(
        token: String,
        payload: ByteArray,
        parentSnapshotId: String?,
        failpoint: PublicationFailpoint? = null,
    ): PublishedProofSnapshot {
        require(TOKEN_PATTERN.matches(token))
        require(payload.isNotEmpty())
        dao.insertPublication(
            ProofPublicationEntity(token, PublicationProofDao.PUBLICATION_STAGED),
        )

        val snapshotId = "$token-snapshot"
        val revision = "$token-manifest"
        val segmentTemp = stagingDirectory.resolve("$token.segment.tmp")
        writeAndSync(segmentTemp, payload) {
            checkpoint(PublicationFailpoint.AFTER_TEMP_WRITE, failpoint)
        }
        checkpoint(PublicationFailpoint.AFTER_SEGMENT_FSYNC, failpoint)

        val segmentSha = sha256Hex(payload)
        val segmentFinal = segmentDirectory.resolve("$segmentSha.naytivec")
        seal(segmentTemp, segmentFinal, payload.size.toLong(), segmentSha)
        checkpoint(PublicationFailpoint.AFTER_SEGMENT_RENAME, failpoint)
        syncDirectory(stagingDirectory)
        syncDirectory(segmentDirectory)
        checkpoint(PublicationFailpoint.AFTER_SEGMENT_DIRECTORY_SYNC, failpoint)

        val segmentPath = relativePath(segmentFinal)
        val manifestBytes = canonicalManifest(revision, segmentPath, payload.size, segmentSha)
        val manifestSha = sha256Hex(manifestBytes)
        val manifestTemp = stagingDirectory.resolve("$token.manifest.tmp")
        val manifestFinal = manifestDirectory.resolve("$revision.manifest")
        writeAndSync(manifestTemp, manifestBytes) {}
        checkpoint(PublicationFailpoint.AFTER_MANIFEST_FSYNC, failpoint)
        seal(manifestTemp, manifestFinal, manifestBytes.size.toLong(), manifestSha)
        checkpoint(PublicationFailpoint.AFTER_MANIFEST_RENAME, failpoint)
        syncDirectory(stagingDirectory)
        syncDirectory(manifestDirectory)
        checkpoint(PublicationFailpoint.AFTER_MANIFEST_DIRECTORY_SYNC, failpoint)

        val manifest =
            ProofManifestEntity(
                revision = revision,
                segmentPath = segmentPath,
                segmentLength = payload.size.toLong(),
                segmentSha256 = segmentSha,
                manifestPath = relativePath(manifestFinal),
                manifestLength = manifestBytes.size.toLong(),
                manifestSha256 = manifestSha,
            )
        val snapshot = ProofSnapshotEntity(snapshotId, parentSnapshotId, revision)
        checkpoint(PublicationFailpoint.BEFORE_DB_COMMIT, failpoint)
        if (failpoint == PublicationFailpoint.INSIDE_DB_TRANSACTION) {
            dao.failInsidePublicationTransaction(token, manifest, snapshot)
            error("Simulated transaction unexpectedly returned")
        } else {
            dao.commitPublication(token, manifest, snapshot)
        }
        checkpoint(PublicationFailpoint.AFTER_DB_COMMIT, failpoint)
        return PublishedProofSnapshot(snapshotId, revision)
    }

    suspend fun recover(
        nowMillis: Long,
        orphanGraceMillis: Long = 0,
    ): ProofRecoveryReport {
        require(nowMillis >= 0)
        require(orphanGraceMillis >= 0)
        val activeBefore = dao.activeSnapshotId()
        val replayedDeleteIntents = replayPendingDeleteIntents()
        val abandoned = dao.abandonStagedPublications()
        val expiredLeases = dao.deleteExpiredLeases(nowMillis)
        val deletedTemps = deleteTemps()
        val activeAfter = recoverActiveSnapshot(activeBefore)
        val deletedOrphans = deleteFilesystemOrphans(nowMillis, orphanGraceMillis)
        return ProofRecoveryReport(
            activeBefore = activeBefore,
            activeAfter = activeAfter,
            abandonedPublications = abandoned,
            expiredLeases = expiredLeases,
            deletedTemps = deletedTemps,
            deletedOrphans = deletedOrphans,
            replayedDeleteIntents = replayedDeleteIntents,
        )
    }

    suspend fun activeSnapshotId(): String? = dao.activeSnapshotId()

    suspend fun publicationState(token: String): String? = dao.publicationState(token)

    suspend fun activeArtifactPaths(): Set<String> {
        val snapshotId = dao.activeSnapshotId() ?: return emptySet()
        val snapshot = dao.snapshot(snapshotId) ?: return emptySet()
        val manifest = dao.manifest(snapshot.manifestRevision) ?: return emptySet()
        return setOf(manifest.segmentPath, manifest.manifestPath)
    }

    suspend fun committedArtifactPaths(): Set<String> =
        dao.manifests().flatMapTo(mutableSetOf()) { manifest ->
            listOf(manifest.segmentPath, manifest.manifestPath)
        }

    fun sealedArtifactPaths(): Set<String> =
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "naytivec" || it.extension == "manifest") }
            .mapTo(mutableSetOf(), ::relativePath)

    fun temporaryFileCount(): Int =
        root.walkTopDown().count { it.isFile && it.extension == "tmp" }

    suspend fun snapshotExists(snapshotId: String): Boolean = dao.snapshot(snapshotId) != null

    suspend fun replaceActivePointer(snapshotId: String?) {
        dao.replaceActivePointer(ProofActivePointerEntity(snapshotId = snapshotId))
    }

    suspend fun acquireQueryLease(
        token: String,
        snapshotId: String,
        expiresAtMillis: Long,
    ) {
        check(dao.snapshot(snapshotId) != null)
        dao.replaceLease(ProofQueryLeaseEntity(token, snapshotId, expiresAtMillis))
    }

    suspend fun collectSnapshot(
        snapshotId: String,
        nowMillis: Long,
        failpoint: PublicationFailpoint? = null,
    ): Boolean {
        val snapshot = dao.snapshot(snapshotId) ?: return true
        if (reachableSnapshotIds(dao.activeSnapshotId()).contains(snapshotId)) return false
        if (dao.liveLeaseCount(snapshotId, nowMillis) != 0) return false
        val manifest = checkNotNull(dao.manifest(snapshot.manifestRevision))
        val intents = buildList {
            if (
                dao.otherSegmentReferenceCount(
                    manifest.segmentPath,
                    manifest.revision,
                ) == 0
            ) {
                add(
                    ProofDeleteIntentEntity(
                        manifest.segmentPath,
                        snapshotId,
                        manifest.segmentSha256,
                        DELETE_PENDING,
                    ),
                )
            }
            add(
                ProofDeleteIntentEntity(
                    manifest.manifestPath,
                    snapshotId,
                    manifest.manifestSha256,
                    DELETE_PENDING,
                ),
            )
        }
        dao.replaceDeleteIntents(intents)
        checkpoint(PublicationFailpoint.AFTER_DELETE_INTENT, failpoint)
        intents.forEachIndexed { index, intent ->
            deleteVerified(intent)
            if (index == 0) checkpoint(PublicationFailpoint.AFTER_DELETE_UNLINK, failpoint)
            check(dao.confirmDeleteIntent(intent.path) == 1)
        }
        checkpoint(PublicationFailpoint.BEFORE_DELETE_DB_FINALIZE, failpoint)
        dao.finalizeSnapshotDeletion(snapshot.snapshotId, snapshot.manifestRevision)
        return true
    }

    suspend fun corruptSegment(snapshotId: String) {
        val snapshot = checkNotNull(dao.snapshot(snapshotId))
        val manifest = checkNotNull(dao.manifest(snapshot.manifestRevision))
        val file = resolveRelative(manifest.segmentPath)
        check(file.setWritable(true, true))
        RandomAccessFile(file, "rw").use { randomAccess ->
            val offset = randomAccess.length() - 1
            randomAccess.seek(offset)
            val value = randomAccess.read()
            randomAccess.seek(offset)
            randomAccess.write(value xor 0xff)
            randomAccess.fd.sync()
        }
        syncDirectory(file.parentFile!!)
    }

    private suspend fun recoverActiveSnapshot(activeBefore: String?): String? {
        var candidateId = activeBefore
        while (candidateId != null) {
            val snapshot = dao.snapshot(candidateId)
            if (snapshot == null) {
                candidateId = null
                break
            }
            val manifest = dao.manifest(snapshot.manifestRevision)
            if (manifest != null && validateManifest(manifest)) break
            candidateId = snapshot.parentSnapshotId
        }
        if (candidateId != activeBefore) {
            dao.replaceActivePointer(ProofActivePointerEntity(snapshotId = candidateId))
        }
        return candidateId
    }

    private suspend fun replayPendingDeleteIntents(): Int {
        val owners = dao.deleteIntentOwnersWithSnapshot()
        if (owners.isEmpty()) return 0
        var replayed = 0
        owners.forEach { snapshotId ->
            val intents = dao.deleteIntents(snapshotId)
            check(intents.isNotEmpty())
            intents.filter { it.state != DELETE_CONFIRMED }.forEach { intent ->
                deleteVerified(intent)
                check(dao.confirmDeleteIntent(intent.path) == 1)
                replayed += 1
            }
            val snapshot = dao.snapshot(snapshotId)
            if (snapshot != null && intents.isNotEmpty()) {
                dao.finalizeSnapshotDeletion(snapshot.snapshotId, snapshot.manifestRevision)
            }
        }
        return replayed
    }

    private suspend fun reachableSnapshotIds(activeSnapshotId: String?): Set<String> {
        val reachable = linkedSetOf<String>()
        var candidateId = activeSnapshotId
        while (candidateId != null && reachable.add(candidateId)) {
            candidateId = dao.snapshot(candidateId)?.parentSnapshotId
        }
        return reachable
    }

    private suspend fun deleteFilesystemOrphans(nowMillis: Long, graceMillis: Long): Int {
        val referenced =
            dao.manifests().flatMapTo(mutableSetOf()) { manifest ->
                listOf(manifest.segmentPath, manifest.manifestPath)
            }
        val threshold = if (nowMillis >= graceMillis) nowMillis - graceMillis else 0
        var deleted = 0
        root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (file.extension == "naytivec" || file.extension == "manifest") &&
                    relativePath(file) !in referenced &&
                    file.lastModified() <= threshold
            }.forEach { file ->
                check(file.setWritable(true, true))
                check(file.delete())
                syncDirectory(file.parentFile!!)
                deleted += 1
            }
        return deleted
    }

    private fun deleteTemps(): Int {
        var deleted = 0
        root.walkTopDown().filter { it.isFile && it.extension == "tmp" }.forEach { file ->
            check(file.delete())
            syncDirectory(file.parentFile!!)
            deleted += 1
        }
        return deleted
    }

    private fun validateManifest(manifest: ProofManifestEntity): Boolean =
        validateFile(
            resolveRelative(manifest.segmentPath),
            manifest.segmentLength,
            manifest.segmentSha256,
        ) && validateFile(
            resolveRelative(manifest.manifestPath),
            manifest.manifestLength,
            manifest.manifestSha256,
        )

    private fun validateFile(file: File, expectedLength: Long, expectedSha256: String): Boolean =
        file.isFile && file.length() == expectedLength && sha256Hex(file.readBytes()) == expectedSha256

    private fun deleteVerified(intent: ProofDeleteIntentEntity) {
        val file = resolveRelative(intent.path)
        if (!file.exists()) return
        check(file.isFile)
        check(sha256Hex(file.readBytes()) == intent.expectedSha256)
        check(file.setWritable(true, true))
        check(file.delete())
        syncDirectory(file.parentFile!!)
    }

    private fun seal(temp: File, final: File, expectedLength: Long, expectedSha256: String) {
        if (final.exists()) {
            check(validateFile(final, expectedLength, expectedSha256))
            check(temp.delete())
        } else {
            Files.move(temp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        check(final.setReadOnly())
    }

    private fun writeAndSync(file: File, bytes: ByteArray, beforeSync: () -> Unit) {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            beforeSync()
            channel.force(true)
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor =
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
                0,
            )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun resolveRelative(path: String): File {
        val resolved = root.toPath().resolve(path).normalize()
        check(resolved.startsWith(root.toPath().normalize()))
        return resolved.toFile()
    }

    private fun relativePath(file: File): String =
        file.relativeTo(root).invariantSeparatorsPath.also { path ->
            check(!path.startsWith("../"))
        }

    private fun canonicalManifest(
        revision: String,
        segmentPath: String,
        segmentLength: Int,
        segmentSha256: String,
    ): ByteArray =
        "NAYTIMAN1\n$revision\n$segmentPath\n$segmentLength\n$segmentSha256\n"
            .encodeToByteArray()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private fun checkpoint(actual: PublicationFailpoint, requested: PublicationFailpoint?) {
        if (actual == requested) throw SimulatedProcessDeath(actual)
    }

    private companion object {
        val TOKEN_PATTERN = Regex("[A-Za-z0-9._-]+")
        const val DELETE_PENDING = "PENDING"
        const val DELETE_CONFIRMED = "CONFIRMED"
        const val HEX = "0123456789abcdef"
    }
}
