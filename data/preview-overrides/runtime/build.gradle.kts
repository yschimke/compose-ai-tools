// `:data-preview-overrides-runtime` — the consumer-facing opt-in API for plain-Compose named overrides.
// A preview author adds this as `implementation(...)` and wraps editable values in `previewOverride*`
// keyed lookups (`previewOverrideString("label", "Tap me")`, `previewOverrideInt("rowCount", 3)`, indexed
// per-item knobs for repeated components). Each lookup returns the daemon-seeded value (or the author
// default) and records its declaration into the process-static `PreviewOverrideController` so a producer
// can enumerate "what is editable" on the preview.
//
// Compose Multiplatform JVM (works for both Android and CMP-desktop previews). Compose itself is
// `compileOnly`: the consumer brings its own Compose (androidx OR jetbrains — same FQNs), so this artifact
// never forces a Compose flavour onto a consumer's classpath. Mirrors the `compileOnly` Compose pattern in
// `:data-remotecompose-connector`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  // Wire-shape (declaration + product kind) and, transitively, `PreviewOverrideValue`. `api` so a
  // consumer test or the connector can refer to them without a second dependency.
  api(project(":data-preview-overrides-core"))

  // Compose runtime (`@Composable`, `compositionLocalOf`, `SideEffect`) + UI (`Color`, `toArgb`, `Dp`).
  // `compileOnly` — the consumer supplies the matching Compose at runtime.
  compileOnly(libs.jetbrains.compose.runtime)
  compileOnly(libs.jetbrains.compose.ui)

  testImplementation(libs.jetbrains.compose.runtime)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-preview-overrides-runtime",
    displayName = "Compose Preview - Named Override Runtime",
    description =
      "Opt-in consumer API for plain-Compose preview overrides: `previewOverride*` keyed lookups (string/int/float/bool/color/dp, with indexed knobs for repeated components) that a preview uses to expose editable values, resolved against daemon seeds and recorded for the `compose/overrides` data product.",
  )
  inceptionYear.set("2026")
}
