pluginManagement {
  includeBuild("../build-logic")
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
  }
}

rootProject.name = "gradle-plugin"

include(":preview-discovery")

include(":daemon-launch-builder")

include(":gradle-plugin-config")
