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

  // `api`, not `implementation`: `SubprocessRenderSessions.open` takes a defaulted
  // `fileSystem: okio.FileSystem`, so Okio is part of this module's PUBLIC signature. `:common-io`
  // exposes it with `api(libs.okio)` for exactly this reason, but an `implementation` edge keeps it
  // off the consumer's COMPILE classpath — Gradle publishes it as a runtime dependency — so an
  // external caller could not name the parameter without depending on Okio itself.
  api(libs.composeai.common.io)

  // JSON-RPC client + subprocess spawn infrastructure. Public surface here is the `RenderSession`
  // contract; the client types stay internal to this module.
  //
  // This used to be `implementation(project(":mcp"))`, which is what made consuming the
  // render-session library drag an MCP server onto the classpath — the last leak the preview-server
  // contract probe recorded. #3824 item 3 lifted the transport into `:daemon-client`; nothing here
  // needed the MCP server, only the wire client.
  implementation(project(":daemon-client"))
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
  // than a downstream break. Regenerate with `./gradlew
  // :render-session-subprocess:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
