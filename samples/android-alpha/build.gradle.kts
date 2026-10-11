plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Pinned to the prerelease compose-material3 line, isolated from `:samples:android`'s stable BOM so
// the alpha APIs and transitive Compose bump don't leak. See [docs/RENDERER_COMPATIBILITY.md].

composePreview {
  // Pin Robolectric to SDK 35; this module compiles against `compileSdk = 37` but Robolectric
  // 4.16.1 only ships up to API 36 (and needs JDK 21+ for that). See the matching block in
  // `:samples:android` for the broader JDK 17 toolchain rationale.
  sdkVersion.set(35)

  // `FocusedPreviewPixelTest` reads rendered PNGs, so render before unit tests.
  renderBeforeUnitTests.set(true)
}

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
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

dependencies {
  // material3 directly for the inset-focus-ring APIs (stable since 1.5.0-alpha18); its metadata
  // pulls the matching Compose alpha, so no separate BOM.
  implementation("androidx.compose.material3:material3:1.5.0-alpha29")
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  // material3's activity 1.11+ pulls androidx.navigationevent, whose R classes are only merged if
  // the main variant also resolves activity >= 1.11; otherwise renders fail with
  // `NoClassDefFoundError: androidx/navigationevent/R$id` (what `compose-preview doctor` reports).
  implementation(libs.activity.compose)
  // `@AnimatedPreview` and `@FocusedPreview` live here — source-retained
  // metadata read by `DiscoverPreviewsTask` at FQN.
  implementation(libs.composeai.preview.annotations)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
