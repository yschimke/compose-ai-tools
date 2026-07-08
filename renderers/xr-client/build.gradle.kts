// JVM client for the native `xr-composite --serve` render server. The daemon's future XR
// RenderSession backend wraps this to spawn/multiplex the native process and
// proxy its frames to clients. Kept dependency-light: just kotlinx-serialization for the JSON-RPC
// payloads — the framing is hand-rolled over the process streams.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

// Published because `:daemon-core` (itself published to Maven Central) depends on it — a published
// module can't have an unpublished (`unspecified`) transitive dependency, or coordinate-based
// consumers (the bundle render path, sample builds) fail to resolve it.
composeAiMavenPublishing {
  coordinates(
    artifactId = "renderer-xr-client",
    displayName = "Compose Preview — XR Render Client",
    description =
      "JVM client for the native xr-composite --serve render server: the framed JSON-RPC " +
        "transport, binary resolution, and held-session management the daemon fronts for the " +
        "XR render service. Pre-1.0.",
  )
  inceptionYear.set("2026")
}
