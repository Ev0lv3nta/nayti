package app.nayti.ml.runtime.pack

import java.nio.file.Path

class ModelPackException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class ModelPackFile(
    val path: String,
    val role: String,
    val length: Long,
    val sha256: String,
)

internal data class ModelPackManifest(
    val packId: String,
    val packVersion: String,
    val keyId: String,
    val compatibility: JsonValue.ObjectValue,
    val runtime: JsonValue.ObjectValue,
    val components: JsonValue.ArrayValue,
    val files: List<ModelPackFile>,
    val provenance: JsonValue.ObjectValue,
) {
    val totalPayloadBytes: Long = files.fold(0L) { total, file -> Math.addExact(total, file.length) }
}

data class VerifiedModelPack(
    val packId: String,
    val packVersion: String,
    val keyId: String,
    val manifestSha256: String,
    val payloadBytes: Long,
    val directory: Path,
)

data class ModelPackValidationCandidate(
    val packId: String,
    val packVersion: String,
    val keyId: String,
    val manifestSha256: String,
    val payloadBytes: Long,
    val directory: Path,
) {
    val payloadDirectory: Path = directory.resolve("payload")
}

fun interface ModelPackPayloadValidator {
    suspend fun validate(candidate: ModelPackValidationCandidate)
}

internal object ModelPackManifestParser {
    private val PackId = Regex("[a-z0-9][a-z0-9.-]{2,63}")
    private val Version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
    private val KeyId = Regex("[0-9a-f]{32}")
    private val Sha256 = Regex("[0-9a-f]{64}")
    private val PathPattern = Regex("[a-z0-9][a-z0-9._/-]{0,159}")
    private val TopLevelKeys =
        setOf(
            "schemaVersion",
            "packId",
            "packVersion",
            "keyId",
            "compatibility",
            "runtime",
            "components",
            "files",
            "provenance",
        )
    private val FileKeys = setOf("path", "role", "length", "sha256")

    fun parse(raw: ByteArray): ModelPackManifest {
        val root = CanonicalJson.parseCanonical(raw)
        if (root.entries.keys != TopLevelKeys) throw ModelPackException("Manifest has missing or unknown fields")
        if (root.integer("schemaVersion") != 1L) throw ModelPackException("Unsupported manifest schema")
        val packId = root.string("packId").matching(PackId, "packId")
        val packVersion = root.string("packVersion").matching(Version, "packVersion")
        val keyId = root.string("keyId").matching(KeyId, "keyId")
        val compatibility = root.objectValue("compatibility")
        val runtime = root.objectValue("runtime")
        val components = root.arrayValue("components")
        val provenance = root.objectValue("provenance")
        if (compatibility.entries.isEmpty() || runtime.entries.isEmpty() || provenance.entries.isEmpty()) {
            throw ModelPackException("Manifest objects must not be empty")
        }
        if (components.values.isEmpty()) throw ModelPackException("Manifest components must not be empty")

        val rawFiles = root.arrayValue("files").values
        if (rawFiles.isEmpty() || rawFiles.size > MaxFiles) throw ModelPackException("Invalid file count")
        val files = rawFiles.map(::parseFile)
        val paths = files.map(ModelPackFile::path)
        if (paths != paths.sorted()) throw ModelPackException("Manifest files are not sorted")
        if (paths.size != paths.toSet().size) throw ModelPackException("Duplicate payload path")
        if (paths.size != paths.map(String::lowercase).toSet().size) {
            throw ModelPackException("Case-colliding payload path")
        }
        val manifest =
            ModelPackManifest(packId, packVersion, keyId, compatibility, runtime, components, files, provenance)
        if (manifest.totalPayloadBytes > MaxTotalPayloadBytes) throw ModelPackException("Payload exceeds size cap")
        return manifest
    }

    private fun parseFile(value: JsonValue): ModelPackFile {
        val objectValue = value as? JsonValue.ObjectValue ?: throw ModelPackException("Invalid file descriptor")
        if (objectValue.entries.keys != FileKeys) throw ModelPackException("Invalid file descriptor fields")
        val path = objectValue.string("path")
        val role = objectValue.string("role")
        val length = objectValue.integer("length")
        val sha256 = objectValue.string("sha256")
        if (!PathPattern.matches(path) || path.startsWith('/') || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ModelPackException("Unsafe payload path: $path")
        }
        role.matching(PackId, "file role")
        if (length !in 0..MaxFileBytes) throw ModelPackException("Payload file exceeds size cap: $path")
        sha256.matching(Sha256, "file SHA-256")
        return ModelPackFile(path, role, length, sha256)
    }

    private fun JsonValue.ObjectValue.string(name: String): String =
        (entries[name] as? JsonValue.StringValue)?.value ?: throw ModelPackException("$name must be a string")

    private fun JsonValue.ObjectValue.integer(name: String): Long =
        (entries[name] as? JsonValue.IntegerValue)?.value ?: throw ModelPackException("$name must be an integer")

    private fun JsonValue.ObjectValue.objectValue(name: String): JsonValue.ObjectValue =
        entries[name] as? JsonValue.ObjectValue ?: throw ModelPackException("$name must be an object")

    private fun JsonValue.ObjectValue.arrayValue(name: String): JsonValue.ArrayValue =
        entries[name] as? JsonValue.ArrayValue ?: throw ModelPackException("$name must be an array")

    private fun String.matching(pattern: Regex, name: String): String {
        if (!pattern.matches(this)) throw ModelPackException("Invalid $name")
        return this
    }

    const val MaxManifestBytes = 1024 * 1024
    const val MaxFiles = 128
    const val MaxFileBytes = 2L * 1024 * 1024 * 1024
    const val MaxTotalPayloadBytes = 4L * 1024 * 1024 * 1024
}
