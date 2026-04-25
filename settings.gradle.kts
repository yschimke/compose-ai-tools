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

include(":preview-annotations")

include(":sample-android")

include(":sample-android-library")

include(":sample-android-screenshot-test")

include(":sample-wear")

include(":sample-cmp")

include(":sample-remotecompose")

include(":renderer-desktop")

include(":renderer-android")
