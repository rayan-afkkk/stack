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

rootProject.name = "SwipeGallery"

// Pure-Kotlin business rules (quota, review engine, swipe thresholds, entitlement,
// trash reconciliation). It is a standalone build so it can be compiled and tested
// on any JVM without the Android SDK:  ./gradlew -p domain test
includeBuild("domain")

include(":app")
