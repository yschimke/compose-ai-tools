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
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Published wire-format DTOs — the driver returns `List<PreviewResult>` keyed by
  // `PreviewManifest`. `api` so downstream consumers (CLI, contrib scripting) see the
  // DTOs transitively.
  api(project(":preview-data-api"))

  // Okio-based file IO for the manifest read + PNG sha256 (see `PreviewResultBuilder` /
  // `PreviewSha256`). `implementation` — consumers don't need Okio on their compile classpath
  // just to call `render()`.
  implementation(project(":common-io"))

  // Gradle Tooling API for the cross-process build drive. The version here mirrors what
  // `:cli` used to declare — bumping is a published-API concern, not a CLI one.
  api("org.gradle:gradle-tooling-api:9.6.0")

  // SLF4J no-op shipped alongside so the Tooling API doesn't complain about a missing impl
  // when a CLI / consumer hasn't already wired one up. Pinned to the version that
  // `gradle-tooling-api` strictly requires on `slf4j-api` (currently 2.0.17) — bumping the
  // `-nop` impl ahead of that drags in a newer `slf4j-api` and trips the strict-constraint
  // resolution.
  runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  // The unpacked Gradle distribution this build runs from. `DiscoverPreviewModulesIntegrationTest`
  // points a real Tooling-API connection at it via `useInstallation(...)` so it reuses the
  // already-present distribution instead of downloading one (the test `assumeTrue`s out when this
  // is absent — e.g. a bare IDE run). Read at configuration time so the configuration cache
  // captures it.
  systemProperty("composeai.test.gradleHome", gradle.gradleHomeDir?.absolutePath ?: "")
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
