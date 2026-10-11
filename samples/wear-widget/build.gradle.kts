plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// A real Glance Wear widget module. Its widget previews use `androidx.glance.wear.tooling.preview`
// `@PreviewParameter` providers, so discovery crops them to their intrinsic bounds at wear density
// with no config.
//
// The widgets are Remote Compose: each document is captured as the `<stem>.rc` sidecar via
// `CapturingWearWidgetPreview`, so the bundle carries data rather than `@Preview` bytecode
// (`WearWidgetDocCaptureTest` asserts it).

composePreview {
  // Pin Robolectric to SDK 35; compiles against `compileSdk = 37` (glance-wear alpha raises the AAR
  // minCompileSdk) but Robolectric 4.16.1 only ships to API 36 (JDK 21+). Matches
  // `:samples:remotecompose`.
  sdkVersion.set(35)

  // Auto-detect does the cropping; `retargetWearPreviews` stays at its default to prove the
  // zero-config path.

  // `WearWidgetDocCaptureTest` / `WearWidgetCropPixelTest` read `.rc` + PNGs from
  // `build/compose-previews/renders/`; chain the unit-test tasks onto `composePreviewRenderAll`.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.wearwidget"
  // glance-wear alpha13 / wear-compose-remote alpha raise the AAR minCompileSdk to 37.
  compileSdk = 37

  defaultConfig {
    // Remote Compose alpha artifacts require API 29+.
    minSdk = 29
  }

  buildFeatures { compose = true }

  // Remote Compose / Glance Wear APIs are `@RestrictTo(LIBRARY_GROUP)`; the source-level
  // `@file:Suppress("RestrictedApiAndroidX")` quiets the IDE, but AGP lint runs `RestrictedApi`
  // separately — disable it here as AndroidX's own samples do.
  lint { disable += "RestrictedApi" }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.rcplayers.bom))
  // No Compose BOM — glance-wear / wear-compose-remote pull the Compose 1.11 line; pinning explicit
  // prerelease versions avoids fighting the 1.10.x BOM used elsewhere. Same as
  // `:samples:remotecompose`.
  implementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.remote.creation)
  implementation(libs.compose.remote.creation.compose)
  implementation(libs.wear.compose.remote.material3)
  // Glance Wear: `wear` (document and brush types; `captureRawContent` yields the `.rc`),
  // `wear-core` (`WearWidgetParams`), `wear-tooling-preview` (`WearWidgetPreview` and param
  // providers).
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)
  implementation(libs.glance.wear.tooling.preview)
  implementation(libs.activity.compose)
  // Renders a widget preview and emits its `.rc` sidecar; shared with the `remote-m3` catalog.
  implementation(project(":wear-preview-runtime"))
  // The embedded Compose player at runtime: `:wear-preview-runtime` has it `compileOnly`, and
  // without it previews fall back to upstream's View-backed player.
  implementation(libs.rcplayer.embedded.android)
  // `IrSidecarChannel` itself — `:wear-preview-runtime` keeps it `implementation`-scoped, and this
  // module's `WearWidgetDocCaptureTest` asserts on the sidecar it produces.
  implementation(libs.composeai.data.render.core)
  debugImplementation(libs.compose.ui.tooling.prerelease)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
