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
