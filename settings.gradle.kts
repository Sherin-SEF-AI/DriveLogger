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

rootProject.name = "BlurabbitDriveLogger"

include(":app")
include(":core:common")
include(":core:clock")
include(":core:proto")
include(":core:mcap")
include(":domain")
include(":data")
include(":sensors")
include(":recording")
include(":events")
include(":upload")
