// Okio-based file/IO foundation for the whole (non-Gradle) codebase.
//
// Every production module funnels its file reads/writes through the suspend helpers here rather
// than `java.io.File` + `readText()` / `writeText()`. The point is twofold: a single Okio
// `FileSystem` indirection (so tests can swap a `FakeFileSystem`), and a single place where
// blocking
// disk access hops onto `Dispatchers.IO`. Published because most consumers (`:daemon:core`,
// `:mcp`, the data connectors) are themselves published and put these helpers on their compile
// classpath.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `api` so downstream modules get Okio's `Path` / `FileSystem` and the coroutines + JSON types
  // these helpers expose without re-declaring them.
  api(libs.okio)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "common-io",
    displayName = "Compose Preview — Common IO",
    description =
      "Okio-based file/IO foundation for the compose-preview tooling: a single FileSystem " +
        "indirection plus suspend read/write helpers that run blocking disk access on " +
        "Dispatchers.IO.",
  )
  inceptionYear.set("2026")
}
