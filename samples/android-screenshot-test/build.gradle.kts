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

  // `screenshotTest` is experimental in AGP. The source set appears only when
  // callers opt in with `android.experimental.enableScreenshotTest=true`.
  //
  // The gradle property alone isn't enough for Google's plugin: it also requires the
  // per-module `experimentalProperties` flag, and fails configuration without it
  // ("Please enable screenshotTest source set in module first"). Gated on the same
  // property so the default build is unchanged.
  if (screenshotTestEnabled) {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
  }
}

// `StudioParityTest` diffs `build/compose-previews/renders/` against the committed Layoutlib
// references, so the renders have to exist by the time it runs. Same wiring rationale as
// `composePreview { renderBeforeUnitTests }` in the other samples; spelled out here because this
// module's previews live in the `screenshotTest` source set.
// `tasks.matching { … }.configureEach { … }` rather than `named(…)`: AGP registers the unit-test
// tasks lazily, so eager lookup fails at configuration time.
tasks
  .matching { it.name == "testDebugUnitTest" }
  .configureEach { dependsOn("composePreviewRenderAll") }

// Tell `StudioParityTest` whether its gate is SUPPOSED to run. Without this it can only see that
// our Parity renders are missing, which is the correct state when the screenshotTest source set was
// never materialised and a broken build when it was — indistinguishable from inside the test,
// because the Layoutlib references are committed either way. Left to guess it assumed the benign
// case and skipped green.
//
// SCOPE, because it is narrower than it looks: this is derived from the same property that
// materialises the source set, so it catches "the gate was asked to run and had nothing of ours to
// compare" — a render that produced no fixtures. It canNOT catch the `-P` flag being dropped
// altogether, which turns this false and hands the test back its skip. Nothing inside the build can
// catch that, because a flagless run is also the legitimate local case. CI carries that half, in a
// step that asserts the gate compared something without consulting Gradle at all.
//
// `withType<Test>` rather than the `matching` block above, which configures a generic `Task` and so
// cannot reach `systemProperty`.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  systemProperty("studioParity.required", screenshotTestEnabled)
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
    // Google's plugin requires `ui-tooling` on the screenshotTest classpath
    // to instantiate PreviewParameter providers and run the `@Preview`
    // functions under Layoutlib. Our renderer doesn't use this configuration
    // — it drives composables via its own ClassGraph-discovered methods —
    // but compiling the screenshotTest source set still needs it.
    "screenshotTestImplementation"(platform(libs.compose.bom.stable))
    "screenshotTestImplementation"(libs.compose.ui.tooling.preview)
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
    // `@PreviewTest` — from alpha15 on, Google's plugin only *discovers* a `@Preview` that also
    // carries this marker, so the Studio-parity fixtures need the annotation on the compile
    // classpath. Same coordinates the plugin runs its own JUnit engine from.
    "screenshotTestImplementation"(libs.android.screenshot.validation.api)
  }
}
