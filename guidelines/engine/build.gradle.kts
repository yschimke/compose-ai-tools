// The design-guidelines engine: asks a model whether rendered @Previews follow a catalog's design
// guidance (`ui-builder.guidelines.json`), in batches, with an evidence loop and a render-hash
// cache.
//
// Layer 1 (docs/design/REPOSITORY_LAYERS.md): behaviour over contract types. It sends outbound
// HTTP (OpenRouter's chat completions and decisions endpoints) and opens no socket of its own; it
// spawns no daemon and reads no Gradle project. What a host can render or fetch is behind
// `GuidelineEvidenceHost`, which the CLI implements over its render session today and the MCP
// server and the VS Code extension can implement over theirs.
//
// The wire shapes are compose-preview-contracts' `design-guidelines-protocol` (layer 0), the same
// ones compose-ui-builder and compose-preview-server exchange for UI-builder designs, so a record
// written here reads anywhere a design's does.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Published and consumed out of repository (the MCP server, the VS Code extension), so every
  // public declaration is a decision someone made; regenerate the dump with
  // `./gradlew :design-guidelines:updateKotlinAbi`.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

tasks.named("check") { dependsOn("checkKotlinAbi") }

dependencies {
  // Contract types are on the public surface (requests, records, verdicts), so `api`.
  api(libs.composeai.design.guidelines.protocol)
  api(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "design-guidelines",
    displayName = "Compose Preview — Design Guidelines",
    description =
      "Checks rendered @Preview functions against a catalog's design guidelines " +
        "(ui-builder.guidelines.json) through OpenRouter: batched requests, a Jev evidence " +
        "triage, an evidence loop for what a first pass could not decide, and a cache keyed by " +
        "render hash.",
  )
  inceptionYear.set("2026")
}

// GeneralGuidelinesPackTest reads the shared pack, so an edit to it re-runs the test.
tasks.withType<Test>().configureEach {
  inputs.dir("../packs").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("packs")
  systemProperty("guidelines.packs.dir", file("../packs").absolutePath)
}
