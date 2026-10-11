plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; this module compiles against `compileSdk = 37` but Robolectric
  // 4.16.1 only ships up to API 36 (and needs JDK 21+ for that). See the matching block in
  // `:samples:android` for the broader JDK 17 toolchain rationale.
  sdkVersion.set(35)

  // `RemoteWidgetDocCaptureTest` reads the `.rc` sidecar + PNG from
  // `build/compose-previews/renders/`; chain the unit-test tasks onto `composePreviewRenderAll`.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.sampleremotecompose"
  // compose-remote alpha08+ / wear-compose-remote alpha02+ raise the AAR
  // minCompileSdk to 37, so this module diverges from the rest of the repo
  // (which still targets 36).
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.sampleremotecompose"
    // Remote Compose alpha artifacts require API 29+.
    minSdk = 29
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // Remote Compose APIs are `@RestrictTo(LIBRARY_GROUP)` — source-level
  // `@file:Suppress("RestrictedApiAndroidX")` quiets the IDE inspection
  // but AGP's lint runs `RestrictedApi` separately. Mirror what
  // AndroidX's own samples do and disable the check for this module.
  lint { disable += "RestrictedApi" }
}

dependencies {
  implementation(platform(libs.rcplayers.bom))
  // No Compose BOM: wear-compose-remote-material3's POM pulls the 1.11 Compose line, and
  // `PreviewWrapper` needs ui-tooling-preview 1.11+, so versions are pinned explicitly.
  implementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.remote.tooling.preview)
  // `remote-tooling-preview`'s POM declares its creation/compose deps with
  // `runtime` scope, so the compile classpath doesn't see `RemoteButton`'s
  // parameter types (`RemoteModifier`, `RemoteString`, `HostAction`, etc.)
  // unless we pull them in explicitly.
  implementation(libs.compose.remote.creation)
  implementation(libs.compose.remote.creation.compose)
  implementation(libs.wear.compose.remote.material3)
  implementation(libs.composeai.preview.annotations)
  implementation(libs.activity.compose)
  // `RemoteOverridablePreview` bridges connector-side named-value overrides into the running
  // Remote Compose player. Sample uses it in place of upstream `RemotePreview` so the panel
  // editor's `renderNow.overrides.remoteCompose.namedValues` flips `rememberNamedRemoteString`
  // bindings without rebuilding the document.
  implementation(libs.composeai.data.remotecompose.connector)
  // The embedded player at runtime: the connector has it `compileOnly`, so without it an `embedded`
  // player request falls back to the View player.
  implementation(libs.rcplayer.embedded.android)
  debugImplementation(libs.compose.ui.tooling.prerelease)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
