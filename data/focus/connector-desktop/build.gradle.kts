@file:Suppress("DEPRECATION")

// `:data-focus-connector-desktop` is the JVM-side counterpart to `:data-focus-connector` (Android).
// Both planners read `renderNow.overrides.focus` and emit a `PreviewOverrideExtension` that wraps
// the
// content in an `AroundComposable` driving `LocalInputModeManager` / `FocusManager.moveFocus(...)`.
//
// **Why a separate source set?** `:data-focus-connector` is an `android.library` module, so its
// outputs can't be consumed from `:daemon:desktop`'s JVM classpath. The around-composable plumbing
// itself only depends on `androidx.compose.runtime` + `androidx.compose.ui` (`LocalFocusManager`,
// `LocalInputModeManager`, `FocusManager.moveFocus`) — fully Compose Multiplatform portable. The
// Android module additionally ships `FocusOverlay` (a `BufferedImage` overlay sourced from
// `AndroidComposeView.focusOwner` via reflection); that's Android-only and stays on the Android
// side.
//
// Mirrors `:data-pseudolocale-connector-desktop`'s layout — see the comment header there for the
// platform-split rationale.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  // Wire-shape (`FocusOverride` / `FocusDirection`) — re-exported so consumers (`:daemon:desktop`)
  // can refer to the model types without a second project dep.
  api(project(":data-focus-core"))

  // DataExtension / AroundComposableExtension / DataExtensionId / DataExtensionPhase. Re-exported
  // so
  // the planner / extension classes can be referenced from `DesktopHost`'s
  // `previewOverrideExtensions` list without a second project dep on the consumer.
  api(project(":daemon:core"))
  api(project(":data-render-compose"))

  implementation(compose.runtime)
  implementation(compose.ui)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-focus-connector-desktop",
    displayName = "Compose Preview - Focus Data Product Connector (Desktop)",
    description =
      "Daemon-side focus / keyboard-traversal data-product connector for Compose Multiplatform Desktop: drives the around-composable that lets `renderNow.overrides.focus` render under a synthetic keyboard input mode. Mirrors :data-focus-connector — see that module for the Android-only `FocusOverlay`.",
  )
  inceptionYear.set("2026")
}
