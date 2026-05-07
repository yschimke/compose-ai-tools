pluginManagement {
  includeBuild("build-logic")
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

include(":samples:android-alpha")

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

include(":data-a11y-hierarchy-android")

project(":data-a11y-hierarchy-android").projectDir = file("data/a11y/hierarchy-android")

include(":data-a11y-connector")

project(":data-a11y-connector").projectDir = file("data/a11y/connector")

include(":data-fonts-core")

project(":data-fonts-core").projectDir = file("data/fonts/core")

include(":data-fonts-connector")

project(":data-fonts-connector").projectDir = file("data/fonts/connector")

include(":data-render-core")

project(":data-render-core").projectDir = file("data/render/core")

include(":data-render-compose")

project(":data-render-compose").projectDir = file("data/render/compose")

include(":data-render-connector")

project(":data-render-connector").projectDir = file("data/render/connector")

include(":data-scroll-core")

project(":data-scroll-core").projectDir = file("data/scroll/core")

include(":data-history-core")

project(":data-history-core").projectDir = file("data/history/core")

include(":data-history-connector")

project(":data-history-connector").projectDir = file("data/history/connector")

include(":data-layoutinspector-connector")

project(":data-layoutinspector-connector").projectDir = file("data/layoutinspector/connector")

include(":data-layoutinspector-core")

project(":data-layoutinspector-core").projectDir = file("data/layoutinspector/core")

include(":data-resources-connector")

project(":data-resources-connector").projectDir = file("data/resources/connector")

include(":data-resources-core")

project(":data-resources-core").projectDir = file("data/resources/core")

include(":data-strings-connector")

project(":data-strings-connector").projectDir = file("data/strings/connector")

include(":data-strings-core")

project(":data-strings-core").projectDir = file("data/strings/core")

include(":data-theme-core")

project(":data-theme-core").projectDir = file("data/theme/core")

include(":data-theme-connector")

project(":data-theme-connector").projectDir = file("data/theme/connector")

include(":data-wallpaper-core")

project(":data-wallpaper-core").projectDir = file("data/wallpaper/core")

include(":data-wallpaper-connector")

project(":data-wallpaper-connector").projectDir = file("data/wallpaper/connector")

include(":data-ambient-core")

project(":data-ambient-core").projectDir = file("data/ambient/core")

include(":data-ambient-connector")

project(":data-ambient-connector").projectDir = file("data/ambient/connector")

include(":data-recomposition-core")

project(":data-recomposition-core").projectDir = file("data/recomposition/core")

include(":data-recomposition-connector")

project(":data-recomposition-connector").projectDir = file("data/recomposition/connector")

// UIAutomator-shaped query/action API for the Compose preview renderer. Carries the matcher,
// the Selector DSL, and the JSON wire format — consumed by `:daemon:android` for
// `record_preview`'s `uia.*` script events.
include(":data-uiautomator-core")

project(":data-uiautomator-core").projectDir = file("data/uiautomator/core")

include(":data-uiautomator-connector")

project(":data-uiautomator-connector").projectDir = file("data/uiautomator/connector")

include(":data-uiautomator-hierarchy-android")

project(":data-uiautomator-hierarchy-android").projectDir =
  file("data/uiautomator/hierarchy-android")

include(":daemon:android")

include(":daemon:desktop")

include(":daemon:harness")

include(":mcp")
