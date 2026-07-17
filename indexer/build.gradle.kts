plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.nayti.indexer"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 30 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { warningsAsErrors = true }
}

dependencies {
    implementation(project(":ml-runtime"))
    implementation(project(":platform-media"))
    implementation(project(":search-engine"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
