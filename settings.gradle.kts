pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nayti"

include(
    ":app",
    ":benchmark",
    ":indexer",
    ":ml-runtime",
    ":platform-media",
    ":search-engine",
    ":storage",
)

// Heavy stage-2 proof: only materialize this test module when the locally built
// reduced AAR is supplied. Normal builds and CI never resolve a fallback ORT.
if (providers.environmentVariable("NAYTI_ORT_AAR").isPresent) {
    include(":model-runtime-proof")
}
