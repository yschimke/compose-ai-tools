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
  // wear-compose 1.7.0-alpha (gesture API) requires `compileSdk = 37`; override the conventions
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
  // `androidx.compose.animation.graphics` — renders wear-compose-material3's shipped gesture
  // indicator AVDs (`wear_one_handed_gesture_*_indicator_animation`) via the official
  // `AnimatedImageVector.animatedVectorResource` API. wear-compose-material3 depends on it at
  // `runtime` scope only, so declare it here to compile against `AnimatedImageVector`.
  implementation("androidx.compose.animation:animation-graphics")
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // `:data-gestures-connector` — the Wear OS one-handed-gesture data extension. `Gestures.kt`
  // wires its screens with the connector's `reportedOneHandedGesture` / `GestureHint` seam so the
  // handlers show up in `compose/gestures` and are drivable via `renderNow.overrides.gestures`.
  // Static `@Preview` rendering doesn't run the daemon extension chain, so previews pass
  // `forceHint = true` (or `rememberForcedGestureHintSource`) to render the hint affordance; the
  // daemon path force-shows it from `overrides.gestures.showHints`.
  implementation(project(":data-gestures-connector"))

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
