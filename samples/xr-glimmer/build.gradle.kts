plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// `:samples:xr-glimmer` — Jetpack Compose Glimmer previews for Android XR display AI glasses.
// Glimmer is its own Compose UI toolkit (not Material 3). Preview names follow a per-environment
// convention (`Glimmer · Light` …) ready for the future `@GlimmerPreview*` meta-annotations.

composePreview {
  // Robolectric SDK 35: Glimmer requires compileSdk 37, but Robolectric 4.16.1 shadows stop at 36
  // and need JDK 21 there, while this repo is on JDK 17. The exercised composables use no API 36+
  // symbol; bump with the JDK toolchain if that changes.
  sdkVersion.set(35)

  // `GlimmerCaptureAdditivePixelTest` reads rendered PNGs, so render before unit tests.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.samplexrglimmer"
  // Glimmer's metadata declares `minCompileSdk = 37`, which needs AGP 9.2.x (as
  // `:samples:android-alpha` and `:samples:remotecompose`).
  compileSdk = 37

  buildFeatures { compose = true }
}

dependencies {
  // `compose-bom-stable` aligns with Glimmer's POM. Glimmer's alpha line can pull Compose forward,
  // so check the resolved version rather than assuming:
  //   ./gradlew :samples:xr-glimmer:dependencies --configuration debugRuntimeClasspath
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Glimmer itself. Pulls Compose foundation / ui transitively per its own POM — again, see the
  // resolved graph rather than a version pinned in prose here.
  implementation(libs.xr.glimmer)

  // `@FocusedPreview`, read by FQN at discovery; drives focus through `GlimmerXrMenuNavigation`.
  implementation(libs.composeai.preview.annotations)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  // Contrast calibration reads the connector-owned environment resources; application code has no
  // dependency on the connector and therefore cannot accidentally ship preview scenery.
  testImplementation(libs.composeai.data.glimmer.environment.connector)
}
