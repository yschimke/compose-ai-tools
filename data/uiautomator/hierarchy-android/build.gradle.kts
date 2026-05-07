@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

// `:data-uiautomator-hierarchy-android` — the Android-platform-specific producer for
// `uia/hierarchy` (#874). Walks a Compose `SemanticsOwner` tree and emits a typed
// `UiAutomatorHierarchyPayload` carrying the actionable subset agents need to formulate
// `uia.*` selectors. Mirrors `:data-a11y-hierarchy-android`'s producer + extension shape;
// uses the same `PostCaptureProcessor` hook so the typed product graph stays
// platform-agnostic downstream.
//
// Default filter (per the issue's review feedback) keeps only nodes exposing at least one
// of `UiAutomatorDataProducts.SUPPORTED_ACTIONS` — drops ~80% of layout / pure-text wrappers
// while preserving every viable dispatch target. `includeNonActionable` flag reverts to a
// debug-friendly walk for selector tuning.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.uiautomator.hierarchy.android" }

dependencies {
  api(project(":data-uiautomator-core"))

  // Compose SemanticsNode / SemanticsActions / SemanticsProperties — same `compileOnly`
  // pattern `:data-uiautomator-core` uses so we don't pin a Compose version onto downstream
  // consumers' classpaths. Test classpath gets the full runtime.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.runtime)
  compileOnly("androidx.compose.ui:ui-test-junit4")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
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
    artifactId = "data-uiautomator-hierarchy-android",
    displayName = "Compose Preview — UIAutomator Hierarchy Producer (Android)",
    description =
      "Android-platform-specific producer for the `uia/hierarchy` data product: walks a Compose " +
        "SemanticsOwner tree and emits the actionable subset agents need to formulate `uia.*` " +
        "selectors. Pairs with `:data-uiautomator-core`'s typed payload + selector DSL.",
  )
  inceptionYear.set("2026")
}
