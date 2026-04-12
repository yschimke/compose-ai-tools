pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo.gradle.org/gradle/libs-releases")
    }
}

rootProject.name = "compose-ai-tools"

includeBuild("gradle-plugin")
include(":cli")
include(":sample-android")
include(":sample-cmp")
include(":renderer-desktop")
include(":renderer-android")
