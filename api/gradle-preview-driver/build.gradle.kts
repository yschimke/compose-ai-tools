// The Gradle Tooling-API render pipeline (discover modules → run tasks → read manifests → build
// PreviewResults) as a published library, so external tooling can drive renders without `:cli`.
// Types keep the `ee.schimke.composeai.cli` package for source compatibility. CLI-specific concerns
// (change detection, `--force`, init-script injection) stay in `:cli`.

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
  api(libs.composeai.preview.data.api)

  // Okio-based file IO for the manifest read + PNG sha256 (see `PreviewResultBuilder` /
  // `PreviewSha256`). `implementation` — consumers don't need Okio on their compile classpath
  // just to call `render()`.
  implementation(libs.composeai.common.io)

  // Gradle Tooling API for the cross-process build drive. The version here mirrors what
  // `:cli` used to declare — bumping is a published-API concern, not a CLI one.
  api("org.gradle:gradle-tooling-api:9.8.0")

  // SLF4J no-op so the Tooling API doesn't warn about a missing impl. Pinned to the `slf4j-api`
  // version `gradle-tooling-api` strictly requires.
  runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  // The running Gradle distribution, so `DiscoverPreviewModulesIntegrationTest` can
  // `useInstallation` instead of downloading one (it skips when absent).
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
