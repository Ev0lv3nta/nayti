package app.nayti.ml.runtime.visual

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path

object Siglip2Contract {
    const val PipelineVersion = "siglip2-image-v1"
    const val ImageEdge = 256
    const val DecodeLongSide = 512
    const val MaximumDecodedEdge = 2_048
    const val EmbeddingDimension = 768
    const val OrtVersion = "1.27.0"
    const val VectorEncodingVersion = "l2-symmetric-qint8-v1"
    const val ResizeContract = "pillow-compatible-bilinear-rgb-stretch-256-v1"
    internal const val ImageInputName = "pixel_values"
    internal const val ImageEmbeddingName = "image_embedding"
}

class VisualRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Owns the SigLIP2 image tower for exactly one bounded visual-indexing window. */
class Siglip2ImageOrtRuntime private constructor(
    private val environment: OrtEnvironment,
    private val encoder: OrtSession,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun encode(bitmap: Bitmap): Siglip2QuantizedVector {
        check(!closed) { "SigLIP2 image runtime is closed" }
        return runCatching {
            val pixels = Siglip2ImagePreprocessor.preprocess(bitmap)
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(pixels),
                longArrayOf(1, 3, Siglip2Contract.ImageEdge.toLong(), Siglip2Contract.ImageEdge.toLong()),
            ).use { input ->
                encoder.run(mapOf(Siglip2Contract.ImageInputName to input)).use { result ->
                    val output = result.requiredTensor(Siglip2Contract.ImageEmbeddingName)
                    require(output.info.type == OnnxJavaType.FLOAT)
                    require(
                        output.info.shape.contentEquals(
                            longArrayOf(1, Siglip2Contract.EmbeddingDimension.toLong()),
                        ),
                    )
                    val buffer = output.floatBuffer
                    Siglip2VectorQuantizer.normalizeAndQuantize(
                        FloatArray(Siglip2Contract.EmbeddingDimension) { index -> buffer.get(index) },
                    )
                }
            }
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            throw VisualRuntimeException("SigLIP2 image inference failed", failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        encoder.close()
    }

    companion object {
        fun open(packPayloadDirectory: Path): Siglip2ImageOrtRuntime {
            val payload = packPayloadDirectory.toAbsolutePath().normalize()
            val model = payload.requiredVisualFile("models/siglip2_image.ort")
            val environment = OrtEnvironment.getEnvironment()
            if (environment.version != Siglip2Contract.OrtVersion) {
                throw VisualRuntimeException(
                    "Expected ONNX Runtime ${Siglip2Contract.OrtVersion}, found ${environment.version}",
                )
            }
            environment.setTelemetry(false)
            var encoder: OrtSession? = null
            try {
                encoder = createSession(environment, model)
                require(encoder.inputNames == setOf(Siglip2Contract.ImageInputName))
                require(encoder.outputNames == setOf(Siglip2Contract.ImageEmbeddingName))
                return Siglip2ImageOrtRuntime(environment, encoder)
            } catch (failure: Throwable) {
                runCatching { encoder?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                if (failure is VisualRuntimeException) throw failure
                throw VisualRuntimeException("Cannot open SigLIP2 image model session", failure)
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
    }
}

private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor =
    get(name).orElseThrow { VisualRuntimeException("Missing SigLIP2 output: $name") } as? OnnxTensor
        ?: throw VisualRuntimeException("SigLIP2 output is not a tensor: $name")

private fun Path.requiredVisualFile(relative: String): Path {
    val candidate = resolve(relative).normalize()
    if (!candidate.startsWith(this) || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
        throw VisualRuntimeException("Missing safe SigLIP2 pack artifact: $relative")
    }
    return candidate
}
