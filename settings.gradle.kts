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

include(":samples:android")

include(":samples:android-library")

include(":samples:android-screenshot-test")

include(":samples:android-daemon-bench")

include(":samples:wear")

include(":samples:cmp")

include(":samples:cmp-shared")

include(":samples:desktop-daemon-bench")

include(":samples:remotecompose")

include(":renderer-desktop")

include(":renderer-android")

include(":daemon:core")

include(":daemon:android")

include(":daemon:desktop")

include(":daemon:harness")

include(":mcp")
