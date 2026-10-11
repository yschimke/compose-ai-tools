// `:splash-preview-runtime` — `SplashScreenSurface(icon, background, iconBackground,
// brandingImage)`, a composable helper used inside a regular `@Preview` that recreates the Android
// 12+ SplashScreen proportions (qualitatively, not pixel-perfect against SystemUI).
//
// No dependency on `:renderer-android`, so it works in Bazel modules and plain JVM tests. Pure
// Compose Foundation; `core-splashscreen` offers no reusable visual surface.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.preview.splash"

  buildFeatures { compose = true }
}

dependencies {
  // Compose deps mirror `:notification-preview-runtime`'s `compileOnly` model — the consumer
  // module brings its own Compose BOM, and we compile against the older `compose-bom-compat`
  // so emitted bytecode runs unchanged against newer consumer Compose versions. See
  // `:renderer-android`'s build script for the long-form rationale.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  // `AnimatedSplashScreenSurface`'s icon pulse (rememberInfiniteTransition / animateFloat).
  // Declared explicitly rather than leaned on as a foundation transitive; the version comes
  // from the BOM above, same as the unversioned test deps below.
  compileOnly("androidx.compose.animation:animation-core")

  // Robolectric-based test for `SplashScreenSurface`. Compose UI test deps are
  // `testImplementation` only — they don't leak into the published AAR. We use the same
  // `compose-bom-compat` we compile against so the test JVM resolves the exact symbols the main
  // source set was built with.
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.runtime)
  testImplementation("androidx.compose.animation:animation-core")
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "splash-preview-runtime",
    displayName = "Compose Preview — Splash Runtime",
    description =
      "Composable helper that recreates the Android 12+ SplashScreen window appearance " +
        "(full-bleed background, masked centre icon, optional icon backdrop and branding image) " +
        "inside a regular Compose `@Preview` so authors can fan splash variants across the " +
        "existing uiMode / locale / widthDp knobs.",
  )
  inceptionYear.set("2026")
}
