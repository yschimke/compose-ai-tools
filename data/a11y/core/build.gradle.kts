@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

// D2.2 — `:data-a11y-core` is the **published** generic-Android piece of the
// accessibility data product: the ATF wrapper (`AccessibilityChecker`), the
// Paparazzi-style overlay generator (`AccessibilityOverlay`), and the JSON
// model classes (`AccessibilityFinding` / `AccessibilityNode` / `AccessibilityEntry`
// / `AccessibilityReport`). Anything that depends only on AndroidX, Compose,
// AndroidX-test or ATF lives here; daemon coupling lives in
// `:data-a11y-connector`, which is published only to satisfy daemon-android's
// external transitive dependency.
//
// Pairs with `:data-a11y-connector` — see docs/daemon/DATA-PRODUCTS.md §
// "Module split (D2.2)".

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.a11y.core" }

dependencies {
  api(project(":data-render-core"))
  api(libs.kotlinx.serialization.json)

  // ATF (`accessibility-test-framework`) — directly referenced by `AccessibilityChecker.kt`.
  // Same artifact `:renderer-android` previously pulled transitively via
  // `roborazzi-accessibility-check`; pinned here so consumers of this published artifact
  // pick it up without dragging Roborazzi.
  implementation(libs.roborazzi.accessibility.check)

  // ShadowBuild fingerprint swap inside `AccessibilityChecker.analyze` — sidesteps ATF's
  // `Build.FINGERPRINT == "robolectric"` bail-out. Same workaround the standalone path used
  // before D2.2.
  implementation(libs.robolectric)

  // ATF nullable annotations — see `:renderer-android`'s own kdoc for the full reasoning;
  // marker-only at runtime, present at compile time so KT-80247 doesn't fire.
  compileOnly(libs.checker.qual)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}

// Vanniktech's `AndroidSingleVariantLibrary` creates the release publication
// (with sources + javadoc jar) — do not create one manually; it clashes.

mavenPublishing {
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-a11y-core",
    displayName = "Compose Preview — Accessibility Data Product (Core)",
    description =
      "Generic Android accessibility data-product primitives: ATF check wrapper, Paparazzi-style overlay PNG generator, and the JSON model classes the Gradle plugin / CLI / daemon all read. Pairs with the connector module that wires this into the compose-preview daemon's data/* JSON-RPC surface.",
  )
  inceptionYear.set("2026")
}
