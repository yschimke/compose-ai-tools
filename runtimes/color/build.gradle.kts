@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:color-preview-runtime` — composable-helper authoring path for colour / design-token specimens.
// Sister to `:typography-preview-runtime`. `ColorSchemeSpecimen` renders every Material 3
// `ColorScheme` role as a labelled swatch, and `ColorSpecimen` does the same for an arbitrary list
// of named brand/semantic colours — reference catalogues that consumers wrap in a normal `@Preview`
// so visual regressions in a theme's palette surface as PNG diffs alongside the rest of the
// gallery.
// This is the compose-ai-tools analogue of Airbnb Showkase's `@ShowkaseColor` sheet.
//
// Standalone on purpose — no compile dep on `:renderer-android` so the runtime can be used in
// Bazel modules or JVM unit tests that don't carry the full Robolectric renderer.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.preview.color"

  buildFeatures { compose = true }
}

dependencies {
  // Compose deps mirror `:typography-preview-runtime`'s `compileOnly` model — the consumer module
  // brings its own Compose BOM, and we compile against the older `compose-bom-compat` so emitted
  // bytecode runs unchanged against newer consumer Compose versions. The specimen helpers consume
  // the Material 3 `ColorScheme` type plus `Color` / `toArgb` from `compose-ui`, both stable
  // surface.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.compose.material3)

  // Recomposition smoke test for the specimen helpers. Compose UI test deps are
  // `testImplementation` only — they don't leak into the published AAR. We use the same
  // `compose-bom-compat` we compile against so the test JVM resolves the exact symbols the main
  // source set was built with.
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
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
    artifactId = "color-preview-runtime",
    displayName = "Compose Preview — Colour Runtime",
    description =
      "Composable helpers that render Material 3 `ColorScheme` roles and arbitrary named colour " +
        "tokens as labelled swatch sheets inside a surrounding Compose `@Preview` tree — specimen " +
        "sheets for palette / design-token visual regressions. Sister to " +
        "`typography-preview-runtime`.",
  )
  inceptionYear.set("2026")
}
