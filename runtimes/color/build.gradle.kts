// `:color-preview-runtime` — `ColorSchemeSpecimen` (every Material 3 `ColorScheme` role) and
// `ColorSpecimen` (arbitrary named colours) render labelled swatches inside a normal `@Preview`, so
// palette regressions show up as PNG diffs. No dependency on `:renderer-android`, so it works in
// Bazel modules and plain JVM tests.

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
