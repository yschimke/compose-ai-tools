// Subprocess-backed implementation of the public render-session library.
//
// Spawns the preview daemon JVM via the launch descriptor written by the gradle plugin's
// `composePreviewDaemonStart` task, drives it over Content-Length-framed JSON-RPC over stdio,
// and exposes the result as a `RenderSession`. This is the default backend — works on any JVM
// with a JDK on the host machine, no Robolectric / AGP / Compose deps required at the call site.
//
// Builds on `:mcp`'s existing `DaemonClient` + `SubprocessDaemonClientFactory` rather than
// re-implementing the JSON-RPC transport. The MCP server keeps consuming those types directly;
// the public render-session API is the supported surface for third-party tooling.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":render-session-api"))
  implementation(project(":common-io"))

  // Existing JSON-RPC client + subprocess spawn infrastructure. Public surface here is the
  // `RenderSession` contract; `:mcp` types stay internal to this module.
  implementation(project(":mcp"))
  implementation(project(":daemon:core"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

// `NonGradleContractTest` (see src/test/.../subprocess/NonGradleContractTest.kt) synthesises a
// fresh `daemon-launch.json` from `:samples:cmp`'s build outputs and drives a real RenderSession
// against it. The test self-skips when those outputs are missing, so devs running
// `./gradlew :render-session-subprocess:test` against a clean tree don't see a hard failure; CI
// (and anyone targeting `check`) pre-builds the inputs.
//
// `:samples:cmp:composePreviewDiscover` produces `previews.json`;
// `:samples:cmp:composePreviewDaemonStart`
// produces the descriptor we treat as a *parts list* (the test re-emits its own descriptor with
// rearranged paths — the goal is to prove the schema is a contract, not to copy the file).
tasks.named<Test>("test") {
  dependsOn(":samples:cmp:composePreviewDiscover", ":samples:cmp:composePreviewDaemonStart")
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-session-subprocess",
    displayName = "Compose Preview — Subprocess Render Session",
    description =
      "Daemon-subprocess-backed implementation of the compose-preview render-session API. Spawns " +
        "a daemon JVM per session, drives it via JSON-RPC, presents the result as a RenderSession.",
  )
  inceptionYear.set("2026")
}
