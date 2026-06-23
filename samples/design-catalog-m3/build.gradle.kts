// `:samples:design-catalog-m3` — a Compose Material 3 **design catalog**: one
// `@Preview` per component in its primary modes, authored so the upstream
// `compose-preview` renderer can turn the module into an importable sticker
// sheet (see `@design-parity/catalog-export` in yschimke/design-parity).
//
// This is the code-led source of truth for the M3 sticker sheet: the renders,
// the `compose/theme` token set, the `compose/semantics-wireframe` layout
// variant, and the a11y findings all come from these previews. Kept deliberately
// thin — only `material3` + the preview tooling — so it builds against the stable
// Compose BOM. M3 Adaptive (window-size canonical layouts) is a planned
// follow-up; breakpoints are exercised here via `@Preview(widthDp = …)`.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Match the sibling Android sample: pin Robolectric to SDK 35 (the project
  // toolchain is JDK 17; Robolectric SDK 36 needs JDK 21+). Drop when the
  // toolchain moves to JDK 21.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.designcatalogm3"

  defaultConfig {
    applicationId = "com.example.designcatalogm3"
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
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
