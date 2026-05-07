plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  previewExtensions {
    a11y {
      // Sample wires a deliberately-broken `BadWearButtonPreview` so the
      // `.a11y.png` for Wear exercises the stacked (legend-below) layout.
      // Uncomment `enableAllChecks()` and re-run to see the annotation;
      // defaults off so `./gradlew check` stays clean.
      // enableAllChecks()
    }
  }

  // `LongScrollPreviewPixelTest` reads PNGs from
  // `build/compose-previews/renders/`; opt the unit-test tasks into a
  // `dependsOn(renderAllPreviews)` chain so `:samples:wear:check` renders
  // before asserting.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.samplewear"

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
  implementation(libs.activity.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  implementation(libs.compose.ui.tooling.preview)
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

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
