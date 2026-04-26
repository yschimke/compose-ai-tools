plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview { accessibilityChecks { enabled = false } }

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

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }

  // Remote Compose APIs are `@RestrictTo(LIBRARY_GROUP)` — source-level
  // `@file:Suppress("RestrictedApiAndroidX")` quiets the IDE inspection
  // but AGP's lint runs `RestrictedApi` separately. Mirror what
  // AndroidX's own samples do and disable the check for this module.
  lint { disable += "RestrictedApi" }
}

dependencies {
  // This module does NOT use the Compose BOM — wear-compose-remote-material3
  // alpha01's POM pulls in Compose 1.11.0-beta01 runtime for foundation /
  // runtime / ui, and `PreviewWrapper` only exists in ui-tooling-preview
  // 1.11.0-beta+. Pinning explicit versions keeps resolution aligned with
  // the 1.11 line and avoids fighting the 1.10.x BOM used elsewhere.
  implementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.remote.tooling.preview)
  // `remote-tooling-preview`'s POM declares its creation/compose deps with
  // `runtime` scope, so the compile classpath doesn't see `RemoteButton`'s
  // parameter types (`RemoteModifier`, `RemoteString`, `HostAction`, etc.)
  // unless we pull them in explicitly.
  implementation(libs.compose.remote.creation)
  implementation(libs.compose.remote.creation.compose)
  implementation(libs.wear.compose.remote.material3)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling:1.11.0")
}
