package app.nayti.ml.runtime.ocr

import app.nayti.ml.runtime.pack.CanonicalJson
import app.nayti.ml.runtime.pack.JsonValue
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs

class OcrDecoderContractException(message: String) : Exception(message)

class OcrDecoderVocabulary private constructor(
    private val values: List<String>,
) {
    val size: Int = values.size

    operator fun get(index: Int): String = values[index]

    companion object {
        fun parseCanonical(raw: ByteArray): OcrDecoderVocabulary {
            val root =
                try {
                    CanonicalJson.parseCanonical(raw)
                } catch (failure: Exception) {
                    throw OcrDecoderContractException("OCR decoder is not canonical JSON: ${failure.message}")
                }
            val expectedKeys =
                setOf(
                    "blankIndex",
                    "decoderSha256",
                    "removeConsecutiveDuplicates",
                    "schemaVersion",
                    "tokens",
                )
            if (root.entries.keys != expectedKeys) throw OcrDecoderContractException("OCR decoder fields drifted")
            if (root.integer("schemaVersion") != 1L) throw OcrDecoderContractException("Unsupported OCR decoder schema")
            if (root.integer("blankIndex") != OcrRecognizerContract.BlankIndex.toLong()) {
                throw OcrDecoderContractException("OCR blank index drifted")
            }
            if (!root.boolean("removeConsecutiveDuplicates")) {
                throw OcrDecoderContractException("OCR duplicate-removal contract drifted")
            }
            val declaredSha = root.string("decoderSha256")
            if (declaredSha != OcrRecognizerContract.DecoderSha256) {
                throw OcrDecoderContractException("OCR decoder identity drifted")
            }
            val tokenValues =
                (root.entries["tokens"] as? JsonValue.ArrayValue)?.values
                    ?: throw OcrDecoderContractException("OCR tokens must be an array")
            val tokens = tokenValues.map { value ->
                (value as? JsonValue.StringValue)?.value
                    ?: throw OcrDecoderContractException("OCR token must be a string")
            }
            if (
                tokens.size != OcrRecognizerContract.Classes ||
                tokens[OcrRecognizerContract.BlankIndex] != "blank" ||
                tokens[OcrRecognizerContract.SpaceIndex] != " " ||
                tokens.subList(1, OcrRecognizerContract.SpaceIndex).toSet().size !=
                OcrRecognizerContract.CharacterCount
            ) {
                throw OcrDecoderContractException("OCR token inventory drifted")
            }
            val encodedTokens =
                CanonicalJson.encode(
                    JsonValue.ArrayValue(tokens.map(JsonValue::StringValue)),
                ).dropLast(1).toByteArray()
            val actualSha = MessageDigest.getInstance("SHA-256").digest(encodedTokens).toHex()
            if (actualSha != declaredSha) throw OcrDecoderContractException("OCR decoder hash mismatch")
            return OcrDecoderVocabulary(tokens)
        }

        internal fun forTesting(tokens: List<String>): OcrDecoderVocabulary = OcrDecoderVocabulary(tokens.toList())
    }
}

data class DecodedOcrText(
    val rawText: String,
    val confidence: Float,
)

class OcrCtcDecoder(
    private val vocabulary: OcrDecoderVocabulary,
) {
    fun decode(probabilities: FloatBuffer, batchSize: Int): List<DecodedOcrText> {
        require(batchSize in 1..OcrRecognizerContract.MaximumBatchSize)
        require(vocabulary.size == OcrRecognizerContract.Classes)
        val expected =
            batchSize * OcrRecognizerContract.Timesteps * OcrRecognizerContract.Classes
        require(probabilities.remaining() == expected) { "Unexpected recognizer probability count" }
        val values = probabilities.duplicate()
        val base = values.position()
        return List(batchSize) { batchIndex -> decodeItem(values, base, batchIndex) }
    }

    private fun decodeItem(
        values: FloatBuffer,
        base: Int,
        batchIndex: Int,
    ): DecodedOcrText {
        val text = StringBuilder()
        var previousClass = -1
        var confidenceTotal = 0.0
        var emitted = 0
        for (timestep in 0 until OcrRecognizerContract.Timesteps) {
            val row =
                base +
                    (batchIndex * OcrRecognizerContract.Timesteps + timestep) *
                    OcrRecognizerContract.Classes
            var bestClass = 0
            var bestProbability = -1.0f
            var sum = 0.0
            for (classIndex in 0 until OcrRecognizerContract.Classes) {
                val probability = values.get(row + classIndex)
                require(probability.isFinite() && probability in 0.0f..1.0f) {
                    "Recognizer output contains an invalid probability"
                }
                sum += probability
                if (probability > bestProbability) {
                    bestProbability = probability
                    bestClass = classIndex
                }
            }
            require(abs(sum - 1.0) <= ProbabilitySumTolerance) {
                "Recognizer class probabilities do not sum to one"
            }
            if (bestClass != OcrRecognizerContract.BlankIndex && bestClass != previousClass) {
                text.append(vocabulary[bestClass])
                confidenceTotal += bestProbability
                emitted++
            }
            previousClass = bestClass
        }
        return DecodedOcrText(
            rawText = text.toString(),
            confidence = if (emitted == 0) 0.0f else (confidenceTotal / emitted).toFloat(),
        )
    }

    private companion object {
        const val ProbabilitySumTolerance = 1e-3
    }
}

private fun JsonValue.ObjectValue.integer(name: String): Long =
    (entries[name] as? JsonValue.IntegerValue)?.value
        ?: throw OcrDecoderContractException("$name must be an integer")

private fun JsonValue.ObjectValue.string(name: String): String =
    (entries[name] as? JsonValue.StringValue)?.value
        ?: throw OcrDecoderContractException("$name must be a string")

private fun JsonValue.ObjectValue.boolean(name: String): Boolean =
    (entries[name] as? JsonValue.BooleanValue)?.value
        ?: throw OcrDecoderContractException("$name must be a boolean")

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
