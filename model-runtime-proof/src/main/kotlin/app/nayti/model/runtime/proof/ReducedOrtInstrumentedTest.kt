package app.nayti.model.runtime.proof

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReducedOrtInstrumentedTest {
    @Test
    fun runsEveryGraphWithPinnedReducedRuntime() {
        val arguments = InstrumentationRegistry.getArguments()
        val rootArgument = requireNotNull(arguments.getString("katRoot")) {
            "instrumentation argument katRoot is required"
        }
        val root = File(rootArgument).canonicalFile
        val manifestFile = resolveChild(root, "manifest.json")
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))

        assertEquals(2, manifest.getInt("schemaVersion"))
        assertEquals("little", manifest.getString("byteOrder"))
        assertEquals(20260717, manifest.getInt("seed"))

        val models = manifest.getJSONObject("models")
        assertEquals(MODEL_ORDER.toSet(), jsonKeys(models))

        val environment = OrtEnvironment.getEnvironment()
        assertEquals("1.27.0", environment.version)
        environment.setTelemetry(false)

        for (modelName in MODEL_ORDER) {
            runModel(environment, root, modelName, models.getJSONObject(modelName))
        }
    }

    private fun runModel(
        environment: OrtEnvironment,
        root: File,
        modelName: String,
        contract: JSONObject,
    ) {
        val modelContract = contract.getJSONObject("model")
        val modelFile = resolveChild(root, modelContract.getString("path"))
        assertFileIdentity(modelFile, modelContract)

        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setDeterministicCompute(true)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                val inputContracts = contract.getJSONObject("inputs")
                assertEquals(jsonKeys(inputContracts), session.inputNames)
                val feeds = linkedMapOf<String, OnnxTensor>()
                try {
                    for (inputName in session.inputNames.sorted()) {
                        feeds[inputName] = createInput(
                            environment,
                            root,
                            inputContracts.getJSONObject(inputName),
                        )
                    }
                    session.run(feeds).use { result ->
                        verifyOutputs(modelName, root, contract.getJSONArray("outputs"), result)
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
                when (dtype) {
                    "float32" -> OnnxTensor.createTensor(environment, bytes.asFloatBuffer(), shape)
                    "int64" -> OnnxTensor.createTensor(environment, bytes.asLongBuffer(), shape)
                    else -> error("unreachable")
                }
            }
            else -> error("unsupported input dtype: $dtype")
        }
    }

    private fun verifyOutputs(
        modelName: String,
        root: File,
        contracts: JSONArray,
        result: OrtSession.Result,
    ) {
        assertEquals(contracts.length(), result.size())
        for (index in 0 until contracts.length()) {
            val contract = contracts.getJSONObject(index)
            val outputName = contract.getString("name")
            val tensor = result.get(outputName).orElseThrow {
                AssertionError("missing output $modelName/$outputName")
            } as OnnxTensor
            val expectedShape = longArray(contract.getJSONArray("shape"))
            assertArrayEquals("shape $modelName/$outputName", expectedShape, tensor.info.shape)

            val expectedFile = resolveChild(root, contract.getString("path"))
            assertFileIdentity(expectedFile, contract)
            when (val dtype = contract.getString("dtype")) {
                "float32" -> {
                    assertEquals(OnnxJavaType.FLOAT, tensor.info.type)
                    compareFloatOutput(modelName, outputName, expectedFile, contract, tensor)
                }
                "int64" -> {
                    assertEquals(OnnxJavaType.INT64, tensor.info.type)
                    compareLongOutput(modelName, outputName, expectedFile, tensor)
                }
                else -> error("unsupported output dtype: $dtype")
            }
        }
    }

    private fun compareFloatOutput(
        modelName: String,
        outputName: String,
        expectedFile: File,
        contract: JSONObject,
        actualTensor: OnnxTensor,
    ) {
        val expected = readDirect(expectedFile).asFloatBuffer()
        val actual = actualTensor.floatBuffer
        assertEquals(expected.remaining(), actual.remaining())
        val comparison = contract.getJSONObject("comparison")
        val expectedValues = FloatArray(expected.remaining()) { index -> expected.get(index) }
        val actualValues = FloatArray(actual.remaining()) { index -> actual.get(index) }
        for (index in actualValues.indices) {
            assertTrue("non-finite $modelName/$outputName[$index]", actualValues[index].isFinite())
        }
        when (val kind = comparison.getString("kind")) {
            "allclose" -> compareAllClose(
                modelName,
                outputName,
                expectedValues,
                actualValues,
                comparison,
            )
            "cosine" -> compareCosine(
                modelName,
                outputName,
                expectedValues,
                actualValues,
                comparison,
            )
            else -> error("unsupported float comparison: $kind")
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
        var maximumAbsoluteError = 0.0
        for (index in expected.indices) {
            val expectedValue = expected[index]
            val actualValue = actual[index]
            val error = abs(actualValue.toDouble() - expectedValue.toDouble())
            maximumAbsoluteError = maxOf(maximumAbsoluteError, error)
            val bound = absoluteTolerance + relativeTolerance * abs(expectedValue.toDouble())
            assertTrue(
                "$modelName/$outputName[$index] error=$error bound=$bound",
                error <= bound,
            )
        }
        Log.i(TAG, "$modelName/$outputName maxAbs=$maximumAbsoluteError")
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
        assertTrue("zero expected norm $modelName/$outputName", expectedSquaredNorm > 0.0)
        assertTrue("zero actual norm $modelName/$outputName", actualSquaredNorm > 0.0)
        val cosine = dot / kotlin.math.sqrt(expectedSquaredNorm * actualSquaredNorm)
        val minimumCosine = comparison.getDouble("minimumCosine")
        val maximumAllowedError = comparison.getDouble("maximumAbsoluteError")
        assertTrue(
            "$modelName/$outputName cosine=$cosine minimum=$minimumCosine",
            cosine >= minimumCosine,
        )
        assertTrue(
            "$modelName/$outputName maxAbs=$maximumAbsoluteError maximum=$maximumAllowedError",
            maximumAbsoluteError <= maximumAllowedError,
        )
        Log.i(TAG, "$modelName/$outputName cosine=$cosine maxAbs=$maximumAbsoluteError")
    }

    private fun compareLongOutput(
        modelName: String,
        outputName: String,
        expectedFile: File,
        actualTensor: OnnxTensor,
    ) {
        val expected = readDirect(expectedFile).asLongBuffer()
        val actual = actualTensor.longBuffer
        assertEquals(expected.remaining(), actual.remaining())
        for (index in 0 until expected.remaining()) {
            assertEquals("$modelName/$outputName[$index]", expected.get(index), actual.get(index))
        }
    }

    private fun assertFileIdentity(file: File, contract: JSONObject) {
        assertTrue("missing fixture: $file", file.isFile)
        assertEquals("length $file", contract.getLong("length"), file.length())
        assertEquals("sha256 $file", contract.getString("sha256"), sha256(file))
    }

    private fun readDirect(file: File): ByteBuffer {
        require(file.length() <= Int.MAX_VALUE) { "fixture is too large for one tensor: $file" }
        val buffer = ByteBuffer.allocateDirect(file.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(file).channel.use { channel ->
            while (buffer.hasRemaining()) {
                check(channel.read(buffer) >= 0) { "truncated fixture: $file" }
            }
        }
        buffer.flip()
        return buffer
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4 * 1024 * 1024)
        FileInputStream(file).use { stream ->
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun resolveChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "unsafe path: $relative" }
        val child = File(root, relative).canonicalFile
        require(child.path.startsWith(root.path + File.separator)) { "path escapes KAT root: $relative" }
        return child
    }

    private fun jsonKeys(value: JSONObject): Set<String> {
        val result = linkedSetOf<String>()
        val keys = value.keys()
        while (keys.hasNext()) result += keys.next()
        return result
    }

    private fun longArray(value: JSONArray): LongArray =
        LongArray(value.length()) { index -> value.getLong(index) }

    private companion object {
        const val TAG = "NaytiOrtProof"
        val MODEL_ORDER = listOf(
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
