plugins {
    alias(libs.plugins.android.library)
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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
