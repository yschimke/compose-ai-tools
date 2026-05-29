// Published wire-format DTOs for the compose-preview CLI / driver / scripting surface.
//
// Carves out of `:cli` so external consumers (contrib scripting, third-party tooling, future
// MCP integrations) can compile against just the data shapes without dragging in `:cli`'s Gradle
// Tooling API + scripting closure. The eventual `:gradle-preview-driver` (step B of the
// clean-API carve-out) consumes this module too — the driver returns `PreviewResult` lists, the
// CLI formats them, the contrib scripting binary decodes extension payloads off them.
//
// Package note: types live in `ee.schimke.composeai.cli` for source-compatibility — they were in
// `:cli` before the extraction. Same pattern as `:data-a11y-core` keeping the
// `ee.schimke.composeai.renderer` package for the D2.2 extraction. Existing importers don't
// change; only the resolved module on Maven Central changes.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Published wire format — `@Serializable` annotations + `JsonElement` extension payloads.
  // `api` so downstream consumers (`:cli`, contrib scripting) get kotlinx-serialization on
  // their compile classpath without re-declaring it.
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "preview-data-api",
    displayName = "Compose Preview — Data API",
    description =
      "Published wire-format DTOs for the compose-preview render pipeline: PreviewResult, " +
        "PreviewManifest, the dataExtensions extension-payload carrier, and the v1 a11y mirror " +
        "types. Consumed by the CLI, contrib scripting, and any third-party tool that reads " +
        "compose-preview's JSON outputs.",
  )
  inceptionYear.set("2026")
}
