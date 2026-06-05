// JVM client for the native `xr-composite --serve` render server. The daemon's future XR
// RenderSession backend (RENDERER_SERVICE RFC) wraps this to spawn/multiplex the native process and
// proxy its frames to clients. Kept dependency-light: just kotlinx-serialization for the JSON-RPC
// payloads — the framing is hand-rolled over the process streams.

plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}
