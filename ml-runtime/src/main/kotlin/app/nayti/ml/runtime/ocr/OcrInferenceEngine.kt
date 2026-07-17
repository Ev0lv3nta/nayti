package app.nayti.ml.runtime.ocr

import android.graphics.Bitmap

data class RecognizedOcrRegion(
    val quadrilateral: OcrQuadrilateral,
    val rawText: String,
    val confidence: Float,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0f..1.0f)
    }
}

object OcrInferenceContract {
    const val MaximumRegions = 512
}

fun interface OcrInferenceEngine {
    fun recognize(bitmap: Bitmap): List<RecognizedOcrRegion>
}

/** Runs the complete detector/recognizer path with one bounded neural lane. */
class OrtOcrInferenceEngine(
    private val runtime: OcrOrtRuntime,
    private val detectorPreprocessor: OcrDetectorPreprocessor =
        OcrDetectorPreprocessor(OcrTensorBufferPool()),
    private val detectorPostprocessor: OcrDetectorPostprocessor = OcrDetectorPostprocessor(),
    private val recognizerPreprocessor: OcrRecognizerPreprocessor =
        OcrRecognizerPreprocessor(OcrTensorBufferPool()),
) : OcrInferenceEngine, AutoCloseable {
    @Synchronized
    override fun recognize(bitmap: Bitmap): List<RecognizedOcrRegion> {
        val detected =
            detectorPreprocessor.prepare(bitmap).use { input ->
                runtime.detect(input, detectorPostprocessor)
            }
        if (detected.isEmpty()) return emptyList()

        val bounded =
            if (detected.size <= OcrInferenceContract.MaximumRegions) {
                detected
            } else {
                detected.sortedByDescending(DetectedTextRegion::confidence)
                    .take(OcrInferenceContract.MaximumRegions)
                    .sortedWith(ReadingOrder)
            }
        return OcrRecognizerImage.from(bitmap).use { image ->
            buildList(bounded.size) {
                bounded.chunked(OcrRecognizerContract.MaximumBatchSize).forEach { batch ->
                    recognizerPreprocessor.prepare(image, batch).use { input ->
                        val decoded = runtime.recognize(input)
                        check(decoded.size == batch.size)
                        batch.zip(decoded).forEach { (region, text) ->
                            if (text.rawText.isNotBlank()) {
                                add(
                                    RecognizedOcrRegion(
                                        quadrilateral = region.quadrilateral,
                                        rawText = text.rawText,
                                        confidence = minOf(region.confidence, text.confidence),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        val postprocessorFailure = runCatching(detectorPostprocessor::close).exceptionOrNull()
        val runtimeFailure = runCatching(runtime::close).exceptionOrNull()
        if (postprocessorFailure != null) {
            runtimeFailure?.let(postprocessorFailure::addSuppressed)
            throw postprocessorFailure
        }
        if (runtimeFailure != null) throw runtimeFailure
    }

    companion object {
        private val ReadingOrder =
            compareBy<DetectedTextRegion>(
                { region -> region.quadrilateral.topLeft.y },
                { region -> region.quadrilateral.topLeft.x },
            )
    }
}
