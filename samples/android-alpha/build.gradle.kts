plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Pinned to the prerelease compose-material3 line. Isolated from
// `:samples:android` (which sticks to `compose-bom-stable`) so the
// alpha-channel APIs and the transitive Compose 1.12.x bump they pull in
// don't leak into the stable sample's classpath. Mirrors the pattern of
// `:samples:remotecompose` riding alpha compose for a feature the BOM
// doesn't have yet — see [docs/RENDERER_COMPATIBILITY.md] for the version
// alignment story.

android {
  namespace = "com.example.samplealpha"
  // compose-ui 1.12.0-alpha02 (transitively pulled by material3 1.5.0-alphaNN)
  // raises minCompileSdk to 37, so this module diverges from the rest of the
  // repo (still on 36).
  compileSdk = 37

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  // Pinning material3 directly: the inset-focus-ring APIs
  // (RippleThemeConfiguration, LocalRippleThemeConfiguration,
  // RippleDefaults.{Inset,Opacity}Focus*RippleThemeConfiguration) graduated
  // to stable in 1.5.0-alpha18; alpha19 is the latest at time of writing.
  // material3 1.5.0-alphaNN's metadata pulls compose-foundation/runtime/ui
  // 1.12.0-alphaNN transitively, so no separate compose-bom application is
  // needed (and would only fight conflict resolution).
  implementation("androidx.compose.material3:material3:1.5.0-alpha19")
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  // `@AnimatedPreview` and `@FocusedPreview` live here — source-retained
  // metadata read by `DiscoverPreviewsTask` at FQN.
  implementation(project(":preview-annotations"))
  debugImplementation("androidx.compose.ui:ui-tooling")
}
