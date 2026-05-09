// Sample that exercises co-existence with Google's
// `com.android.compose.screenshot` plugin. We do NOT drive its
// `validate{Variant}ScreenshotTest` tasks — we keep rendering via Robolectric
// — but applying its plugin creates the `screenshotTest` source set, and our
// plugin has to discover + render the `@Preview` functions consumers put
// there (the idiomatic place to keep preview-only code under Google's docs).
//
// Kept as its own module so `:samples:android` stays a minimal Robolectric-only
// baseline — a regression in the screenshotTest discovery / instance-method
// receiver resolution doesn't hide behind the larger sample's render output.
@file:Suppress("UnstableApiUsage")

plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.android.compose.screenshot) apply false
  id("ee.schimke.composeai.preview")
}

val screenshotTestEnabled =
  providers
    .gradleProperty("android.experimental.enableScreenshotTest")
    .map(String::toBoolean)
    .getOrElse(false)

if (screenshotTestEnabled) {
  pluginManager.apply(libs.plugins.android.compose.screenshot.get().pluginId)
}

android {
  namespace = "com.example.sampleandroidscreenshot"

  defaultConfig {
    applicationId = "com.example.sampleandroidscreenshot"
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // `screenshotTest` is experimental in AGP. The source set appears only when
  // callers opt in with `android.experimental.enableScreenshotTest=true`.
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  if (screenshotTestEnabled) {
    // Google's plugin requires `ui-tooling` on the screenshotTest classpath
    // to instantiate PreviewParameter providers and run the `@Preview`
    // functions under Layoutlib. Our renderer doesn't use this configuration
    // — it drives composables via its own ClassGraph-discovered methods —
    // but compiling the screenshotTest source set still needs it.
    "screenshotTestImplementation"(platform(libs.compose.bom.stable))
    "screenshotTestImplementation"(libs.compose.ui.tooling.preview)
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
  }
}
