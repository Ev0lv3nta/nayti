plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.nayti.platform.media"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 30 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { warningsAsErrors = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
