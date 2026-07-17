package app.nayti.ml.runtime.pack

data class ModelPackPolicy(
    val appVersionCode: Long,
    val engineApi: Long,
    val androidApi: Long,
    val supportedAbis: Set<String>,
    val pageSize: Long,
    val expectedPackId: String = "nayti-offline-search",
    val expectedRuntimeVersion: String = "1.27.0",
    val expectedExtensionsVersion: String = "0.15.0+fe4e13f",
) {
    internal fun validate(manifest: ModelPackManifest) {
        if (manifest.packId != expectedPackId) throw ModelPackException("Unexpected model pack ID")
        val compatibility = manifest.compatibility
        val minApp = compatibility.integer("minAppVersionCode")
        val maxApp = compatibility.integer("maxAppVersionCode")
        if (appVersionCode !in minApp..maxApp) throw ModelPackException("Model pack is incompatible with this app")
        if (compatibility.integer("engineApi") != engineApi) throw ModelPackException("Model pack engine API mismatch")
        if (androidApi < compatibility.integer("minAndroidApi")) throw ModelPackException("Android API is too old")
        val abis = compatibility.stringSet("abis")
        if (supportedAbis.intersect(abis).isEmpty()) throw ModelPackException("Model pack ABI mismatch")
        val pageSizes = compatibility.integerSet("pageSizes")
        if (pageSize !in pageSizes) throw ModelPackException("Model pack page-size mismatch")

        val runtime = manifest.runtime
        if (runtime.string("format") != "ORT" || runtime.string("executionProvider") != "CPU") {
            throw ModelPackException("Unsupported model runtime")
        }
        if (runtime.string("onnxRuntime") != expectedRuntimeVersion) {
            throw ModelPackException("ONNX Runtime version mismatch")
        }
        if (runtime.string("onnxRuntimeExtensions") != expectedExtensionsVersion) {
            throw ModelPackException("ONNX Runtime Extensions version mismatch")
        }
        if (runtime.string("targetPlatform") != "arm") throw ModelPackException("Unexpected model target platform")
        if (runtime.string("operatorConfigPath") != RequiredOperatorConfig) {
            throw ModelPackException("Unexpected operator config path")
        }

        val filesByPath = manifest.files.associateBy(ModelPackFile::path)
        if (filesByPath[RequiredOperatorConfig]?.role != "runtime-config") {
            throw ModelPackException("Missing runtime operator allowlist")
        }
        if (filesByPath[RequiredKatManifest]?.role != "test-manifest") {
            throw ModelPackException("Missing runtime known-answer manifest")
        }
        val allowedRoles =
            setOf(
                "contract",
                "license",
                "model",
                "notice",
                "preprocess-config",
                "provenance",
                "runtime-config",
                "sbom",
                "test-input",
                "test-manifest",
                "test-output",
            )
        if (manifest.files.any { it.role !in allowedRoles }) throw ModelPackException("Unknown payload role")

        val components = manifest.components.values.map { it.requireObject("component") }
        if (components.size != RequiredArtifacts.size) throw ModelPackException("Unexpected component count")
        val actualArtifacts =
            components.associate { component ->
                val componentId = component.string("componentId")
                if (component.string("license") != "Apache-2.0") {
                    throw ModelPackException("Unapproved model license")
                }
                componentId to component.string("artifactPath")
            }
        if (actualArtifacts != RequiredArtifacts) throw ModelPackException("Model component contract mismatch")
        if (actualArtifacts.values.any { filesByPath[it]?.role != "model" }) {
            throw ModelPackException("Component artifact is not a declared model")
        }
        val containsUserData = manifest.provenance.entries["containsUserData"] as? JsonValue.BooleanValue
        if (containsUserData?.value != false) throw ModelPackException("Pack provenance does not exclude user data")
    }

    private companion object {
        const val RequiredOperatorConfig = "operators/required-operators.config"
        const val RequiredKatManifest = "tests/manifest.json"
        val RequiredArtifacts =
            mapOf(
                "siglip2-image" to "models/siglip2_image.ort",
                "siglip2-text" to "models/siglip2_text.ort",
                "siglip2-tokenizer" to "models/siglip2_tokenizer.ort",
                "user2-encoder" to "models/user2_encoder.ort",
                "user2-tokenizer" to "models/user2_tokenizer.ort",
                "ppocrv6-detector" to "models/ppocrv6_detector.ort",
                "eslav-recognizer" to "models/eslav_recognizer.ort",
            )
    }
}

internal fun JsonValue.requireObject(description: String): JsonValue.ObjectValue =
    this as? JsonValue.ObjectValue ?: throw ModelPackException("$description must be an object")

internal fun JsonValue.ObjectValue.string(name: String): String =
    (entries[name] as? JsonValue.StringValue)?.value ?: throw ModelPackException("$name must be a string")

internal fun JsonValue.ObjectValue.integer(name: String): Long =
    (entries[name] as? JsonValue.IntegerValue)?.value ?: throw ModelPackException("$name must be an integer")

private fun JsonValue.ObjectValue.stringSet(name: String): Set<String> {
    val values = (entries[name] as? JsonValue.ArrayValue)?.values ?: throw ModelPackException("$name must be an array")
    return values.map { (it as? JsonValue.StringValue)?.value ?: throw ModelPackException("$name must contain strings") }.toSet()
}

private fun JsonValue.ObjectValue.integerSet(name: String): Set<Long> {
    val values = (entries[name] as? JsonValue.ArrayValue)?.values ?: throw ModelPackException("$name must be an array")
    return values.map { (it as? JsonValue.IntegerValue)?.value ?: throw ModelPackException("$name must contain integers") }.toSet()
}
