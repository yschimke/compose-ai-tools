pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Snapshot probe for the SDK compatibility matrix's snapshot cells. Pulls Robolectric
// snapshots (which carry API 37 fixes ahead of the next stable release) from the new Sonatype
// Central Maven snapshots endpoint so `:samples:sdk-matrix` can render at SDK 37. The legacy
// `oss.sonatype.org` host is still reachable but stopped accepting new snapshots during
// Sonatype's 2024–2025 migration to `central.sonatype.com`. Both endpoints are added so a
// future-published snapshot that lands on the old host still resolves; in practice the new one
// wins. Scoped to `org.robolectric` so a stray snapshot artifact in some other group can't leak
// in. Repos are added only when the property is set; default builds aren't slowed by extra
// snapshot lookups.
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
    if (matrixRobolectricVersion?.endsWith("-SNAPSHOT") == true) {
      maven("https://central.sonatype.com/repository/maven-snapshots/") {
        name = "robolectric-snapshots-central"
        content { includeGroup("org.robolectric") }
      }
      maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "robolectric-snapshots-oss"
        content { includeGroup("org.robolectric") }
      }
    }
  }
}

rootProject.name = "compose-ai-tools"

includeBuild("gradle-plugin")

include(":cli")

// Published wire-format DTOs (`PreviewResult`, `PreviewManifest`, the v1 a11y mirror types, …).
// Lives outside `:cli` so external consumers (contrib scripting, future MCP integrations,
// third-party tooling) can pull just the data shapes without dragging in `:cli`'s Gradle Tooling
// API + scripting closure. Step A of the clean-API carve-out — issue #1084 / docs/AGENTS.md
// "Built-in scripts" / clean-API discussion.
include(":preview-data-api")

project(":preview-data-api").projectDir = file("preview-data-api")

// Step B of the clean-API carve-out: the Gradle Tooling-API render pipeline that previously
// lived inside `:cli`'s `Command` base class. Exposes a `GradlePreviewDriver` library so
// external consumers (contrib scripting, third-party tooling) can render previews and read the
// result without taking a dependency on `:cli`. The CLI's own commands are refactored to drive
// this library, keeping a single source of truth.
include(":gradle-preview-driver")

project(":gradle-preview-driver").projectDir = file("gradle-preview-driver")

// `:cli-scripting` (the Kotlin-scripting host for `compose-preview script <path>`) was removed
// in the step C carve-out — see issue #1084 / the clean-API discussion. Scripting now lives in
// `yschimke/compose-ai-contrib` as a standalone consumer of `:preview-data-api` +
// `:gradle-preview-driver` + `:data-a11y-core`. Its absence from this repo is the proof the
// published API is expressive enough to build features against, not just inside.

// Reference implementation of contrib's `compose-preview-scripting` binary. Lives here as a
// validated, build-tested template that consumes only the published surface (`:preview-data-api`
// + `:gradle-preview-driver` + `:data-a11y-core`). The `yschimke/compose-ai-contrib` repo lifts
// this code wholesale into its own published module; the copy here gets deleted once contrib's
// copy job has run, at which point the carve-out is fully closed.
include(":examples-scripting")

project(":examples-scripting").projectDir = file("examples/scripting")

include(":bundle-viewer")

include(":preview-annotations")

include(":notification-preview-runtime")

include(":samples:android")

include(":samples:android-alpha")

include(":samples:android-library")

include(":samples:android-screenshot-test")

include(":samples:android-daemon-bench")

include(":samples:sdk-matrix")

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

include(":data-navigation-core")

project(":data-navigation-core").projectDir = file("data/navigation/core")

include(":data-navigation-connector")

project(":data-navigation-connector").projectDir = file("data/navigation/connector")

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

include(":data-focus-core")

project(":data-focus-core").projectDir = file("data/focus/core")

include(":data-focus-connector")

project(":data-focus-connector").projectDir = file("data/focus/connector")

include(":data-focus-connector-desktop")

project(":data-focus-connector-desktop").projectDir = file("data/focus/connector-desktop")

include(":data-keyboard-core")

project(":data-keyboard-core").projectDir = file("data/keyboard/core")

include(":data-keyboard-connector")

project(":data-keyboard-connector").projectDir = file("data/keyboard/connector")

include(":data-keyboard-connector-desktop")

project(":data-keyboard-connector-desktop").projectDir = file("data/keyboard/connector-desktop")

include(":data-touch-overlay-connector")

project(":data-touch-overlay-connector").projectDir = file("data/touch-overlay/connector")

include(":data-pseudolocale-core")

project(":data-pseudolocale-core").projectDir = file("data/pseudolocale/core")

include(":data-pseudolocale-connector")

project(":data-pseudolocale-connector").projectDir = file("data/pseudolocale/connector")

include(":data-pseudolocale-connector-desktop")

project(":data-pseudolocale-connector-desktop").projectDir =
  file("data/pseudolocale/connector-desktop")

include(":data-recomposition-core")

project(":data-recomposition-core").projectDir = file("data/recomposition/core")

include(":data-recomposition-connector")

project(":data-recomposition-connector").projectDir = file("data/recomposition/connector")

include(":data-displayfilter-core")

project(":data-displayfilter-core").projectDir = file("data/displayfilter/core")

include(":data-displayfilter-connector")

project(":data-displayfilter-connector").projectDir = file("data/displayfilter/connector")

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

// Public render-session library. `:render-session-api` is the pure-interface surface every
// consumer (CLI, MCP server, third-party tooling) compiles against; `:render-session-subprocess`
// is the daemon-subprocess-backed implementation. Future `:render-session-embedded` will host the
// in-process Robolectric driver once that's viable.
include(":render-session-api")

project(":render-session-api").projectDir = file("render-session/api")

include(":render-session-subprocess")

project(":render-session-subprocess").projectDir = file("render-session/subprocess")

// In-process Compose Multiplatform Desktop backend for the render-session library. Hosts the
// daemon's `JsonRpcServer` + `DesktopHost` in the calling JVM via piped streams instead of forking
// a subprocess. Trades classpath footprint (the calling JVM picks up Skiko + Compose Desktop) for
// dramatically faster session startup. Embedders that don't want the runtime footprint stick with
// `:render-session-subprocess`.
include(":render-session-embedded-desktop")

project(":render-session-embedded-desktop").projectDir = file("render-session/embedded-desktop")

// Thin `java -jar` CLI over `:render-session-subprocess` for non-Gradle build systems
// (Bazel rules, Amper tasks in `yschimke/compose-ai-contrib`). See `contrib/README.md`.
include(":render-cli")
