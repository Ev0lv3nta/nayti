package app.nayti.indexer

import app.nayti.ml.runtime.pack.ModelPackCandidateInstaller
import app.nayti.ml.runtime.pack.ModelPackSource
import app.nayti.storage.ModelPackDao
import app.nayti.storage.ModelPackEntity
import app.nayti.storage.ModelPackStatus
import java.nio.file.Path

fun interface RegisteredModelPackInstaller {
    suspend fun install(source: ModelPackSource): ModelPackEntity
}

class ModelPackInstallCoordinator(
    private val installer: ModelPackCandidateInstaller,
    private val registry: ModelPackDao,
    modelPackRoot: Path,
    private val nowMillis: () -> Long,
) : RegisteredModelPackInstaller {
    private val root = modelPackRoot.toAbsolutePath().normalize()

    override suspend fun install(source: ModelPackSource): ModelPackEntity {
        val verified = installer.install(source)
        val directory = verified.directory.toAbsolutePath().normalize()
        if (!directory.startsWith(root)) error("Verified pack directory escaped the model-pack root")
        val relative = root.relativize(directory)
        check(relative.nameCount == 2) { "Unexpected installed pack directory layout" }
        check(relative.getName(0).toString() == verified.packId) { "Installed pack directory does not match pack ID" }
        val candidate =
            ModelPackEntity(
                packId = verified.packId,
                packVersion = verified.packVersion,
                keyId = verified.keyId,
                manifestSha256 = verified.manifestSha256,
                relativeDirectory = relative.joinToString("/"),
                payloadBytes = verified.payloadBytes,
                installedAtMillis = nowMillis(),
                status = ModelPackStatus.INSTALLED_CANDIDATE,
            )
        return registry.registerInstalledCandidate(candidate)
    }
}
