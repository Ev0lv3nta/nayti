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
