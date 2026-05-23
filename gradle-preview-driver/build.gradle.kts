// Gradle Tooling-API render pipeline as a published library.
//
// Step B of the clean-API carve-out (issue #1084): the discover-modules → run-tasks →
// read-manifests → build-base-PreviewResults pipeline that previously lived inside `:cli`'s
// `Command` base class moves here so external consumers (contrib scripting, third-party
// tooling, future MCP integrations) can drive renders without depending on `:cli`.
//
// Package note: types live in `ee.schimke.composeai.cli` for source-compat — they were in
// `:cli` before the extraction. Same pattern `:data-a11y-core` + `:preview-data-api` used.
//
// Boundary: this module owns the Gradle Tooling-API wrapping and the PNG-sha-+-manifest pass.
// CLI-specific concerns (`.cli-state.json` change detection, image-size override for hosting
// agents, `--force` stderr notices, autoinject init-script synthesis) stay in `:cli` as
// layers on top of the driver's output.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Published wire-format DTOs — the driver returns `List<PreviewResult>` keyed by
  // `PreviewManifest`. `api` so downstream consumers (CLI, contrib scripting) see the
  // DTOs transitively.
  api(project(":preview-data-api"))

  // Gradle Tooling API for the cross-process build drive. The version here mirrors what
  // `:cli` used to declare — bumping is a published-API concern, not a CLI one.
  api("org.gradle:gradle-tooling-api:9.5.1")

  // SLF4J no-op shipped alongside so the Tooling API doesn't complain about a missing impl
  // when a CLI / consumer hasn't already wired one up.
  runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "gradle-preview-driver",
    displayName = "Compose Preview — Gradle Driver",
    description =
      "Gradle Tooling-API render pipeline as a library. Discover preview modules, run " +
        "composePreviewRenderAll, read result manifests, and build base PreviewResult objects " +
        "with PNG sha256s populated. Consumed by the CLI and by contrib scripting; lets any " +
        "tool drive a render without baking the Tooling-API dance into itself.",
  )
  inceptionYear.set("2026")
}
