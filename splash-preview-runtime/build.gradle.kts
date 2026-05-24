@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:splash-preview-runtime` — composable-helper authoring path for the Android 12+
// SplashScreen window appearance. Sister to `:notification-preview-runtime` and
// `:glance-preview-runtime`: a tiny JVM-friendly helper consumed inside a regular `@Preview`,
// no new annotation, no new renderer strategy.
//
// `SplashScreenSurface(icon = …, background = …, iconBackground = …, brandingImage = …)`
// recreates the proportions Android paints when the SplashScreen API runs at app launch —
// full-bleed background, centred icon masked to the splash-icon shape (a circle whose
// diameter ≈ 75% of the canvas's short edge per the SplashScreen spec), optional
// `windowSplashScreenIconBackgroundColor` ring, optional `windowSplashScreenBrandingImage`
// at the bottom. The reproduction is qualitative (the rendered footprint reads like the
// real splash on a phone-shaped canvas), not pixel-perfect against the SystemUI compositor.
//
// Standalone on purpose — no compile dep on `:renderer-android` so the runtime can be used
// in Bazel modules or JVM unit tests that don't carry the full Robolectric renderer. Pure
// Compose Foundation under the hood: no platform splash APIs are invoked (the
// `androidx.core:core-splashscreen` library is an *app-side* shim around the platform
// SplashScreen window; it doesn't expose a reusable Compose surface that mirrors the visual
// appearance for tooling).

plugins {
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
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
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
