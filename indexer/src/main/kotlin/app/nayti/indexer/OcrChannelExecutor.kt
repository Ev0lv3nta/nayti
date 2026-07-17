package app.nayti.indexer

import app.nayti.ml.runtime.ocr.OcrDetectorContract
import app.nayti.ml.runtime.ocr.OcrInferenceEngine
import app.nayti.ml.runtime.ocr.OcrInferenceContract
import app.nayti.ml.runtime.ocr.OcrRuntimeException
import app.nayti.ml.runtime.ocr.RecognizedOcrRegion
import app.nayti.platform.media.BoundedMediaDecoder
import app.nayti.platform.media.MediaDecodeAccessException
import app.nayti.platform.media.MediaDecodeContentException
import app.nayti.platform.media.MediaDecodeIoException
import app.nayti.platform.media.MediaKey
import app.nayti.search.engine.lexical.LexicalTextNormalizer
import app.nayti.storage.CatalogAvailability
import app.nayti.storage.OcrDao
import app.nayti.storage.OcrDocumentDraft
import app.nayti.storage.OcrPublicationCodec
import app.nayti.storage.OcrRegionDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun interface OcrExecutorClock {
    fun nowMillis(): Long
}

data class OcrPublicationDraft(
    val document: OcrDocumentDraft,
    val regions: List<OcrRegionDraft>,
)

class OcrPublicationBuilder(
    private val normalizer: LexicalTextNormalizer = LexicalTextNormalizer(),
) {
    fun build(
        claim: IndexClaimContext,
        sourceWidth: Int,
        sourceHeight: Int,
        decodedWidth: Int,
        decodedHeight: Int,
        recognized: List<RecognizedOcrRegion>,
    ): OcrPublicationDraft {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(decodedWidth > 0 && decodedHeight > 0)
        require(recognized.size <= OcrInferenceContract.MaximumRegions)
        val accepted =
            recognized.filter { region ->
                region.rawText.isNotBlank() &&
                    region.confidence.isFinite() &&
                    region.confidence >= MinimumPublicationConfidence
            }
        val rawLines = accepted.map(RecognizedOcrRegion::rawText)
        val documentForms = normalizer.normalize(rawLines)
        val document =
            OcrDocumentDraft(
                assetId = claim.work.assetId,
                sourceFingerprint = claim.work.sourceFingerprint,
                accessRevision = claim.work.accessRevision,
                pipelineVersion = claim.work.pipelineVersion,
                componentHash = claim.work.componentHash,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rawText = rawLines.joinToString("\n"),
                displayText = documentForms.display,
                canonicalText = documentForms.canonical,
                stemText = documentForms.stems,
                identifierText = documentForms.identifiers,
                normalizerVersion = LexicalTextNormalizer.NormalizerVersion,
                stemmerVersion = LexicalTextNormalizer.StemmerVersion,
                identifierVersion = LexicalTextNormalizer.IdentifierVersion,
            )
        val regions =
            accepted.map { recognizedRegion ->
                val display = normalizer.displayLine(recognizedRegion.rawText)
                OcrRegionDraft(
                    rawText = recognizedRegion.rawText,
                    displayText = display,
                    canonicalText = normalizer.canonical(display),
                    confidenceMicros = (recognizedRegion.confidence * Micros).roundToInt().coerceIn(0, Micros),
                    x0Micros = recognizedRegion.quadrilateral.topLeft.x.toMicros(decodedWidth),
                    y0Micros = recognizedRegion.quadrilateral.topLeft.y.toMicros(decodedHeight),
                    x1Micros = recognizedRegion.quadrilateral.topRight.x.toMicros(decodedWidth),
                    y1Micros = recognizedRegion.quadrilateral.topRight.y.toMicros(decodedHeight),
                    x2Micros = recognizedRegion.quadrilateral.bottomRight.x.toMicros(decodedWidth),
                    y2Micros = recognizedRegion.quadrilateral.bottomRight.y.toMicros(decodedHeight),
                    x3Micros = recognizedRegion.quadrilateral.bottomLeft.x.toMicros(decodedWidth),
                    y3Micros = recognizedRegion.quadrilateral.bottomLeft.y.toMicros(decodedHeight),
                )
            }
        return OcrPublicationDraft(document, regions)
    }

    private fun Float.toMicros(axisLength: Int): Int =
        (this / axisLength * Micros).roundToInt().coerceIn(0, Micros)

    companion object {
        const val MinimumPublicationConfidence = 0.25f
        private const val Micros = 1_000_000
    }
}

/** Decodes one current MediaStore asset and atomically publishes its OCR evidence. */
class OcrChannelExecutor(
    private val ocr: OcrDao,
    private val decoder: BoundedMediaDecoder,
    private val inference: OcrInferenceEngine,
    private val publicationBuilder: OcrPublicationBuilder = OcrPublicationBuilder(),
    private val clock: OcrExecutorClock = OcrExecutorClock(System::currentTimeMillis),
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : IndexChannelExecutor {
    override suspend fun execute(claim: IndexClaimContext): IndexExecutionOutcome {
        val asset = ocr.catalogAsset(claim.work.assetId) ?: return IndexExecutionOutcome.LeaseRejected
        val access = ocr.accessObservation() ?: return IndexExecutionOutcome.LeaseRejected
        if (
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != claim.work.sourceFingerprint ||
            access.processAccessRevision != claim.work.accessRevision ||
            access.accessScope == "None"
        ) {
            return IndexExecutionOutcome.LeaseRejected
        }

        val draft =
            try {
                withContext(inferenceDispatcher) {
                    decoder.decode(
                        MediaKey(asset.volumeName, asset.mediaStoreId),
                        OcrDetectorContract.BaseDecodeLongSide,
                    ).use { decoded ->
                        publicationBuilder.build(
                            claim = claim,
                            sourceWidth = decoded.sourceWidth,
                            sourceHeight = decoded.sourceHeight,
                            decodedWidth = decoded.decodedWidth,
                            decodedHeight = decoded.decodedHeight,
                            recognized = inference.recognize(decoded.bitmap),
                        )
                    }
                }
            } catch (_: MediaDecodeAccessException) {
                return IndexExecutionOutcome.LeaseRejected
            } catch (_: MediaDecodeContentException) {
                return IndexExecutionOutcome.Permanent(ErrorCorruptMedia)
            } catch (_: MediaDecodeIoException) {
                return IndexExecutionOutcome.Retryable(ErrorMediaIo)
            } catch (_: OcrRuntimeException) {
                return IndexExecutionOutcome.Retryable(ErrorRuntime)
            }

        val identity = OcrPublicationCodec.identity(draft.document, draft.regions)
        val publication =
            ocr.commitOcrPublication(
                leaseToken = checkNotNull(claim.work.leaseToken),
                publicationToken = claim.publicationToken,
                expectedIdentity = identity,
                document = draft.document,
                regions = draft.regions,
                nowMillis = clock.nowMillis(),
            )
        return if (publication == null) IndexExecutionOutcome.LeaseRejected else IndexExecutionOutcome.Published
    }

    companion object {
        const val ErrorCorruptMedia = "OCR_CORRUPT_MEDIA"
        const val ErrorMediaIo = "OCR_MEDIA_IO"
        const val ErrorRuntime = "OCR_RUNTIME"
    }
}
