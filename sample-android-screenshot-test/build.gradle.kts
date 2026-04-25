// Sample that exercises co-existence with Google's
// `com.android.compose.screenshot` plugin. We do NOT drive its
// `validate{Variant}ScreenshotTest` tasks — we keep rendering via Robolectric
// — but applying its plugin creates the `screenshotTest` source set, and our
// plugin has to discover + render the `@Preview` functions consumers put
// there (the idiomatic place to keep preview-only code under Google's docs).
//
// Kept as its own module so `:sample-android` stays a minimal Robolectric-only
// baseline — a regression in the screenshotTest discovery / instance-method
// receiver resolution doesn't hide behind the larger sample's render output.
@file:Suppress("UnstableApiUsage")

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.android.compose.screenshot)
  id("ee.schimke.composeai.preview")
}

android {
  namespace = "com.example.sampleandroidscreenshot"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example.sampleandroidscreenshot"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }

  // Required opt-in for `com.android.compose.screenshot`. Without this the
  // plugin registers no tasks and the `screenshotTest` source set never
  // appears on disk — at which point we'd only discover `main` previews
  // and this sample would degenerate into a carbon copy of
  // `:sample-android`.
  experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Google's plugin requires `ui-tooling` on the screenshotTest classpath
  // to instantiate PreviewParameter providers and run the `@Preview`
  // functions under Layoutlib. Our renderer doesn't use this configuration
  // — it drives composables via its own ClassGraph-discovered methods —
  // but compiling the screenshotTest source set still needs it.
  "screenshotTestImplementation"(platform(libs.compose.bom))
  "screenshotTestImplementation"(libs.compose.ui.tooling.preview)
  "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
}
