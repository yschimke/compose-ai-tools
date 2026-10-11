// `:wear-preview-runtime` — composable helpers used inside a regular `@Preview` (no renderer
// strategy).
//
// `TlcScalingHost { spec -> … }` hosts a real single-item `TransformingLazyColumn` and hands the
// caller the genuine `TransformingLazyColumnItemScope` + `TransformationSpec`, so the body is
// exactly a live item's code with real Wear scaling. Pair with `@Preview`, `@ScrollingPreview(GIF,
// reduceMotion = false)`, or `ProvideTlcScalePosition`.
//
// `CapturingWearWidgetPreview` renders a Glance Wear widget and also emits its RemoteCompose
// document as the `.rc` sidecar (upstream's `WearWidgetPreview` keeps the bytes to itself).
//
// Wear/Glance/Remote Compose deps are `compileOnly` so consumers' own alpha versions aren't pinned.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  // Like every other published android-library runtime (`:splash`, `:notification`, …): the
  // maven-publishing convention only runs `configureKotlinCompatibility(...)` when tapmoc is
  // present, so without this the AAR ships without the documented `kotlinCoreLibraries` floor.
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.wear.preview"
  // wear-compose 1.7.0-beta requires `compileSdk = 37` (mirrors :samples:design-catalog-wear-m3).
  compileSdk = 37

  buildFeatures { compose = true }

  // `CapturingWearWidgetPreview` uses `@RestrictTo(LIBRARY_GROUP)` APIs; AGP lint's `RestrictedApi`
  // runs separately from the file-level suppression.
  lint { disable += "RestrictedApi" }

  // Robolectric + a real Compose/Remote Compose graph (`CapturingWearWidgetPreviewTest`).
  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  compileOnly(platform(libs.rcplayers.bom))
  compileOnly(platform(libs.compose.bom.stable))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.wear.compose.material3)
  compileOnly(libs.wear.compose.foundation)

  // The render-harness hand-off for the captured `.rc` bytes, called at render time.
  implementation(libs.composeai.data.render.core)
  // `runBlocking` for the one-shot capture. A real dependency, not `compileOnly`: consumers' alpha
  // deps may resolve coroutines 1.9.0, where the 1.11 call site's mangled `runBlocking` doesn't
  // exist, and the capture would silently lose its `.rc`.
  implementation(libs.kotlinx.coroutines.core)

  // Glance Wear + Remote Compose creation API, `compileOnly` like `wear-compose`.
  compileOnly(libs.glance.wear)
  compileOnly(libs.glance.wear.core)
  compileOnly(libs.glance.wear.tooling.preview)
  compileOnly(libs.compose.remote.creation.compose)

  // Players for replaying the captured document; the embedded Compose player is the default (see
  // `WearWidgetPreviewPlayer`). `compileOnly`: `embeddedWearWidgetPlayerAvailable` falls back to
  // the upstream View-backed `WearWidgetPreview` when absent.
  compileOnly(libs.compose.remote.player.core)
  compileOnly(libs.rcplayer.embedded.android)

  // `EmbeddedWearWidgetPlayerTest` checks the pinned entry point against the real player, so it's a
  // real test dependency. The Compose BOM + runtime are for the Compose compiler plugin, which also
  // runs over test sources.
  testImplementation(platform(libs.compose.bom.stable))
  testImplementation(platform(libs.rcplayers.bom))
  testImplementation(libs.compose.runtime)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.rcplayer.embedded.android)

  // The `compileOnly` artifacts at the same pins, so a Glance Wear bump that changes the upstream
  // `WearWidgetPreview` signature fails here with `NoSuchMethodError` rather than in consumers.
  testImplementation(libs.robolectric)
  testImplementation(libs.glance.wear)
  testImplementation(libs.glance.wear.core)
  testImplementation(libs.glance.wear.tooling.preview)
  testImplementation(libs.compose.remote.creation.compose)
  testImplementation(libs.compose.remote.player.core)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  // `debugImplementation`, not `testImplementation`: `createComposeRule` launches a
  // `ComponentActivity` that must be declared in the debug manifest Robolectric resolves against.
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "wear-preview-runtime",
    displayName = "Compose Preview — Wear Runtime",
    description =
      "Composable helpers for Wear previews: host a component in a real single-item " +
        "TransformingLazyColumn so an isolated `@Preview` shows genuine TLC item scaling (scale + " +
        "fade toward the edges) with the component authored in the normal list-item code; and " +
        "preview a Glance Wear widget in its host container while capturing the widget's encoded " +
        "RemoteCompose document as the render's `.rc` IR sidecar.",
  )
  inceptionYear.set("2026")
}
