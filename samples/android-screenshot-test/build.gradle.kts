// Co-existence with Google's `com.android.compose.screenshot` plugin: its `screenshotTest` source
// set is the idiomatic home for preview-only code, so our plugin must discover and render
// `@Preview`s there (we don't drive its validate tasks). Its own module so `:samples:android` stays
// a minimal Robolectric baseline.
@file:Suppress("UnstableApiUsage")

plugins {
  id("composeai.base-conventions")
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

composePreview {
  // Pin Robolectric to SDK 35; see the matching block in `:samples:android` for the JDK 17
  // toolchain rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)
}

android {
  namespace = "com.example.sampleandroidscreenshot"
  // Compose 1.12 (BOM 2026.08.00) publishes minCompileSdk 37 metadata.
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.sampleandroidscreenshot"
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // `screenshotTest` is experimental: it needs `android.experimental.enableScreenshotTest=true`
  // and, for Google's plugin, the per-module `experimentalProperties` flag. Gated so the default
  // build is unchanged.
  if (screenshotTestEnabled) {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
  }
}

// `StudioParityTest` needs the renders, so render before unit tests (as `renderBeforeUnitTests`
// does elsewhere). `tasks.matching { … }` because AGP registers unit-test tasks lazily.
tasks
  .matching { it.name == "testDebugUnitTest" }
  .configureEach { dependsOn("composePreviewRenderAll") }

// Tell `StudioParityTest` whether its gate should run, so missing renders fail rather than skip
// green when the source set was materialised. Can't catch the `-P` flag being dropped altogether
// (that's also the legitimate local case); CI asserts that separately. `withType<Test>` to reach
// `systemProperty`.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  systemProperty("studioParity.required", screenshotTestEnabled)
  // Declare the side-by-side composites as task outputs so a FROM-CACHE `testDebugUnitTest`
  // restores them; otherwise CI's "gate compared something" check fails on a cache hit. Optional
  // because a skipped gate produces none.
  outputs
    .dir(layout.buildDirectory.dir("studio-parity"))
    .withPropertyName("studioParityComposites")
    .optional()
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.truth)

  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  if (screenshotTestEnabled) {
    // Google's plugin needs `ui-tooling` on the screenshotTest classpath for Layoutlib; our
    // renderer doesn't use it, but compiling the source set does.
    "screenshotTestImplementation"(platform(libs.compose.bom.stable))
    "screenshotTestImplementation"(libs.compose.ui.tooling.preview)
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
    // `@PreviewTest` — from alpha15 on, Google's plugin only *discovers* a `@Preview` that also
    // carries this marker, so the Studio-parity fixtures need the annotation on the compile
    // classpath. Same coordinates the plugin runs its own JUnit engine from.
    "screenshotTestImplementation"(libs.android.screenshot.validation.api)
  }
}
