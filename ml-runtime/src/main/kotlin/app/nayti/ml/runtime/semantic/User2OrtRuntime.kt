package app.nayti.ml.runtime.semantic

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

object User2Contract {
    const val SequenceLength = 128
    const val EmbeddingDimension = 384
    const val DocumentPrefix = "search_document: "
    const val QueryPrefix = "search_query: "
    const val SpecialTokenCount = 2
    const val MaximumContentTokens = 96
    const val OrtVersion = "1.27.0"
    const val VectorEncodingVersion = "l2-symmetric-qint8-v1"
    internal const val TextInputName = "text"
    internal const val InputIdsName = "input_ids"
    internal const val AttentionMaskName = "attention_mask"
    internal const val EmbeddingName = "sentence_embedding"
}

class SemanticRuntimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class User2TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
) {
    init {
        require(inputIds.size == User2Contract.SequenceLength)
        require(attentionMask.size == User2Contract.SequenceLength)
        require(attentionMask.all { it == 0L || it == 1L })
        require((1 until attentionMask.size).none { index ->
            attentionMask[index - 1] == 0L && attentionMask[index] == 1L
        })
    }

    val occupiedTokenCount: Int = attentionMask.count { it == 1L }
}

/** Owns the official USER2 tokenizer and encoder sessions for one bounded execution window. */
class User2OrtRuntime private constructor(
    private val environment: OrtEnvironment,
    private val tokenizer: OrtSession,
    private val encoder: OrtSession,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun contentTokenCount(text: String): Int {
        checkOpen()
        val tokenized = tokenize(normalize(text))
        return (tokenized.occupiedTokenCount - User2Contract.SpecialTokenCount).coerceAtLeast(0)
    }

    @Synchronized
    fun encodeDocument(text: String): NormalizedQuantizedVector =
        encode(User2Contract.DocumentPrefix + normalize(text))

    @Synchronized
    fun encodeQuery(text: String): NormalizedQuantizedVector =
        encode(User2Contract.QueryPrefix + normalize(text))

    private fun encode(prefixedText: String): NormalizedQuantizedVector {
        checkOpen()
        return runCatching {
            val tokenized = tokenize(prefixedText)
            require(tokenized.occupiedTokenCount <= User2Contract.SequenceLength)
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenized.inputIds),
                longArrayOf(1, User2Contract.SequenceLength.toLong()),
            ).use { ids ->
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(tokenized.attentionMask),
                    longArrayOf(1, User2Contract.SequenceLength.toLong()),
                ).use { mask ->
                    encoder.run(
                        mapOf(
                            User2Contract.InputIdsName to ids,
                            User2Contract.AttentionMaskName to mask,
                        ),
                    ).use { result ->
                        val output = result.requiredTensor(User2Contract.EmbeddingName)
                        require(output.info.type == OnnxJavaType.FLOAT)
                        require(
                            output.info.shape.contentEquals(
                                longArrayOf(1, User2Contract.EmbeddingDimension.toLong()),
                            ),
                        )
                        val buffer = output.floatBuffer
                        val embedding = FloatArray(User2Contract.EmbeddingDimension) { index -> buffer.get(index) }
                        User2VectorQuantizer.normalizeAndQuantize(embedding)
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            throw SemanticRuntimeException("USER2 encoder inference failed", failure)
        }
    }

    private fun tokenize(text: String): User2TokenizedInput =
        runCatching {
            OnnxTensor.createTensor(environment, arrayOf(text), longArrayOf(1)).use { input ->
                tokenizer.run(mapOf(User2Contract.TextInputName to input)).use { result ->
                    val ids = result.requiredTensor(User2Contract.InputIdsName)
                    val mask = result.requiredTensor(User2Contract.AttentionMaskName)
                    require(ids.info.type == OnnxJavaType.INT64 && mask.info.type == OnnxJavaType.INT64)
                    val expectedShape = longArrayOf(1, User2Contract.SequenceLength.toLong())
                    require(ids.info.shape.contentEquals(expectedShape) && mask.info.shape.contentEquals(expectedShape))
                    User2TokenizedInput(
                        inputIds = LongArray(User2Contract.SequenceLength) { index -> ids.longBuffer.get(index) },
                        attentionMask = LongArray(User2Contract.SequenceLength) { index -> mask.longBuffer.get(index) },
                    )
                }
            }
        }.getOrElse { failure ->
            if (failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            throw SemanticRuntimeException("USER2 tokenizer inference failed", failure)
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

    private fun checkOpen() = check(!closed) { "USER2 runtime is closed" }

    companion object {
        fun open(packPayloadDirectory: Path): User2OrtRuntime {
            val payload = packPayloadDirectory.toAbsolutePath().normalize()
            val tokenizerPath = payload.requiredRegularFile("models/user2_tokenizer.ort")
            val encoderPath = payload.requiredRegularFile("models/user2_encoder.ort")
            val environment = OrtEnvironment.getEnvironment()
            if (environment.version != User2Contract.OrtVersion) {
                throw SemanticRuntimeException(
                    "Expected ONNX Runtime ${User2Contract.OrtVersion}, found ${environment.version}",
                )
            }
            environment.setTelemetry(false)
            var tokenizer: OrtSession? = null
            var encoder: OrtSession? = null
            try {
                tokenizer = createSession(environment, tokenizerPath)
                encoder = createSession(environment, encoderPath)
                require(tokenizer.inputNames == setOf(User2Contract.TextInputName))
                require(tokenizer.outputNames == setOf(User2Contract.InputIdsName, User2Contract.AttentionMaskName))
                require(encoder.inputNames == setOf(User2Contract.InputIdsName, User2Contract.AttentionMaskName))
                require(encoder.outputNames == setOf(User2Contract.EmbeddingName))
                return User2OrtRuntime(environment, tokenizer, encoder)
            } catch (failure: Throwable) {
                runCatching { encoder?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { tokenizer?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                if (failure is SemanticRuntimeException) throw failure
                throw SemanticRuntimeException("Cannot open USER2 model sessions", failure)
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

        private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
    }
}

private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor =
    get(name).orElseThrow { SemanticRuntimeException("Missing USER2 output: $name") } as? OnnxTensor
        ?: throw SemanticRuntimeException("USER2 output is not a tensor: $name")

private fun Path.requiredRegularFile(relative: String): Path {
    val candidate = resolve(relative).normalize()
    if (!candidate.startsWith(this) || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate)) {
        throw SemanticRuntimeException("Missing safe USER2 pack artifact: $relative")
    }
    return candidate
}
