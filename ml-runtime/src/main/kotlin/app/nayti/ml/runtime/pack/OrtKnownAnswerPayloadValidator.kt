package app.nayti.ml.runtime.pack

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.nayti.ml.runtime.ocr.OcrDecoderVocabulary
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Opens every deploy graph and checks signed known-answer tensors before pack publication. */
class OrtKnownAnswerPayloadValidator : ModelPackPayloadValidator {
    override suspend fun validate(candidate: ModelPackValidationCandidate) {
        withContext(Dispatchers.Default) {
            OcrDecoderVocabulary.parseCanonical(
                java.nio.file.Files.readAllBytes(
                    candidate.payloadDirectory.resolve("preprocessing/eslav-recognizer-decoder.json"),
                ),
            )
            validatePackPayload(candidate.payloadDirectory.toFile())
        }
    }

    fun validatePackPayload(payloadRoot: File) {
        try {
            val canonicalRoot = payloadRoot.canonicalFile
            validate(
                modelRoot = resolveChild(canonicalRoot, "models"),
                fixtureRoot = resolveChild(canonicalRoot, "tests"),
                manifestFile = resolveChild(canonicalRoot, "tests/manifest.json"),
            )
        } catch (failure: Exception) {
            if (failure is ModelPackException) throw failure
            throw ModelPackException("Model pack runtime known-answer validation failed", failure)
        }
    }

    private fun validate(modelRoot: File, fixtureRoot: File, manifestFile: File) {
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        contract(manifest.getInt("schemaVersion") == 2, "Unsupported runtime KAT schema")
        contract(manifest.getString("byteOrder") == "little", "Runtime KAT byte order drifted")
        contract(manifest.getInt("seed") == RequiredSeed, "Runtime KAT seed drifted")
        val models = manifest.getJSONObject("models")
        contract(jsonKeys(models) == ModelOrder.toSet(), "Runtime KAT model inventory drifted")

        val environment = OrtEnvironment.getEnvironment()
        contract(environment.version == RequiredOrtVersion, "ONNX Runtime version drifted")
        environment.setTelemetry(false)
        ModelOrder.forEach { modelName ->
            runModel(environment, modelRoot, fixtureRoot, modelName, models.getJSONObject(modelName))
        }
    }

