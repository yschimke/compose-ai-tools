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

  // `:data-ambient-connector` — the Wear OS ambient-mode data extension. The
  // connector's `AmbientOverrideExtension` (an `AroundComposableExtension` planned
  // by `AmbientPreviewOverrideExtension` from `renderNow.overrides.ambient`)
  // installs the `LocalAmbientModeManager` composition local that
  // `AmbientStatusBody` reads from. Static `@Preview` rendering doesn't run the
  // daemon-side extension chain, so previews fall back to `AmbientMode.Interactive`;
  // daemon-driven renders with `overrides.ambient` see `Ambient(...)` end-to-end.
  implementation(project(":data-ambient-connector"))

  // Wear Tiles — for the `@androidx.wear.tiles.tooling.preview.Preview` sample
  // rendered via TilePreviewRenderer in renderer-android. `wear.tiles.renderer`
  // is deliberately NOT declared here — the plugin injects it when the
  // consumer's variant runtime classpath already includes `androidx.wear.tiles:tiles`,
  // so consumer apps don't need to restate this preview-only dependency.
  implementation(libs.wear.tiles)
  implementation(libs.wear.tiles.tooling.preview)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.expression)
  implementation(libs.wear.protolayout.material3)
  implementation(libs.wear.tooling.preview)
  // `@ScrollingPreview` — read by FQN at discovery time; no runtime cost.
  implementation(project(":preview-annotations"))

  // `:data-preview-overrides-runtime` — the opt-in override seam. `PlaceholderPreviews.kt` reads
  // `placeholderActive()` from exactly one place: the preview-only `PlaceholderCardOverrideDriven`
  // wrapper, which forwards it into `PlaceholderCard`'s hoisted `loading` parameter so a daemon
  // render can drive the state from `renderNow.overrides.placeholderActive` (issue #2646). The
  // reusable card itself takes `loading` explicitly and carries no compose-ai-tools import — the
  // static loaded/loading previews just pass the boolean (issue #3675).
  implementation(project(":data-preview-overrides-runtime"))

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
