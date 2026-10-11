plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; see the matching block in `:samples:android` for the JDK 17
  // toolchain rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)

  // `LongScrollPreviewPixelTest` reads PNGs from
  // `build/compose-previews/renders/`; opt the unit-test tasks into a
  // `dependsOn(composePreviewRenderAll)` chain so `:samples:wear:check` renders
  // before asserting.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.samplewear"
  // wear-compose 1.7.0-beta (gesture API) requires `compileSdk = 37`; override the conventions
  // plugin's `compileSdk = 36` default. Robolectric still renders at SDK 35 (see `composePreview`).
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.samplewear"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  // Issue #2299 long-scroll regression fixture uses `Icons.Default.*` in list-item buttons.
  implementation("androidx.compose.material:material-icons-extended")
  implementation(libs.activity.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  // Wear navigation — `SwipeDismissableNavHost` drives the gesture-gallery flow in `Gestures.kt`.
  implementation(libs.wear.compose.navigation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.roborazzi.annotations)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Wear ambient-mode data extension: installs `LocalAmbientModeManager` from
  // `renderNow.overrides.ambient` in daemon renders; static renders fall back to `Interactive`.
  implementation(libs.composeai.data.ambient.connector)

  // Wear Tiles, for the tile `@Preview` sample. `wear.tiles.renderer` isn't declared: the plugin
  // injects it when `androidx.wear.tiles:tiles` is on the runtime classpath.
  implementation(libs.wear.tiles)
  implementation(libs.wear.tiles.tooling.preview)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.expression)
  implementation(libs.wear.protolayout.material3)
  implementation(libs.wear.tooling.preview)
  // `@ScrollingPreview` — read by FQN at discovery time; no runtime cost.
  implementation(libs.composeai.preview.annotations)

  // The opt-in override seam, used only by the preview-only `PlaceholderCardOverrideDriven`
  // wrapper; the reusable card takes `loading` explicitly.
  implementation(libs.composeai.data.preview.overrides.runtime)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
