package app.nayti.indexer

import app.nayti.storage.CatalogStorage
import app.nayti.storage.IndexChannel
import java.io.File

/** Runs storage, native-index and installed-pack smoke checks before the candidate can become READY. */
class ProductionCandidateCanaryVerifier(
    private val storage: CatalogStorage,
    private val packResolver: InstalledOcrPackResolver,
    vectorRoot: File,
) : CandidateCanaryVerifier {
    private val integrity = VectorSnapshotIntegrityVerifier(vectorRoot, storage.vectorIndexDao)

    override suspend fun verify(candidate: PreparedCandidateSnapshot) {
        val snapshot = candidate.snapshot
        check(
            integrity.verify(
                snapshot = snapshot,
                deepVerifySegments = false,
                candidateChannels = candidate.channels,
            ),
        ) { "Candidate vector closure failed deep verification" }
        val pack = packResolver.resolve(snapshot.packId, snapshot.packVersion)
        check(pack.registryEntry.manifestSha256 == snapshot.packManifestSha256)
        val ocr = candidate.channels.single { it.channel == IndexChannel.OCR }
        OcrLexicalSearch(storage.ocrDao).searchAtEpoch(
            query = CanaryQuery,
            pipelineVersion = ocr.pipelineVersion,
            componentHash = ocr.componentHash,
            maximumPublicationEpoch = snapshot.lexicalPublicationEpoch,
            limit = 1,
        )
        check(storage.catalogDao.watermark()?.catalogRevision == snapshot.catalogWatermark)
        check(storage.catalogDao.accessObservation()?.processAccessRevision == snapshot.capturedAccessRevision)
    }

    private companion object {
        const val CanaryQuery = "nayti candidate canary"
    }
}
