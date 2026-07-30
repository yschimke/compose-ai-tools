plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Regression coverage for issue #136: applying `composePreview` to a
// `com.android.library` module on AGP 9.x used to fail at configuration time
// because the plugin depended on `process${Cap}Resources`, which exists only
// on application variants. Rendering this module verifies the library path
// stays configured + executes end-to-end.

composePreview {
  // Pin Robolectric to SDK 35; see the matching block in `:samples:android` for the JDK 17
  // toolchain rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)

  // Issue #2957. `HtmlShowNotesPreview` is a plain `@Preview` whose body is an `AndroidView`
  // hosting a `TextView` styled through the app-owned `?attr/sampleBodyTextAppearance`. A library
  // module's merged manifest declares no `<application android:theme>`, so without naming a theme
  // here the preview host activity runs under the platform default, the attribute resolves to
  // nothing, and inflation throws `UnsupportedOperationException: Failed to resolve attribute at
  // index N` — which aborts the render, so the preview emits no PNG at all rather than a broken
  // one. This is the library-module counterpart of what an application module gets for free.
  hostTheme.set("@style/Theme.SampleLibrary")

  // `AndroidViewHtmlTextPixelTest` reads a PNG out of `build/compose-previews/renders/`, so chain
  // the unit-test task behind `composePreviewRenderAll`.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.samplelibrary"

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
