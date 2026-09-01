import org.gradle.api.initialization.resolve.RepositoriesMode

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

rootProject.name = "CodeMatchAndroid"

include(":app")
include(":core:model")
include(":core:matching")
include(":core:designsystem")

// The fake scanner is deliberately a debug-only dependency of :app. Keeping it
// as a separate module makes the release dependency graph auditable.
include(":scanner:fake")
