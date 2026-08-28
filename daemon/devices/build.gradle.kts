// The device catalog both ends of the render lane resolve against — screen geometry for the
// `spec:` strings a `@Preview` carries, plus the frame-orientation arithmetic over it.
//
// Split out of `:daemon:core` for #3824. Serve builds its device menu from this catalog so the
// menu matches what the backend will actually render at; while it lived in the daemon
// implementation, an extracted preview server took the JSON-RPC server, the encoders and the
// history archive along with it just to name a screen size.
//
// It is not in `:daemon-protocol` on purpose. That module holds shapes — what crosses the wire —
// and `DeviceDimensions.resolve` computes a result from a table. The precedent here is
// `:data-pseudolocale-core`: a pure table with no renderer behind it, where the alternative is
// each side keeping its own copy and drifting. That drift is not hypothetical — the catalog is
// duplicated in `:gradle-plugin`'s discovery pass, and `DeviceDimensionsCatalogDriftTest` exists
// because the two copies did drift once.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-devices`. The package is
// unchanged (`ee.schimke.composeai.daemon.devices`) and `:daemon:core` exposes this module as
// `api`, so nothing downstream changes — no import edit, no binary name moved.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // `FrameOrientation` resolves against the protocol's `Orientation`, so the type is on this
  // module's compile ABI — `api`, not `implementation`.
  api(project(":daemon-protocol"))

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-devices",
    displayName = "Compose Preview — Device Catalog",
    description =
      "Screen geometry for the devices a @Preview can name, and the frame-orientation " +
        "arithmetic over them, shared by the preview server and the render backend.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — this is a published contract both halves of the #3824 split compile against
  // across a repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
