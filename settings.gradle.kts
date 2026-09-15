// -----------------------------------------------------------------------------
// settings.gradle.kts
//
// Declares the plugin/dependency repositories and the module graph. Keeping the
// repositories here (rather than in each module) means `./gradlew` works from a
// clean checkout with no extra flags - important for the Termux and cloud-IDE
// workflows described in README.md.
// -----------------------------------------------------------------------------

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Fail loudly if a module tries to declare its own repositories, so the
    // build stays reproducible.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KeyProxy"

include(":app")
