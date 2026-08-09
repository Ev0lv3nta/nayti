plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseKeystorePath = providers.environmentVariable("NAYTI_RELEASE_KEYSTORE").orNull
val releaseKeyAlias = providers.environmentVariable("NAYTI_RELEASE_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("NAYTI_RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("NAYTI_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues =
    listOf(
        releaseKeystorePath,
        releaseKeyAlias,
        releaseStorePassword,
        releaseKeyPassword,
    )
val releaseSigningConfigured = releaseSigningValues.all { value -> !value.isNullOrBlank() }

if (releaseSigningValues.any { value -> !value.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing requires all NAYTI_RELEASE_* environment variables.")
}

android {
    namespace = "app.nayti"
    compileSdk { version = release(37) }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.nayti"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-alpha.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("publicAlpha") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk.abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.abiFilters.add("arm64-v8a")
            signingConfig = signingConfigs.findByName("publicAlpha")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        register("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            versionNameSuffix = "-alpha-local"
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    sourceSets.getByName("main").assets.directories.add(rootProject.file("third_party").path)

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable")
        warningsAsErrors = true
    }
}

hilt {
    enableAggregatingTask = true
}

dependencies {
    implementation(project(":indexer"))
    implementation(project(":ml-runtime"))
    implementation(project(":platform-media"))
    implementation(project(":search-engine"))
    implementation(project(":storage"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
