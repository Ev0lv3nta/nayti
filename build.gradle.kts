import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

val verifyArchitecture = tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Checks that project dependencies follow the approved module graph."

    inputs.property("violations", "")

    doLast {
        val violations = inputs.properties.getValue("violations") as String
        check(violations.isBlank()) {
            "Module graph violations:\n$violations"
        }
    }
}

gradle.projectsEvaluated {
    val allowed = mapOf(
        ":app" to setOf(":indexer", ":platform-media", ":search-engine"),
        ":benchmark" to emptySet(),
        ":indexer" to setOf(":ml-runtime", ":platform-media", ":search-engine", ":storage"),
        ":ml-runtime" to emptySet(),
        ":platform-media" to emptySet(),
        ":search-engine" to setOf(":ml-runtime", ":storage"),
        ":storage" to emptySet(),
    )

    val violations = subprojects.flatMap { project ->
        val actual = project.configurations
            .flatMap { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java)
            }
            // Gradle 9's ProjectDependency.path identifies the consuming project.
            // Module names are unique and all approved modules live at the root.
            .map { dependency -> ":${dependency.name}" }
            .toSet()

        val expected = allowed.getValue(project.path)
        (actual - expected).map { dependency ->
            "${project.path} must not depend on $dependency"
        }
    }

    verifyArchitecture.configure {
        inputs.property("violations", violations.joinToString("\n"))
    }
}
