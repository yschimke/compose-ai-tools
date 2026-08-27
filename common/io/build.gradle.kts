// Okio-based file/IO foundation for the whole (non-Gradle) codebase.
//
// Synchronous and deliberately coroutines-free: every production module funnels file reads/writes
// through Okio's `FileSystem` (the `SystemFileSystem` indirection here plus Okio's own `read {}` /
// `write {}` blocking helpers), and this module must stay loadable on the *render subprocess*
// classpath without dragging kotlinx-coroutines onto it — a coroutines version skew there breaks
// Compose rendering (see RENDERER_COMPATIBILITY.md). The rare caller that needs blocking IO off a
// UI thread does its own `withContext` / `runBlocking` at the call site (e.g. `:bundle-viewer`).
//
// Published because most consumers (`:daemon:core`, `:mcp`, the data connectors) are themselves
// published and put `SystemFileSystem` / `okio.Path` on their compile classpath.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // `api` so downstream modules get Okio's `Path` / `FileSystem` without re-declaring it.
  api(libs.okio)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "common-io",
    displayName = "Compose Preview — Common IO",
    description =
      "Okio-based file/IO foundation for the compose-preview tooling: a single synchronous " +
        "FileSystem indirection. Coroutines-free so it stays safe on the render subprocess " +
        "classpath.",
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
  // than a downstream break. Regenerate with `./gradlew :common-io:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
