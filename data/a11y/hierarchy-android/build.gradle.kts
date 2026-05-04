@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

// `:data-a11y-hierarchy-android` — the Android-platform-specific producer for
// `a11y/hierarchy` and `a11y/atf`. Wraps `AccessibilityChecker.analyze` (ATF +
// Robolectric) into a `PostCaptureProcessor` so the typed product graph stays
// platform-agnostic downstream — `:data-a11y-core` consumers like
// `TouchTargetsExtension` and `OverlayExtension` only see typed payloads, not
// `View`s or ATF types. A future Compose-Multiplatform Desktop variant lands as
// `:data-a11y-hierarchy-desktop` with the same outputs and `targets = {Desktop}`;
// the planner's target filter selects exactly one provider per platform.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.a11y.hierarchy.android" }

dependencies {
  api(project(":data-a11y-core"))

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}

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
    artifactId = "data-a11y-hierarchy-android",
    displayName = "Compose Preview — Accessibility Hierarchy Producer (Android)",
    description =
      "Android-platform-specific producer that runs ATF over a captured View tree and emits typed AccessibilityHierarchyPayload + AccessibilityFindingsPayload products. Pairs with `:data-a11y-core`'s platform-agnostic consumers (touch targets, overlay).",
  )
  inceptionYear.set("2026")
}
