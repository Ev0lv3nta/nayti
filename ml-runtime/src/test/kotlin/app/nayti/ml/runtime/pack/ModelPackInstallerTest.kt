package app.nayti.ml.runtime.pack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder

class ModelPackInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun verifiedPackPublishesImmutableCandidateAndIsIdempotent() = runTest {
        val fixture = fixture()
        val installer = installer(fixture)

        val first = installer.install(ModelPackSource { ByteArrayInputStream(fixture.container) })
        val second = installer.install(ModelPackSource { ByteArrayInputStream(fixture.container) })

        assertEquals(first, second)
        assertEquals("nayti-offline-search", first.packId)
        assertEquals("0.1.0-alpha.2", first.packVersion)
        assertEquals(fixture.payloadBytes, first.payloadBytes)
        assertTrue(Files.isRegularFile(first.directory.resolve("manifest.json")))
        fixture.payloads.forEach { (path, bytes) ->
            assertTrue(bytes.contentEquals(Files.readAllBytes(first.directory.resolve("payload").resolve(path))))
        }
        assertNoTemporaryFiles()
    }

    @Test
    fun tamperTrailingPayloadAndUntrustedKeyLeaveNoCandidate() = runTest {
        val fixture = fixture()
        val tampered = fixture.container.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertInstallFails(installer(fixture), tampered, "SHA-256")
        assertInstallFails(installer(fixture), fixture.container + 1, "trailing")

        val other = fixture()
        assertInstallFails(
            ModelPackInstaller(
                root(),
                other.trustedKeys,
                policy(),
                storageBudget = ModelPackStorageBudget { Long.MAX_VALUE },
                payloadValidator = ModelPackPayloadValidator {},
                minimumFreeBytesAfterInstall = 0,
            ),
            fixture.container,
            "not trusted",
        )
        assertFalse(Files.exists(root().resolve("nayti-offline-search")))
        assertNoTemporaryFiles()
    }

    @Test
    fun runtimeValidationMustPassAndCannotMutatePayload() = runTest {
        val fixture = fixture()
        val rejection =
            installer(fixture, ModelPackPayloadValidator { throw ModelPackException("runtime KAT failed") })

        assertInstallFails(rejection, fixture.container, "runtime KAT")
        assertFalse(Files.exists(root().resolve("nayti-offline-search")))
        assertNoTemporaryFiles()

        val mutation =
            installer(
                fixture,
                ModelPackPayloadValidator { candidate ->
                    Files.writeString(candidate.payloadDirectory.resolve("models/siglip2_image.ort"), "changed")
                },
            )
        assertInstallFails(mutation, fixture.container, "corrupt")
        assertFalse(Files.exists(root().resolve("nayti-offline-search")))
        assertNoTemporaryFiles()
    }

    @Test
    fun startupCleanupRemovesOnlyInterruptedImports() = runTest {
        val fixture = fixture()
        val installer = installer(fixture)
        val incoming = root().resolve(".incoming-old.naytipack")
        val staging = root().resolve(".staging-old")
        val unrelated = root().resolve("keep")
        Files.createDirectories(staging)
        Files.writeString(incoming, "partial")
        Files.writeString(unrelated, "keep")

        installer.cleanupInterruptedImports()

        assertFalse(Files.exists(incoming))
        assertFalse(Files.exists(staging))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun realAlphaPackMatchesAndroidImporterContract() = runTest {
        val rawPath = System.getenv("NAYTI_REAL_MODEL_PACK")
        assumeTrue("NAYTI_REAL_MODEL_PACK is not set", !rawPath.isNullOrBlank())
        val installer =
            ModelPackInstaller(
                root(),
                AlphaModelPackTrust.keys,
                policy(),
                storageBudget = ModelPackStorageBudget { Long.MAX_VALUE },
                payloadValidator = ModelPackPayloadValidator {},
                minimumFreeBytesAfterInstall = 0,
            )

        val installed = installer.install(FileModelPackSource(java.nio.file.Path.of(rawPath)))

        assertEquals("2c90206b2c1ac09233a2b4f3c882dbe4e721bd52ddc3bde46cc6631d51a42167", sha256(java.nio.file.Path.of(rawPath)))
        assertEquals("1f87cfe37659bee690441e464ae66415c1623e8ae751320a9483adc6aff79d83", installed.manifestSha256)
        assertEquals(1_013_966_012L, installed.payloadBytes)
        assertTrue(Files.isRegularFile(installed.directory.resolve("payload/models/siglip2_image.ort")))
    }

    private suspend fun assertInstallFails(installer: ModelPackInstaller, bytes: ByteArray, message: String) {
        val failure = runCatching {
            installer.install(ModelPackSource { ByteArrayInputStream(bytes) })
        }.exceptionOrNull()
        assertTrue("Expected ModelPackException containing '$message', got $failure", failure is ModelPackException)
        assertTrue(failure?.message.orEmpty().contains(message, ignoreCase = true))
    }

    private fun installer(
        fixture: Fixture,
        validator: ModelPackPayloadValidator = ModelPackPayloadValidator {},
    ) =
        ModelPackInstaller(
            root(),
            fixture.trustedKeys,
            policy(),
            storageBudget = ModelPackStorageBudget { Long.MAX_VALUE },
            payloadValidator = validator,
            minimumFreeBytesAfterInstall = 0,
        )

    private fun root() = temporary.root.toPath().resolve("model-packs")

    private fun policy() =
        ModelPackPolicy(
            appVersionCode = 1,
            engineApi = 1,
            androidApi = 30,
            supportedAbis = setOf("arm64-v8a"),
            pageSize = 4096,
        )

    private fun assertNoTemporaryFiles() {
        if (!Files.exists(root())) return
        Files.list(root()).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".") })
        }
    }

    private fun sha256(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun fixture(): Fixture {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).toHex().take(32)
        val payloads =
            linkedMapOf(
                "models/eslav_recognizer.ort" to "rec".toByteArray(),
                "models/ppocrv6_detector.ort" to "det".toByteArray(),
                "models/siglip2_image.ort" to "image".toByteArray(),
                "models/siglip2_text.ort" to "text".toByteArray(),
                "models/siglip2_tokenizer.ort" to "stok".toByteArray(),
                "models/user2_encoder.ort" to "user".toByteArray(),
                "models/user2_tokenizer.ort" to "utok".toByteArray(),
                "operators/required-operators.config" to "ops".toByteArray(),
                "preprocessing/eslav-recognizer-decoder.json" to "decoder".toByteArray(),
                "tests/manifest.json" to "kat".toByteArray(),
            )
        val manifest = manifest(keyId, payloads)
        val manifestBytes = CanonicalJson.encode(manifest)
        val signature = sign(keyPair, manifestBytes)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write("NAYTIPK1".toByteArray(Charsets.US_ASCII))
            stream.writeInt(manifestBytes.size)
            stream.writeShort(signature.size)
            stream.writeShort(0)
            stream.write(manifestBytes)
            stream.write(signature)
            payloads.toSortedMap().values.forEach(stream::write)
        }
        return Fixture(
            output.toByteArray(),
            payloads.toSortedMap(),
            payloads.values.sumOf(ByteArray::size).toLong(),
            mapOf(keyId to TrustedModelPackKey(keyId, keyPair.public.encoded.takeLast(32).toByteArray())),
        )
    }

    private fun manifest(keyId: String, payloads: Map<String, ByteArray>): JsonValue.ObjectValue =
        obj(
            "schemaVersion" to integer(1),
            "packId" to string("nayti-offline-search"),
            "packVersion" to string("0.1.0-alpha.2"),
            "keyId" to string(keyId),
            "compatibility" to
                obj(
                    "minAppVersionCode" to integer(1),
                    "maxAppVersionCode" to integer(1),
                    "engineApi" to integer(1),
                    "minAndroidApi" to integer(30),
                    "abis" to array(string("arm64-v8a")),
                    "pageSizes" to array(integer(4096), integer(16384)),
                ),
            "runtime" to
                obj(
                    "format" to string("ORT"),
                    "onnxRuntime" to string("1.27.0"),
                    "onnxRuntimeExtensions" to string("0.15.0+fe4e13f"),
                    "executionProvider" to string("CPU"),
                    "targetPlatform" to string("arm"),
                    "operatorConfigPath" to string("operators/required-operators.config"),
                ),
            "components" to
                array(
                    component("siglip2-image", "models/siglip2_image.ort"),
                    component("siglip2-text", "models/siglip2_text.ort"),
                    component("siglip2-tokenizer", "models/siglip2_tokenizer.ort"),
                    component("user2-encoder", "models/user2_encoder.ort"),
                    component("user2-tokenizer", "models/user2_tokenizer.ort"),
                    component("ppocrv6-detector", "models/ppocrv6_detector.ort"),
                    component(
                        "eslav-recognizer",
                        "models/eslav_recognizer.ort",
                        "preprocessing/eslav-recognizer-decoder.json",
                    ),
                ),
            "files" to
                JsonValue.ArrayValue(
                    payloads.toSortedMap().map { (path, bytes) ->
                        obj(
                            "path" to string(path),
                            "role" to
                                string(
                                    when (path) {
                                        "operators/required-operators.config" -> "runtime-config"
                                        "preprocessing/eslav-recognizer-decoder.json" -> "decoder"
                                        "tests/manifest.json" -> "test-manifest"
                                        else -> "model"
                                    },
                                ),
                            "length" to integer(bytes.size.toLong()),
                            "sha256" to string(MessageDigest.getInstance("SHA-256").digest(bytes).toHex()),
                        )
                    },
                ),
            "provenance" to obj("containsUserData" to JsonValue.BooleanValue(false)),
        )

    private fun component(id: String, path: String, decoderPath: String? = null) =
        obj(
            *buildList {
                add("componentId" to string(id))
                add("artifactPath" to string(path))
                add("license" to string("Apache-2.0"))
                decoderPath?.let { add("decoderPath" to string(it)) }
            }.toTypedArray(),
        )

    private fun sign(keyPair: KeyPair, manifest: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(keyPair.private)
        signature.update("NAYTI_MODEL_PACK_SIGNATURE_V1\u0000".toByteArray(Charsets.US_ASCII))
        signature.update(manifest)
        return signature.sign()
    }

    private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*entries))
    private fun array(vararg values: JsonValue) = JsonValue.ArrayValue(values.toList())
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun integer(value: Long) = JsonValue.IntegerValue(value)

    private data class Fixture(
        val container: ByteArray,
        val payloads: Map<String, ByteArray>,
        val payloadBytes: Long,
        val trustedKeys: Map<String, TrustedModelPackKey>,
    )
}
