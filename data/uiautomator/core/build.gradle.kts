@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:data-uiautomator-core` — UIAutomator-shaped query/action API for the Compose preview
// renderer.
//
// Carries the [Selector] DSL + [By] factory, the [NodeBacking] matcher, two backings
// ([ViewBacking] for plain Android views and [SemanticsBacking] for Compose `SemanticsNode`
// trees), the [UiObject] sealed result type, and the [SelectorJson] wire format. Published so
// `:daemon:android` (which is published) can depend on it without producing a publish-graph
// dangling reference, and so the matcher can travel across the daemon bridge into the Robolectric
// sandbox for `record_preview`'s `uia.*` script events.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.uiautomator.core" }

dependencies {
  // Selector JSON wire format — needed so the matcher can travel across the daemon bridge
  // (DispatchUiAutomator envelope, see docs/daemon/INTERACTIVE-ANDROID.md) and the MCP
  // record_preview surface without forcing host code onto consumer classpaths.
  implementation(libs.kotlinx.serialization.json)

  // Compose-side traversal walks `SemanticsNode` and dispatches actions through
  // `SemanticsActions` lambdas. `compileOnly` for the same reason `:renderer-android` does it
  // — the consumer's classpath supplies the actual runtime, and we don't want to pin a
  // specific Compose version onto downstream projects. Tests use full runtime classes via
  // `testImplementation`.
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
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-uiautomator-core",
    displayName = "Compose Preview — UIAutomator-shaped Selector + Actions",
    description =
      "UIAutomator-shaped query/action API for the Compose preview renderer: a BySelector-mirroring " +
        "DSL that matches against either Android View trees (View.createAccessibilityNodeInfo) or " +
        "Compose SemanticsNode trees (SemanticsProperties / SemanticsActions), plus a JSON wire " +
        "format for crossing the daemon bridge.",
  )
  inceptionYear.set("2026")
}
