package app.nayti.ml.runtime.pack

import app.nayti.ml.runtime.semantic.User2EmbeddingSpaceIdentity
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class User2EmbeddingSpaceIdentityTest {
    @Test
    fun identityUsesOnlySelectedArtifactsAndExecutionContract() {
        val root = Files.createTempDirectory("nayti-user2-identity")
        try {
            writeManifest(root, encoderHash = "1".repeat(64), unrelatedHash = "3".repeat(64))
            val first = User2EmbeddingSpaceIdentity.calculate(root)
            writeManifest(root, encoderHash = "1".repeat(64), unrelatedHash = "4".repeat(64))
            val unrelatedChanged = User2EmbeddingSpaceIdentity.calculate(root)
            writeManifest(root, encoderHash = "5".repeat(64), unrelatedHash = "4".repeat(64))
            val encoderChanged = User2EmbeddingSpaceIdentity.calculate(root)

            assertEquals(first, unrelatedChanged)
            assertNotEquals(first, encoderChanged)
            assertEquals(64, first.length)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeManifest(root: java.nio.file.Path, encoderHash: String, unrelatedHash: String) {
        val manifest =
            obj(
                "schemaVersion" to integer(1),
                "packId" to string("nayti-test"),
                "packVersion" to string("0.1.0-alpha.1"),
                "keyId" to string("0".repeat(32)),
                "compatibility" to obj("engineApi" to integer(1)),
                "runtime" to obj("format" to string("ORT")),
                "components" to array(obj("componentId" to string("semantic"))),
                "files" to
                    array(
                        file("models/ocr.ort", unrelatedHash, 30),
                        file("models/user2_encoder.ort", encoderHash, 10),
                        file("models/user2_tokenizer.ort", "2".repeat(64), 20),
                    ),
                "provenance" to obj("containsUserData" to JsonValue.BooleanValue(false)),
            )
        Files.write(root.resolve("manifest.json"), CanonicalJson.encode(manifest))
    }

    private fun file(path: String, sha256: String, length: Long) =
        obj(
            "path" to string(path),
            "role" to string("model"),
            "length" to integer(length),
            "sha256" to string(sha256),
        )

    private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*entries))
    private fun array(vararg values: JsonValue) = JsonValue.ArrayValue(values.toList())
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun integer(value: Long) = JsonValue.IntegerValue(value)
}
