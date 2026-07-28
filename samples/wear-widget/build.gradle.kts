plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Issue #2670 fixture — a **real** Glance Wear widget module. Its manifest declares the watch
// feature, so PreviewDiscovery marks it Wear; its widget previews use `@PreviewParameter` providers
// from `androidx.glance.wear.tooling.preview` (the `WearWidgetParams` shape #2670 is about), so the
// discovery auto-detect crops them to their intrinsic bounds at wear density with **no config** —
// never the 227dp watch-face canvas.
//
// The widgets are Remote Compose: a Wear widget's value is its **encoded RemoteCompose document**,
// captured here as the `<stem>.rc` sidecar via `CapturingWearWidgetPreview` (see that file).
// That
// keeps the widget in the portable bundle as data (its `.rc`), not as compiled `@Preview`
// bytecode — `WearWidgetDocCaptureTest` asserts it.

composePreview {
  // Pin Robolectric to SDK 35; compiles against `compileSdk = 37` (glance-wear alpha raises the AAR
  // minCompileSdk) but Robolectric 4.16.1 only ships to API 36 (JDK 21+). Matches
  // `:samples:remotecompose` / `:samples:design-catalog-remote-m3`.
  sdkVersion.set(35)

  // Auto-detect (not the flag) does the cropping here: the glance-wear `@PreviewParameter`
  // providers
  // are recognised as widgets, so we leave `retargetWearPreviews` at its `true` default to prove
  // the
  // zero-config path end-to-end.

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
  // No Compose BOM — glance-wear / wear-compose-remote pull the Compose 1.11 line; pinning explicit
  // prerelease versions avoids fighting the 1.10.x BOM used elsewhere. Same as
  // `:samples:remotecompose`.
  implementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.remote.creation)
  implementation(libs.compose.remote.creation.compose)
  implementation(libs.wear.compose.remote.material3)
  // Glance Wear — the Wear OS widget layer on Remote Compose. `wear` carries the widget document +
  // brush types (`WearWidgetDocument`, `WearWidgetBrush`) whose `captureRawContent` yields the
  // encoded `.rc`; `wear-core` the `WearWidgetParams` container spec; `wear-tooling-preview` the
  // `WearWidgetPreview` composable + `SquircleAllWidgetPreviewParams` providers.
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)
  implementation(libs.glance.wear.tooling.preview)
  implementation(libs.activity.compose)
  // `CapturingWearWidgetPreview` — the shared Wear helper that renders a widget preview AND offers
  // its encoded RemoteCompose document to `IrSidecarChannel`, so the render lands a `<stem>.rc`
  // sidecar next to the PNG. Shared with `:samples:design-catalog-remote-m3` so both widget
  // surfaces capture their document the same way.
  implementation(project(":wear-preview-runtime"))
  // `IrSidecarChannel` itself — `:wear-preview-runtime` keeps it `implementation`-scoped, and this
  // module's `WearWidgetDocCaptureTest` asserts on the sidecar it produces.
  implementation(project(":data-render-core"))
  debugImplementation(libs.compose.ui.tooling.prerelease)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
