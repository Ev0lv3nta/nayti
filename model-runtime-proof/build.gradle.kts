plugins {
    alias(libs.plugins.android.test)
}

val reducedOrtAar = providers.environmentVariable("NAYTI_ORT_AAR").orNull
    ?: error("NAYTI_ORT_AAR is required when :model-runtime-proof is included")
require(file(reducedOrtAar).isFile) {
    "NAYTI_ORT_AAR does not point to a file: $reducedOrtAar"
}

android {
    namespace = "app.nayti.model.runtime.proof"
    compileSdk { version = release(37) }
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters.add("arm64-v8a")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(files(reducedOrtAar))
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}
