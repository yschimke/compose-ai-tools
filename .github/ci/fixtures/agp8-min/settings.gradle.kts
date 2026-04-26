// Minimal AGP 8.13 / Gradle 8.13 / Kotlin 2.0.21 fixture used by the
// `agp8-min` integration matrix entry. The job verifies that
// `ee.schimke.composeai.preview` still applies and discovers previews
// against an AGP-8-line consumer — the floor we publicly support.
//
// Versions are pinned literally (no version catalog) so the fixture is
// self-contained and Renovate can bump them without touching anything
// else. Don't add more modules or features here — every keystroke is
// budget against the integration job's runtime.

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
  }
}

rootProject.name = "agp8-min"

include(":app")
