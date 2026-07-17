package app.nayti.ml.runtime.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.file.Files
import java.nio.file.Path

class OcrRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Owns the two OCR sessions and serializes every native inference call. */
class OcrOrtRuntime private constructor(
    private val environment: OrtEnvironment,
    private val detector: OrtSession,
    private val recognizer: OrtSession,
    val decoder: OcrCtcDecoder,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun detect(
        input: PreparedDetectorInput,
        postprocessor: OcrDetectorPostprocessor,
    ): List<DetectedTextRegion> {
        checkOpen()
        return runCatching {
            OnnxTensor.createTensor(environment, input.tensor.readableBuffer(), input.tensor.shape).use { tensor ->
                detector.run(mapOf(OcrDetectorContract.InputName to tensor)).use { result ->
                    val output = result.requiredTensor(OcrDetectorContract.OutputName)
                    val expectedShape =
                        longArrayOf(
                            1,
                            1,
                            input.resize.tensorHeight.toLong(),
                            input.resize.tensorWidth.toLong(),
                        )
                    require(output.info.type == OnnxJavaType.FLOAT)
                    require(output.info.shape.contentEquals(expectedShape)) {
                        "Unexpected detector output shape"
                    }
                    postprocessor.process(
                        output.floatBuffer,
                        input.resize.tensorWidth,
                        input.resize.tensorHeight,
                        input.resize,
                    )
                }
            }
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            throw OcrRuntimeException("OCR detector inference failed", failure)
        }
    }

    @Synchronized
    fun recognize(input: PreparedRecognizerBatch): List<DecodedOcrText> {
        checkOpen()
        return runCatching {
            OnnxTensor.createTensor(environment, input.tensor.readableBuffer(), input.tensor.shape).use { tensor ->
                recognizer.run(mapOf(OcrRecognizerContract.InputName to tensor)).use { result ->
                    val output = result.requiredTensor(OcrRecognizerContract.OutputName)
                    val expectedShape =
                        longArrayOf(
                            input.regions.size.toLong(),
                            OcrRecognizerContract.Timesteps.toLong(),
                            OcrRecognizerContract.Classes.toLong(),
                        )
                    require(output.info.type == OnnxJavaType.FLOAT)
                    require(output.info.shape.contentEquals(expectedShape)) {
                        "Unexpected recognizer output shape"
                    }
                    decoder.decode(output.floatBuffer, input.regions.size)
                }
            }
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            throw OcrRuntimeException("OCR recognizer inference failed", failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val recognizerFailure = runCatching(recognizer::close).exceptionOrNull()
        val detectorFailure = runCatching(detector::close).exceptionOrNull()
        if (recognizerFailure != null) {
            detectorFailure?.let(recognizerFailure::addSuppressed)
            throw recognizerFailure
        }
        if (detectorFailure != null) throw detectorFailure
    }

    private fun checkOpen() = check(!closed) { "OCR runtime is closed" }

    companion object {
        fun open(packPayloadDirectory: Path): OcrOrtRuntime {
            val payload = packPayloadDirectory.toAbsolutePath().normalize()
            val detectorPath = payload.requiredRegularFile("models/ppocrv6_detector.ort")
            val recognizerPath = payload.requiredRegularFile("models/eslav_recognizer.ort")
            val decoderPath = payload.requiredRegularFile("preprocessing/eslav-recognizer-decoder.json")
            val vocabulary = OcrDecoderVocabulary.parseCanonical(Files.readAllBytes(decoderPath))
            val environment = OrtEnvironment.getEnvironment()
            if (environment.version != RequiredOrtVersion) {
                throw OcrRuntimeException(
                    "Expected ONNX Runtime $RequiredOrtVersion, found ${environment.version}",
                )
            }
            environment.setTelemetry(false)
            var detector: OrtSession? = null
            var recognizer: OrtSession? = null
            try {
                detector = createSession(environment, detectorPath)
                recognizer = createSession(environment, recognizerPath)
                require(detector.inputNames == setOf(OcrDetectorContract.InputName))
                require(detector.outputNames == setOf(OcrDetectorContract.OutputName))
                require(recognizer.inputNames == setOf(OcrRecognizerContract.InputName))
                require(recognizer.outputNames == setOf(OcrRecognizerContract.OutputName))
                return OcrOrtRuntime(environment, detector, recognizer, OcrCtcDecoder(vocabulary))
            } catch (failure: Throwable) {
                runCatching { recognizer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { detector?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                if (failure is OcrRuntimeException) throw failure
                throw OcrRuntimeException("Cannot open OCR model sessions", failure)
            }
        }

        private fun createSession(environment: OrtEnvironment, model: Path): OrtSession {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setDeterministicCompute(true)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                return environment.createSession(model.toString(), options)
            }
        }

        private const val RequiredOrtVersion = "1.27.0"
    }
}

private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor =
    get(name).orElseThrow { OcrRuntimeException("Missing OCR output: $name") } as? OnnxTensor
        ?: throw OcrRuntimeException("OCR output is not a tensor: $name")

private fun Path.requiredRegularFile(relative: String): Path {
    val candidate = resolve(relative).normalize()
    if (!candidate.startsWith(this) || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
        throw OcrRuntimeException("Missing safe OCR pack artifact: $relative")
    }
    return candidate
}
