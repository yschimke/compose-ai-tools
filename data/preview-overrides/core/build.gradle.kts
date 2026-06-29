// `:data-preview-overrides-core` — wire-shape for the plain-Compose **named override** data product
// (`compose/overrides`). Mirrors `:data-remotecompose-core`: the product-kind constant + payload
// classes live on a tiny JVM module so MCP clients and the bundle producer can depend on the
// payload
// schema without dragging in the connector, Compose, or any backend.
//
// The author-declared knobs a preview exposes through the `previewOverride*` lookups
// (`:data-preview-overrides-runtime`) are captured here as [PreviewOverrideDeclaration]s and
// carried in
// the `compose/overrides` payload — the data both a live daemon (`data/fetch`) and a portable
// bundle
// surface so a viewer can present editable controls.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries `PreviewOverrideValue` (the typed value variant the declaration and the
  // `renderNow.overrides.namedOverrides` seed share). Re-exported via `api` so consumers refer to
  // it
  // without a second `project` dependency.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-preview-overrides-core",
    displayName = "Compose Preview - Named Override Data Product Core",
    description =
      "Shared model classes for the plain-Compose named-override data product (`compose/overrides`): the author-declared editable knobs (`previewOverride*`) a preview exposes, carried both live (`data/fetch`) and inside a portable bundle so a viewer can present editable controls.",
  )
  inceptionYear.set("2026")
}
