// `:samples:android-live-lane` — the fixture for the Android (Robolectric) serve-lane e2e, which
// lives in yschimke/compose-preview-server (`preview-harness/serve-lanes.spec.mjs`); failures
// surface there.
//
// Packed into a bundle and live-rendered by `compose-preview serve`, its merged manifest names an
// `Application` the render classpath lacks. Unless the detached daemon pins
// `android.app.Application` (the `robolectric.properties` from
// `AndroidBundleResources.daemonClasspath`, plus the manifest strip), every sandbox aborts and the
// catalog falls back to baked PNGs.
//
// Deliberately tiny so cold Robolectric start dominates. The one `@Preview` declares a `label`
// string knob, which the spec selects on. Keep it a `previewOverride*` knob until the spec can be
// run against a parameter-knob change (see `docs/design/PARAMETER_KNOB_MIGRATION.md` → gap 6).
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35 (project toolchain is JDK 17; SDK 36 needs JDK 21+), matching the
  // other Android samples.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.androidlivelane"
  // Compose 1.12 (BOM 2026.08.00) publishes minCompileSdk 37 metadata.
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.androidlivelane"
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  lint {
    // The missing `Application` class is the POINT of this module (see AndroidManifest.xml), so
    // lint's MissingClass — an error by default, and correct for a real app — is disabled here
    // rather than worked around. Keep the disable scoped to this module.
    disable += "MissingClass"
  }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  // `previewOverrideString` — the declared-knob runtime the serve lane flips via `?knob.label=`.
  implementation(libs.composeai.data.preview.overrides.runtime)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
