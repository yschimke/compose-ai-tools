// `:samples:design-catalog-m3-android` — the **Android-only supplement** to the
// (now Compose Multiplatform) `:samples:design-catalog-m3` catalog.
//
// The main catalog is desktop CMP so the public preview server can live-render it,
// but a few M3 features only exist in the **androidx** `material3` line and have no
// CMP equivalent yet — notably the Material 3 **inset focus ring**
// (`RippleConfiguration.Focus.InsetRing`, material3 1.5.0-alpha). This tiny Android
// module holds just those previews, rendered via Robolectric against the alpha
// artifact; the `design-artifacts` generator folds their renders into the
// `compose-m3` catalog (by function name) so the affected variants — e.g. the
// keyboard-focus `FilledButtonFocused` — stay **selectable, with the real ring**,
// even though the sheet itself is CMP. Keep the preview **function names** here in
// lockstep with `catalog.spec.json` variants so the fold matches.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35 (project toolchain is JDK 17; SDK 36 needs JDK 21+),
  // matching the other Android sample catalogs.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.designcatalogm3android"

  defaultConfig {
    applicationId = "com.example.designcatalogm3android"
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
  // Forward-pinned off the BOM for the inset focus ring APIs (material3 1.5.0-alpha) —
  // the whole reason this Android supplement exists.
  implementation(libs.compose.material3.catalog)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
