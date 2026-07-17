package app.nayti.ml.runtime.visual

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

/** Owns the official SigLIP2 tokenizer and text tower for one bounded visual query session. */
class Siglip2TextOrtRuntime private constructor(
    private val environment: OrtEnvironment,
    private val tokenizer: OrtSession,
    private val encoder: OrtSession,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun encodeQuery(text: String): Siglip2QuantizedVector {
        check(!closed) { "SigLIP2 text runtime is closed" }
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        require(normalized.isNotBlank())
        return runCatching {
            val inputIds = tokenize(normalized)
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, Siglip2Contract.TextSequenceLength.toLong()),
            ).use { ids ->
                encoder.run(mapOf(Siglip2Contract.TextTokenIdsName to ids)).use { result ->
                    val output = result.requiredVisualTensor(Siglip2Contract.TextEmbeddingName)
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
            throw VisualRuntimeException("SigLIP2 text inference failed", failure)
        }
    }

    private fun tokenize(text: String): LongArray =
        OnnxTensor.createTensor(environment, arrayOf(text), longArrayOf(1)).use { input ->
            tokenizer.run(mapOf(Siglip2Contract.TextInputName to input)).use { result ->
                val ids = result.requiredVisualTensor(Siglip2Contract.TextTokenIdsName)
                require(ids.info.type == OnnxJavaType.INT64)
                require(
                    ids.info.shape.contentEquals(
                        longArrayOf(1, Siglip2Contract.TextSequenceLength.toLong()),
                    ),
                )
                LongArray(Siglip2Contract.TextSequenceLength) { index -> ids.longBuffer.get(index) }
            }
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val encoderFailure = runCatching(encoder::close).exceptionOrNull()
        val tokenizerFailure = runCatching(tokenizer::close).exceptionOrNull()
        if (encoderFailure != null) {
            tokenizerFailure?.let(encoderFailure::addSuppressed)
            throw encoderFailure
        }
        if (tokenizerFailure != null) throw tokenizerFailure
    }

    companion object {
        fun open(packPayloadDirectory: Path): Siglip2TextOrtRuntime {
            val payload = packPayloadDirectory.toAbsolutePath().normalize()
            val tokenizerPath = payload.requiredSiglip2File("models/siglip2_tokenizer.ort")
            val encoderPath = payload.requiredSiglip2File("models/siglip2_text.ort")
            val environment = OrtEnvironment.getEnvironment()
            if (environment.version != Siglip2Contract.OrtVersion) {
                throw VisualRuntimeException(
                    "Expected ONNX Runtime ${Siglip2Contract.OrtVersion}, found ${environment.version}",
                )
            }
            environment.setTelemetry(false)
            var tokenizer: OrtSession? = null
            var encoder: OrtSession? = null
            try {
                tokenizer = createSession(environment, tokenizerPath)
                encoder = createSession(environment, encoderPath)
                require(tokenizer.inputNames == setOf(Siglip2Contract.TextInputName))
                require(tokenizer.outputNames == setOf(Siglip2Contract.TextTokenIdsName))
                require(encoder.inputNames == setOf(Siglip2Contract.TextTokenIdsName))
                require(encoder.outputNames == setOf(Siglip2Contract.TextEmbeddingName))
                return Siglip2TextOrtRuntime(environment, tokenizer, encoder)
            } catch (failure: Throwable) {
                runCatching { encoder?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { tokenizer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                if (failure is VisualRuntimeException) throw failure
                throw VisualRuntimeException("Cannot open SigLIP2 text model sessions", failure)
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

private fun OrtSession.Result.requiredVisualTensor(name: String): OnnxTensor =
    get(name).orElseThrow { VisualRuntimeException("Missing SigLIP2 output: $name") } as? OnnxTensor
        ?: throw VisualRuntimeException("SigLIP2 output is not a tensor: $name")

private fun Path.requiredSiglip2File(relative: String): Path {
    val candidate = resolve(relative).normalize()
    if (!candidate.startsWith(this) || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
        throw VisualRuntimeException("Missing safe SigLIP2 pack artifact: $relative")
    }
    return candidate
}
