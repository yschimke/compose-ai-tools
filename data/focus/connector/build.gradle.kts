// `:data-focus-connector` glues `:data-focus-core` (the wire-shape) to the daemon's data-product /
// preview-override surface. Mirrors `:data-ambient-connector`'s split — model in the core module,
// daemon-side machinery (controller, around-composable extension, planner, overlay) here. Ships:
//
//  - `FocusController` — process-static state holder for the active `FocusOverride`, plus the
//    settle-window constant the renderer's per-capture clock advance reads.
//  - `FocusOverrideExtension` / `FocusPreviewOverrideExtension` — `AroundComposable` plumbing that
//    installs `LocalInputModeManager provides KeyboardInputModeManager` and drives
//    `FocusManager.moveFocus(...)` from a `LaunchedEffect`. Planner maps
//    `renderNow.overrides.focus` to the extension; the renderer-android plugin path additionally
//    invokes the around-composable directly when `@FocusedPreview` discovery emits per-capture
//    focus state.
//  - `FocusOverlay` — post-capture stroke + label overlay drawn over the focused element's bounds,
//    moved off `RobolectricRenderTest` so the renderer no longer carries hardcoded focus logic.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.focus.connector" }

dependencies {
  // Wire-shape — re-exported via `api` so consumers (`:daemon:android`, `:renderer-android`) can
  // refer to `FocusOverride` / `FocusDirection` without adding a second `project` dependency.
  api(project(":data-focus-core"))

  // DataProductRegistry / DataExtension / AroundComposableExtension. Re-exported so the
  // connector's planner / extension classes can be referenced from `RobolectricHost`'s
  // `previewOverrideExtensions` list without a second project dep on the consumer.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // Compose runtime / UI types (`@Composable`, `DisposableEffect`, `LocalInputModeManager`,
  // `LocalFocusManager`) flow in transitively through `:data-render-compose`'s
  // `api(libs.jetbrains.compose.runtime)` / `api(libs.jetbrains.compose.ui)` — no explicit dep
  // here, mirroring `:data-ambient-connector`. Inline `@Composable` callsites from
  // `:data-render-compose`-shipped APIs work at this layer; richer foundation widgets that need
  // `compose-foundation` (e.g. `Box`) belong in the renderer / consumer modules.

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

mavenPublishing {
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-focus-connector",
    displayName = "Compose Preview - Focus Data Product Connector",
    description =
      "Daemon-side focus / keyboard-traversal data-product connector: drives the Compose around-composable that lets `@FocusedPreview` and `renderNow.overrides.focus` walks render under a synthetic keyboard input mode.",
  )
  inceptionYear.set("2026")
}
