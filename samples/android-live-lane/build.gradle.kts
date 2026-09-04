// `:samples:android-live-lane` — the fixture behind the **Android (Robolectric) serve lane** e2e.
//
// That e2e lives in the split-out `yschimke/compose-preview-server`
// (`preview-harness/serve-lanes.spec.mjs`, driven by `serve-lanes-boot.sh`), NOT in this
// repository — the workflow and spec this comment used to name were moved with the preview server
// and nothing here builds or runs them. This module is still the fixture they consume, so it is
// load-bearing for a suite whose failures surface in the other repo.
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
// the contract the spec selects on (`overrides[].key == "label"`) — so the Android lane reuses the
// desktop lane's spec unmodified.
//
// **That knob stays a `previewOverride*` one until someone can run the spec.** The spec reads the
// override list from the bundle's `previews/<id>.overrides.json` sidecar and FAILS rather than
// skips when it finds no label-knob preview. The Android bake now records a *parameter* knob into
// that sidecar too, so the format is no longer the blocker — but the spec needs a daemon-backed
// serve under Playwright and cannot run from this repository, so migrating the knob here would ship
// an unverified change to another repo's required e2e. See
// `docs/design/PARAMETER_KNOB_MIGRATION.md` → gap 6.
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
  implementation(project(":data-preview-overrides-runtime"))
  debugImplementation("androidx.compose.ui:ui-tooling")
}
