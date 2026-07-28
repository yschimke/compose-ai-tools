// `:samples:android-live-lane` — the fixture behind the **Android (Robolectric) serve lane** e2e
// (`.github/workflows/serve-lanes-e2e.yml`, job `serve-lanes-android`).
//
// It is packed into a bundle and live-rendered by a daemon-backed `compose-preview serve`,
// reproducing the shape that let #2669 ship broken: a merged manifest naming an `Application` the
// render classpath does NOT carry (see `src/main/AndroidManifest.xml`). Robolectric resolves that
// name at sandbox bootstrap, so unless the detached daemon pins `android.app.Application` — via the
// package-scoped `robolectric.properties` `AndroidBundleResources.daemonClasspath` writes, plus the
// manifest `android:name` strip beside it — every sandbox aborts with `ClassNotFoundException` and
// the catalog fails SOFT to baked PNGs.
//
// Deliberately tiny (one preview file, no resources, no theme) so the e2e's cold Robolectric start
// dominates rather than the build. The single `@Preview` declares a `label` string knob, which is
// the contract `preview-harness/serve-lanes.spec.mjs` looks for — so the Android lane reuses the
// desktop lane's spec unmodified.
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
  implementation(project(":data-preview-overrides-runtime"))
  debugImplementation("androidx.compose.ui:ui-tooling")
}
