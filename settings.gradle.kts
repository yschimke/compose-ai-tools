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

// Per-product data-product modules — each `data/<product>/` carries a `core` (generic Android /
// Compose / AndroidX-test code, published) and a `connector` (daemon glue, unpublished) module.
// See docs/daemon/DATA-PRODUCTS.md § "Module split (D2.2)".
//
// Project paths use flat names rather than `:data:a11y:core` because Gradle resolves project
// dependencies by `<group>:<projectName>` and `:data:a11y:core`'s leaf name "core" collides
// with `:daemon:core`'s — same group, same name, "by conflict resolution" substitutes one for
// the other. Flat names avoid the collision; the directory layout on disk stays nested under
// `data/<product>/`. Published Maven coordinates are set explicitly in each module's
// `mavenPublishing { coordinates(...) }` block.
include(":data-a11y-core")

project(":data-a11y-core").projectDir = file("data/a11y/core")

include(":data-a11y-connector")

project(":data-a11y-connector").projectDir = file("data/a11y/connector")

include(":data-daemon-connector")

project(":data-daemon-connector").projectDir = file("data/daemon/connector")

include(":data-daemon-core")

project(":data-daemon-core").projectDir = file("data/daemon/core")

include(":data-android-connector")

project(":data-android-connector").projectDir = file("data/android/connector")

include(":data-desktop-connector")

project(":data-desktop-connector").projectDir = file("data/desktop/connector")

include(":daemon:android")

include(":daemon:desktop")

include(":daemon:harness")

include(":mcp")
