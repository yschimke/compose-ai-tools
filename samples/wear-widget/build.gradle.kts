plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Regression fixture for issue #2670: a Wear module (its manifest declares
// `<uses-feature android:name="android.hardware.type.watch">`) whose widget/tile
// previews must crop to their intrinsic layout bounds for export as fixed-size
// drawable assets, NOT get pinned onto the 227dp watch-face canvas the way a
// design-catalog component sticker does. `retargetWearPreviews = false` opts this
// module out of the canvas retarget: device-less previews stay wrap-content (the
// renderer crops each PNG to measured bounds) while discovery still swaps in the
// Wear density (2.0x) so the cropped dp bounds scale to watch-density px, not the
// inherited 2.625x phone default. `WearWidgetCropPixelTest` asserts the render is
// cropped at wear density.

composePreview {
  // Pin Robolectric to SDK 35; see the matching block in `:samples:android` for the JDK 17
  // toolchain rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)

  // Opt out of the Wear watch-canvas retarget so device-less widget previews crop to their
  // intrinsic bounds instead of the 227dp square (#2670).
  retargetWearPreviews.set(false)

  // `WearWidgetCropPixelTest` reads PNGs from `build/compose-previews/renders/`; chain the
  // unit-test tasks onto `composePreviewRenderAll` so `:samples:wear-widget:check` renders
  // before asserting.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.wearwidget"

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
