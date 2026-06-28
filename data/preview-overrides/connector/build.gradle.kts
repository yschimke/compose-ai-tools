// `:data-preview-overrides-connector` — daemon-side glue for the plain-Compose named-override surface.
// Mirrors `:data-touch-overlay-connector` (a shared Compose Multiplatform module consumed by BOTH
// `:daemon:desktop` and `:daemon:android`) because nothing here is backend-specific: it seeds the
// process-static `PreviewOverrideController` from `renderNow.overrides.namedOverrides`, installs the
// `LocalPreviewOverrideHost` composition local, and produces the `compose/overrides` data product (the
// set of editable knobs the preview declared). Unlike `:data-remotecompose-connector` there is no alpha
// runtime to gate on, so a single shared module suffices.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Wire-shape + product kind (`compose/overrides`), re-exported so `:daemon:*` can register the
  // extension by referring to the connector's classes only.
  api(project(":data-preview-overrides-core"))
  // The consumer runtime — the connector seeds + reads the same `PreviewOverrideController` the
  // `previewOverride*` lookups use, and provides `LocalPreviewOverrideHost`.
  api(project(":data-preview-overrides-runtime"))

  // DataProductRegistry, DataExtension, AroundComposableExtension.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)

  testImplementation(libs.jetbrains.compose.runtime)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-preview-overrides-connector",
    displayName = "Compose Preview - Named Override Data Product Connector",
    description =
      "Daemon-side connector for plain-Compose named overrides: seeds `renderNow.overrides.namedOverrides` into the `previewOverride*` lookups and surfaces the preview's declared editable knobs through `data/fetch?kind=compose/overrides`.",
  )
  inceptionYear.set("2026")
}
