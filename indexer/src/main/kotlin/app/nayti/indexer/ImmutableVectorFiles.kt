package app.nayti.indexer

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class SealedVectorFile(
    val relativePath: String,
    val file: File,
)

enum class VectorFileOperation {
    BEFORE_WRITE_CHUNK,
    BEFORE_FILE_FSYNC,
    BEFORE_ATOMIC_MOVE,
    BEFORE_DIRECTORY_FSYNC,
}

enum class VectorArtifactRole {
    SEGMENT,
    MANIFEST,
}

class ImmutableVectorFiles(
    rootDirectory: File,
    private val faultInjector: (VectorArtifactRole, VectorFileOperation) -> Unit = { _, _ -> },
) {
    val root: File = rootDirectory.canonicalFile
    private val stagingDirectory = root.resolve("staging")
    private val segmentDirectory = root.resolve("segments")
    private val manifestDirectory = root.resolve("manifests")

    init {
        listOf(root, stagingDirectory, segmentDirectory, manifestDirectory).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory)
        }
    }

    fun sealSegment(
        token: String,
        bytes: ByteArray,
        sha256: String,
        afterFsync: () -> Unit = {},
        afterRename: () -> Unit = {},
    ): SealedVectorFile =
        seal(token, VectorArtifactRole.SEGMENT, "segments/$sha256.naytivec", bytes, sha256, afterFsync, afterRename)

    fun sealManifest(
        token: String,
        bytes: ByteArray,
        sha256: String,
        afterFsync: () -> Unit = {},
        afterRename: () -> Unit = {},
    ): SealedVectorFile =
        seal(token, VectorArtifactRole.MANIFEST, "manifests/$sha256.naytimanifest", bytes, sha256, afterFsync, afterRename)

    fun readVerified(relativePath: String, expectedLength: Long, expectedSha256: String): ByteArray {
        require(expectedLength in 1..MaximumInMemoryArtifactBytes)
        val file = resolve(relativePath)
        check(file.isFile && file.length() == expectedLength)
        val bytes = file.readBytes()
        check(sha256Hex(bytes) == expectedSha256)
        return bytes
    }

    private fun seal(
        token: String,
        role: VectorArtifactRole,
        relativePath: String,
        bytes: ByteArray,
        expectedSha256: String,
        afterFsync: () -> Unit,
        afterRename: () -> Unit,
    ): SealedVectorFile {
        require(Token.matches(token) && Sha256.matches(expectedSha256))
        require(bytes.isNotEmpty() && sha256Hex(bytes) == expectedSha256)
        val target = resolve(relativePath)
        val temp = stagingDirectory.resolve("$token-${UUID.randomUUID()}.${role.name.lowercase()}.tmp")
        writeAndSync(role, temp, bytes)
        afterFsync()
        if (target.exists()) {
            check(target.isFile && target.length() == bytes.size.toLong())
            check(sha256Hex(target) == expectedSha256)
            check(temp.delete())
        } else {
            faultInjector(role, VectorFileOperation.BEFORE_ATOMIC_MOVE)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        check(target.setReadOnly())
        syncDirectory(role, stagingDirectory)
        syncDirectory(role, target.parentFile!!)
        afterRename()
        return SealedVectorFile(relativePath, target)
    }

    private fun writeAndSync(role: VectorArtifactRole, file: File, bytes: ByteArray) {
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                faultInjector(role, VectorFileOperation.BEFORE_WRITE_CHUNK)
                check(channel.write(buffer) > 0)
            }
            faultInjector(role, VectorFileOperation.BEFORE_FILE_FSYNC)
            channel.force(true)
        }
    }

    private fun resolve(relativePath: String): File {
        val resolved = root.resolve(relativePath).canonicalFile
        check(resolved.toPath().startsWith(root.toPath()))
        return resolved
    }

    private fun syncDirectory(role: VectorArtifactRole, directory: File) {
        faultInjector(role, VectorFileOperation.BEFORE_DIRECTORY_FSYNC)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
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

    private companion object {
        val Token = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        val Sha256 = Regex("[0-9a-f]{64}")
        const val MaximumInMemoryArtifactBytes = 8L * 1024 * 1024
    }
}
