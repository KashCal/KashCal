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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KashCal"
include(":app")

// Include icaldav library via composite build when available locally
// Set USE_MAVEN_CENTRAL=true to test Maven Central dependency instead of local build
val useMavenCentral = System.getenv("USE_MAVEN_CENTRAL")?.toBoolean() ?: false
if (!useMavenCentral && file("../icaldav").exists()) {
    includeBuild("../icaldav") {
        dependencySubstitution {
            substitute(module("org.onekash:icaldav-core")).using(project(":icaldav-core"))
        }
    }
}
