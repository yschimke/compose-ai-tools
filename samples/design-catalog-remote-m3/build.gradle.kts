// `:samples:design-catalog-remote-m3` — a Remote Compose **design catalog**: one
// `@Preview` per Remote Compose component (Wear Compose Remote Material 3 +
// the `remote-creation-compose` primitives), authored so the upstream
// `compose-preview` renderer can export the module as an importable sticker
// sheet (see `@design-parity/catalog-export` in yschimke/design-parity, and the
// M3 / Wear siblings `:samples:design-catalog-m3` / `:samples:design-catalog-wear-m3`).
//
// Code-led source of truth for the Remote Compose sheet. Each sticker is a real
// `RemoteDocument` — the composable emits remote content, `RemotePreview` builds
// the document and the player rasterises it, exactly the path a watch face /
// tile / widget takes on-device. That's why this module carries the alpha
// Remote Compose runtime rather than the stable Compose BOM (mirrors
// `:samples:remotecompose`, which is the "how to preview Remote Compose" demo;
// this module is the sticker sheet).
@file:Suppress("RestrictedApiAndroidX")

plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; this module compiles against `compileSdk = 37`
  // but Robolectric 4.16.1 only ships up to API 36 (and needs JDK 21+ for that).
  // Matches `:samples:remotecompose`.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.designcatalogremotem3"
  // compose-remote alpha08+ / wear-compose-remote alpha02+ raise the AAR
  // minCompileSdk to 37, so this module diverges from the rest of the repo
  // (which still targets 36) — same as `:samples:remotecompose`.
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.designcatalogremotem3"
    // Remote Compose alpha artifacts require API 29+.
    minSdk = 29
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // Remote Compose APIs are `@RestrictTo(LIBRARY_GROUP)` — source-level
  // `@file:Suppress("RestrictedApiAndroidX")` quiets the IDE inspection but
  // AGP's lint runs `RestrictedApi` separately. Mirror what AndroidX's own
  // samples do and disable the check for this module.
  lint { disable += "RestrictedApi" }
}

dependencies {
  // This module does NOT use the Compose BOM — wear-compose-remote-material3
  // alpha01's POM pulls in Compose 1.11.0-beta01 runtime for foundation /
  // runtime / ui, and the alpha remote runtime aligns with the 1.11 line.
  // Pinning explicit versions keeps resolution aligned and avoids fighting the
  // 1.10.x BOM used elsewhere. Same rationale as `:samples:remotecompose`.
  implementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.remote.tooling.preview)
  // `remote-tooling-preview`'s POM declares its creation/compose deps with
  // `runtime` scope, so the compile classpath doesn't see the remote primitive
  // types (`RemoteModifier`, `RemoteString`, `HostAction`, `RemoteBrush`, …)
  // unless we pull them in explicitly.
  implementation(libs.compose.remote.creation)
  implementation(libs.compose.remote.creation.compose)
  implementation(libs.wear.compose.remote.material3)
  // Glance Wear — the Wear OS widget layer on Remote Compose. The widget-container
  // stickers (`WidgetContainerPreviews.kt`) render through its `wear-tooling-preview`
  // `WearWidgetPreview` wrapper, which recreates the host-drawn squircle container
  // (background + rounded corners + padding, `WearWidgetContainer`) around remote
  // content. `wear` carries the brush/document types, `wear-core` the
  // `WearWidgetParams` / `ContainerInfo` container spec; both are compile-scope
  // needs of the sticker code, so declared explicitly rather than trusted to
  // transitive scoping (same rationale as the remote-creation pair above).
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)
  implementation(libs.glance.wear.tooling.preview)
  implementation(libs.activity.compose)
  // The sticker frame captures through the connector's `RemoteOverridablePreview`
  // rather than raw upstream `RemotePreview`, so the named-value stickers
  // (`NamedLabelRemoteButton`, `ShaderGradientSticker`) honour
  // `renderNow.overrides.remoteCompose.namedValues` in trusted live re-renders and
  // the captured RemoteDocument lands in the bundle's `.rc` sidecar. With no
  // seeded overrides (the vanilla `composePreviewRenderAll` / weekly render) it is
  // byte-for-byte the same output as `RemotePreview`.
  implementation(project(":data-remotecompose-connector"))
  debugImplementation(libs.compose.ui.tooling.prerelease)
}
