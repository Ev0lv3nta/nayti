package app.nayti.ml.runtime.visual

import app.nayti.ml.runtime.pack.CanonicalJson
import app.nayti.ml.runtime.pack.JsonValue
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Siglip2EmbeddingSpaceIdentityTest {
    @Test
    fun identityBindsBothTowersTokenizerAndPreprocessingButNotUnrelatedModels() {
        val root = Files.createTempDirectory("nayti-siglip2-identity")
        try {
            writeManifest(root, imageHash = "1".repeat(64), textHash = "2".repeat(64), unrelatedHash = "4".repeat(64))
            val first = Siglip2EmbeddingSpaceIdentity.calculate(root)
            writeManifest(root, imageHash = "1".repeat(64), textHash = "2".repeat(64), unrelatedHash = "5".repeat(64))
            val unrelatedChanged = Siglip2EmbeddingSpaceIdentity.calculate(root)
            writeManifest(root, imageHash = "1".repeat(64), textHash = "6".repeat(64), unrelatedHash = "5".repeat(64))
            val textChanged = Siglip2EmbeddingSpaceIdentity.calculate(root)

            assertEquals(first, unrelatedChanged)
            assertNotEquals(first, textChanged)
            assertEquals(64, first.length)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeManifest(
        root: java.nio.file.Path,
        imageHash: String,
        textHash: String,
        unrelatedHash: String,
    ) {
        val manifest =
            obj(
                "schemaVersion" to integer(1),
                "packId" to string("nayti-test"),
                "packVersion" to string("0.1.0-alpha.2"),
                "keyId" to string("0".repeat(32)),
                "compatibility" to obj("engineApi" to integer(1)),
                "runtime" to obj("format" to string("ORT")),
                "components" to array(obj("componentId" to string("visual"))),
                "files" to
                    array(
                        file("models/ocr.ort", unrelatedHash, 40),
                        file("models/siglip2_image.ort", imageHash, 10),
                        file("models/siglip2_text.ort", textHash, 20),
                        file("models/siglip2_tokenizer.ort", "3".repeat(64), 30),
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
