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
  // `PreviewOverrideValue` (the typed value variant the declaration and the
  // `renderNow.overrides.namedOverrides` seed share) lives here now, alongside
  // [PreviewOverrideDeclaration] — so this module needs no `:daemon:core` dependency and the daemon
  // no longer rides a preview's classpath just for the override value type. The daemon protocol
  // depends up into this module for the shared type instead.
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

kotlin {
  // `explicitApi()` — every declaration states its visibility, every public one its return type.
  // This module is a published contract an extracted preview server compiles against across a repo
  // boundary (#3824), so an implicitly-public declaration is an API decision nobody made.
  //
  // Everything here was already public by default and is already in a shipped ABI, so the
  // annotations preserve the existing surface rather than changing it — narrowing any of these to
  // `internal` would be a breaking change and is deliberately not part of this pass.
  explicitApi()

  // ABI dump gate, following `:rc-player-*` and `:daemon-client`. `checkKotlinAbi` diffs the real
  // public ABI against the committed dump in `api/`, so a surface change is a diff in review rather
  // than a downstream break. Regenerate with `./gradlew
  // :data-preview-overrides-core:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
