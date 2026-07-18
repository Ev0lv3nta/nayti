import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val reducedOrtMetadata =
    Properties().apply {
        rootProject.file("gradle/reduced-ort.properties").inputStream().use(::load)
    }
val reducedOrtAar = providers.environmentVariable("NAYTI_ORT_AAR").orNull
reducedOrtAar?.let { path ->
    val artifact = file(path)
    require(artifact.isFile) { "NAYTI_ORT_AAR does not point to a file: $path" }
    require(artifact.length() == reducedOrtMetadata.getProperty("bytes").toLong()) {
        "NAYTI_ORT_AAR has an unexpected size: $path"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    artifact.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    require(actualSha256 == reducedOrtMetadata.getProperty("sha256")) {
        "NAYTI_ORT_AAR failed the pinned SHA-256 check: $path"
    }
}

android {
    namespace = "app.nayti.ml.runtime"
    compileSdk { version = release(37) }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        externalNativeBuild {
            cmake.cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint { warningsAsErrors = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.tink)
    compileOnly(libs.onnxruntime.android)
    if (reducedOrtAar != null) {
        implementation(files(reducedOrtAar))
    }
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
