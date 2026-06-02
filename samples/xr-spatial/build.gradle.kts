plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// `:samples:xr-spatial` — Jetpack Compose for XR (`androidx.xr.compose`) spatial
// composables rendered through this repo's `@Preview` pipeline.
//
// Unlike a real Android XR device, the offline renderer has no Jetpack XR
// `Session` / SceneCore runtime, so `LocalSpatialCapabilities` reports
// `NoCapabilities`. That is exactly the "Home Space / non-XR" code path: spatial
// affordances that have a documented 2D fallback (`Orbiter`, `SpatialElevation`,
// `SpatialDialog`, `SpatialPopup`) degrade to their flat equivalents and render
// normally, while `Subspace { SpatialPanel { … } }` content is *ignored* (Google's
// own behaviour — see `SubspaceDemo.kt`). The previews here capture the 2D
// fallback, which is what Android Studio's `@Preview` shows for an XR app's UI in
// Home Space too. The full rationale and rendering model live in
// docs/design/XR_SPATIAL_PREVIEW.md.

composePreview {
  // Pin Robolectric to SDK 35. `androidx.xr.compose:compose`'s AAR metadata
  // declares `minCompileSdk = 36`, so the module compiles against 36, but
  // Robolectric 4.16.1 needs JDK 21+ for an SDK 36 sandbox and the repo's
  // toolchain stays on JDK 17. The 2D fallback path the previews exercise is
  // pure Compose drawing (no API-36 platform symbol at render time), so SDK 35
  // captures it cleanly — the same escape hatch `:samples:remotecompose` and
  // `:samples:android-alpha` use to render compileSdk-37 modules under JDK 17.
  // Drop this override when the toolchain moves to JDK 21.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.samplexrspatial"

  // `androidx.xr.compose:compose` raises `minCompileSdk = 36` in its AAR
  // metadata, so the module must compile against 36. That is the repo default
  // from `composeai.android-conventions` (platform-36 is installed); unlike
  // `:samples:xr-glimmer` no platform-37 bump is needed.
  compileSdk = 36

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)

  // Jetpack Compose for XR. Brings the `androidx.xr.compose.spatial` affordances
  // (`Orbiter`, `SpatialElevation`, `SpatialDialog`) and the
  // `androidx.xr.compose.subspace` layout system (`Subspace`, `SpatialPanel`,
  // `SpatialRow`/`SpatialColumn`). Pulls `androidx.xr.scenecore` /
  // `androidx.xr.runtime` transitively — those only matter on-device; the 2D
  // fallback path the previews exercise never reaches for a `Session`.
  implementation(libs.xr.compose)

  debugImplementation("androidx.compose.ui:ui-tooling")
}