    private fun runModel(
        environment: OrtEnvironment,
        modelRoot: File,
        fixtureRoot: File,
        modelName: String,
        modelContractJson: JSONObject,
    ) {
        val modelContract = modelContractJson.getJSONObject("model")
        val modelFile = resolveChild(modelRoot, modelContract.getString("path"))
        assertFileIdentity(modelFile, modelContract)
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setDeterministicCompute(true)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                val inputContracts = modelContractJson.getJSONObject("inputs")
                contract(jsonKeys(inputContracts) == session.inputNames, "$modelName input names drifted")
                val feeds = linkedMapOf<String, OnnxTensor>()
                try {
                    session.inputNames.sorted().forEach { inputName ->
                        feeds[inputName] =
                            createInput(environment, fixtureRoot, inputContracts.getJSONObject(inputName))
                    }
                    session.run(feeds).use { result ->
                        verifyOutputs(modelName, fixtureRoot, modelContractJson.getJSONArray("outputs"), result)
                    }
                } finally {
                    feeds.values.forEach(OnnxTensor::close)
                }
            }
        }
    }

    private fun createInput(
        environment: OrtEnvironment,
        root: File,
        contract: JSONObject,
    ): OnnxTensor {
        val shape = longArray(contract.getJSONArray("shape"))
        return when (val dtype = contract.getString("dtype")) {
            "string" -> {
                val values = contract.getJSONArray("values")
                val strings = Array(values.length()) { index -> values.getString(index) }
                OnnxTensor.createTensor(environment, strings, shape)
            }
            "float32", "int64" -> {
                val file = resolveChild(root, contract.getString("path"))
                assertFileIdentity(file, contract)
                val bytes = readDirect(file)
                if (dtype == "float32") {
                    OnnxTensor.createTensor(environment, bytes.asFloatBuffer(), shape)
                } else {
                    OnnxTensor.createTensor(environment, bytes.asLongBuffer(), shape)
                }
            }
            else -> throw ModelPackException("Unsupported runtime KAT input type: $dtype")
        }
    }

    private fun verifyOutputs(
        modelName: String,
        root: File,
        contracts: JSONArray,
        result: OrtSession.Result,
    ) {
        contract(result.size() == contracts.length(), "$modelName output count drifted")
        for (index in 0 until contracts.length()) {
            val outputContract = contracts.getJSONObject(index)
            val outputName = outputContract.getString("name")
            val tensor = result.get(outputName).orElseThrow {
                ModelPackException("Missing runtime KAT output: $modelName/$outputName")
            } as? OnnxTensor ?: throw ModelPackException("Runtime KAT output is not a tensor: $modelName/$outputName")
            contract(
                tensor.info.shape.contentEquals(longArray(outputContract.getJSONArray("shape"))),
                "$modelName/$outputName shape drifted",
            )
            val expectedFile = resolveChild(root, outputContract.getString("path"))
            assertFileIdentity(expectedFile, outputContract)
            when (val dtype = outputContract.getString("dtype")) {
                "float32" -> {
                    contract(tensor.info.type == OnnxJavaType.FLOAT, "$modelName/$outputName type drifted")
                    compareFloatOutput(modelName, outputName, expectedFile, outputContract, tensor)
                }
                "int64" -> {
                    contract(tensor.info.type == OnnxJavaType.INT64, "$modelName/$outputName type drifted")
                    compareLongOutput(modelName, outputName, expectedFile, tensor)
                }
                else -> throw ModelPackException("Unsupported runtime KAT output type: $dtype")
            }
        }
    }

    private fun compareFloatOutput(
        modelName: String,
        outputName: String,
        expectedFile: File,
        outputContract: JSONObject,
        actualTensor: OnnxTensor,
    ) {
        val expectedBuffer = readDirect(expectedFile).asFloatBuffer()
        val actualBuffer = actualTensor.floatBuffer
        contract(expectedBuffer.remaining() == actualBuffer.remaining(), "$modelName/$outputName length drifted")
        val expected = FloatArray(expectedBuffer.remaining()) { index -> expectedBuffer.get(index) }
        val actual = FloatArray(actualBuffer.remaining()) { index -> actualBuffer.get(index) }
        contract(actual.all(Float::isFinite), "$modelName/$outputName contains non-finite values")
        val comparison = outputContract.getJSONObject("comparison")
        when (val kind = comparison.getString("kind")) {
            "allclose" -> compareAllClose(modelName, outputName, expected, actual, comparison)
            "cosine" -> compareCosine(modelName, outputName, expected, actual, comparison)
            else -> throw ModelPackException("Unsupported runtime KAT comparison: $kind")
        }
    }

    private fun compareAllClose(
        modelName: String,
        outputName: String,
        expected: FloatArray,
        actual: FloatArray,
        comparison: JSONObject,
    ) {
        val absoluteTolerance = comparison.getDouble("absolute")
        val relativeTolerance = comparison.getDouble("relative")
        for (index in expected.indices) {
            val error = abs(actual[index].toDouble() - expected[index].toDouble())
            val bound = absoluteTolerance + relativeTolerance * abs(expected[index].toDouble())
            contract(error <= bound, "$modelName/$outputName exceeds allclose tolerance")
        }
    }

    private fun compareCosine(
        modelName: String,
        outputName: String,
        expected: FloatArray,
        actual: FloatArray,
        comparison: JSONObject,
    ) {
        var dot = 0.0
        var expectedSquaredNorm = 0.0
        var actualSquaredNorm = 0.0
        var maximumAbsoluteError = 0.0
        for (index in expected.indices) {
            val expectedValue = expected[index].toDouble()
            val actualValue = actual[index].toDouble()
            dot += expectedValue * actualValue
            expectedSquaredNorm += expectedValue * expectedValue
            actualSquaredNorm += actualValue * actualValue
            maximumAbsoluteError = maxOf(maximumAbsoluteError, abs(actualValue - expectedValue))
        }
        contract(expectedSquaredNorm > 0.0 && actualSquaredNorm > 0.0, "$modelName/$outputName has a zero norm")
        val cosine = dot / kotlin.math.sqrt(expectedSquaredNorm * actualSquaredNorm)
        contract(cosine >= comparison.getDouble("minimumCosine"), "$modelName/$outputName cosine is below tolerance")
        contract(
            maximumAbsoluteError <= comparison.getDouble("maximumAbsoluteError"),
            "$modelName/$outputName absolute error is above tolerance",
        )
    }

    private fun compareLongOutput(
        modelName: String,
        outputName: String,
        expectedFile: File,
        actualTensor: OnnxTensor,
    ) {
        val expected = readDirect(expectedFile).asLongBuffer()
        val actual = actualTensor.longBuffer
        contract(expected.remaining() == actual.remaining(), "$modelName/$outputName length drifted")
        for (index in 0 until expected.remaining()) {
            contract(expected.get(index) == actual.get(index), "$modelName/$outputName value drifted")
        }
    }

    private fun assertFileIdentity(file: File, contract: JSONObject) {
        contract(file.isFile, "Runtime KAT artifact is missing")
        contract(file.length() == contract.getLong("length"), "Runtime KAT artifact length changed")
        contract(sha256(file) == contract.getString("sha256"), "Runtime KAT artifact identity changed")
    }

    private fun readDirect(file: File): ByteBuffer {
        contract(file.length() <= Int.MAX_VALUE, "Runtime KAT tensor exceeds the direct-buffer limit")
        val buffer = ByteBuffer.allocateDirect(file.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(file).channel.use { channel ->
            while (buffer.hasRemaining()) {
                contract(channel.read(buffer) >= 0, "Runtime KAT tensor is truncated")
            }
        }
        buffer.flip()
        return buffer
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HashBufferBytes)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun resolveChild(root: File, relative: String): File {
        contract(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\'), "Unsafe KAT path")
        val child = File(root, relative).canonicalFile
        contract(child.path.startsWith(root.path + File.separator), "Runtime KAT path escaped its root")
        return child
    }

    private fun jsonKeys(value: JSONObject): Set<String> =
        buildSet {
            val keys = value.keys()
            while (keys.hasNext()) add(keys.next())
        }

    private fun longArray(value: JSONArray): LongArray =
        LongArray(value.length()) { index -> value.getLong(index) }

    private fun contract(condition: Boolean, message: String) {
        if (!condition) throw ModelPackException(message)
    }

    private companion object {
        const val RequiredOrtVersion = "1.27.0"
        const val RequiredSeed = 20_260_717
        const val HashBufferBytes = 4 * 1024 * 1024
        val ModelOrder =
            listOf(
                "eslav_recognizer",
                "ppocrv6_detector",
                "siglip2_image",
                "siglip2_text",
                "siglip2_tokenizer",
                "user2_encoder",
                "user2_tokenizer",
            )
    }
}
