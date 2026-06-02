// Suspend + Dispatchers.IO wrappers over `:common-io`'s Okio FileSystem.
//
// Split out of `:common-io` so the sync Okio foundation stays coroutines-free (it has to be
// loadable
// on the render subprocess classpath without a kotlinx-coroutines version skew — see
// RENDERER_COMPATIBILITY.md). Only async consumers (e.g. `:bundle-viewer`, whose bundle load runs
// in
// a Compose `LaunchedEffect`) depend on this; everything else uses `:common-io` + Okio's blocking
// `read {}` / `write {}` directly.
//
// Same `ee.schimke.composeai.io` package as `:common-io`, so callers' imports don't care which
// module a helper lives in.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `api` so consumers get `SystemFileSystem` / `okio.Path`, the coroutines types the suspend
  // helpers expose, and the kotlinx-serialization Json the JSON helpers take.
  api(project(":common-io"))
  api(libs.okio)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "common-io-suspend",
    displayName = "Compose Preview — Common IO (suspend)",
    description =
      "Suspend file/IO helpers over :common-io's Okio FileSystem that run blocking disk access on " +
        "Dispatchers.IO. Split from :common-io so the sync foundation stays coroutines-free for the " +
        "render subprocess classpath.",
  )
  inceptionYear.set("2026")
}
