@file:Suppress("DEPRECATION")

// `:data-keyboard-connector-desktop` is the JVM-side counterpart to `:data-keyboard-connector`
// (Android). Both planners always return a `KeyboardOverrideExtension` so the around-composable's
// observer is in place for every render; both forward `interactive/input` `KEY_*` dispatches and
// `renderNow.overrides.keyboard` into the same process-static `KeyboardController` (one instance
// per platform — JVM classloader vs Android sandbox classloader).
//
// **Why a separate source set?** `:data-keyboard-connector` is an `android.library` module, so its
// outputs can't be consumed from `:daemon:desktop`'s JVM classpath. The around-composable plumbing
// itself only depends on `androidx.compose.runtime` + `androidx.compose.ui` + foundation widgets,
// all available on Compose Multiplatform Desktop. Mirrors `:data-focus-connector-desktop`'s layout
// — see the comment header there for the platform-split rationale.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":data-keyboard-core"))
  api(project(":daemon:core"))
  api(project(":data-render-compose"))

  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-keyboard-connector-desktop",
    displayName = "Compose Preview - Soft Keyboard Data Product Connector (Desktop)",
    description =
      "Daemon-side soft-keyboard data-product connector for Compose Multiplatform Desktop: shadows LocalSoftwareKeyboardController so app-side IME show/hide and `renderNow.overrides.keyboard` / `interactive/input` `KEY_*` dispatches drive the same fake-IME overlay. Mirrors :data-keyboard-connector.",
  )
  inceptionYear.set("2026")
}
