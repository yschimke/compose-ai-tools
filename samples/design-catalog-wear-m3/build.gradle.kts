// `:samples:design-catalog-wear-m3` — a Wear Compose Material 3 **design catalog**:
// one `@Preview` per component in its primary modes, authored so the upstream
// `compose-preview` renderer can export the module as an importable sticker sheet
// (see `@design-parity/catalog-export` in yschimke/design-parity, and the M3
// sibling `:samples:design-catalog-m3`).
//
// Code-led source of truth for the Wear M3 sheet. Wear is dark-first, so the
// primary "modes" are the round size breakpoints (small + large round) supplied
// by the `@WearPreviewSmallRound` / `@WearPreviewLargeRound` multipreviews rather
// than a light/dark pair. Kept thin — `wear.compose.material3` + the Wear preview
// tooling — so it builds against the stable Compose BOM.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; see `:samples:wear` for the JDK 17 toolchain
  // rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)
}

android {
  namespace = "com.example.designcatalogwearm3"

  defaultConfig {
    applicationId = "com.example.designcatalogwearm3"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
