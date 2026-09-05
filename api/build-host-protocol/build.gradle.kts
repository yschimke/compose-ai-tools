// The wire contract between a preview server and a Gradle build host process.
//
// See `BuildHostProtocol`'s KDoc for what it is and
// `docs/design/BUILD_HOST_PROTOCOL_PREVIEWMODULE.md` for why it is published from this repository
// rather than from compose-preview-contracts.
//
// Shape and framing only: no Gradle, no process spawning, no IO beyond turning a line into a
// message. The Tooling API lives in `:cli`, which implements this, and the server links only what
// is here. That is the whole point — a module a preview server can depend on without acquiring a
// build tool.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `PreviewModule` and `PreviewManifest` appear in this module's own public signatures:
  // `WireModule` converts to and from the former, and `WireModuleManifest` carries the latter
  // as-is. Reusing them rather than redeclaring them is the reason this module is here and not in
  // contracts — see the design doc.
  api(project(":preview-data-api"))
  api(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
}

kotlin {
  // A published contract two repositories compile against, so every declaration states its
  // visibility and every public one its return type.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin.
tasks.named("check") { dependsOn("checkKotlinAbi") }

composeAiMavenPublishing {
  coordinates(
    artifactId = "build-host-protocol",
    displayName = "Compose Preview — Build Host Protocol",
    description =
      "The wire contract between a Compose Preview server and a Gradle build host process: the " +
        "seven build operations a server needs, framed as newline-delimited JSON, so the server " +
        "can ask for Gradle work without linking the Gradle Tooling API.",
  )
  inceptionYear.set("2026")
}
