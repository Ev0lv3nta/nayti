plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.nayti.search.engine"
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
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
