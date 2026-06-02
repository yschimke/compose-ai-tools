// In-process Compose Multiplatform Desktop backend for the render-session library.
//
// Hosts `:daemon:desktop`'s `runDaemon(...)` on a background thread, with the JSON-RPC transport
// wired to in-memory piped streams. The calling JVM holds the client end of those pipes via a
// `DaemonClient` and surfaces it as a `RenderSession` — same protocol, same wire format, no
// subprocess fork.
//
// **When to use this vs `:render-session-subprocess`**
//
// - **Embedded (this module):** the calling JVM gains the full Compose Desktop + Skiko + daemon
//   runtime at session-open time. Best for JUnit pixel-test rigs, IDE plugins, or any host that
//   already runs Compose Desktop and wants to avoid the JVM-fork startup cost (~1–2s saved per
//   session).
// - **Subprocess (`:render-session-subprocess`):** the calling JVM stays minimal. The renderer runs
//   in its own forked JVM with whatever JVM args the daemon launch descriptor prescribes. Best for
//   thin CLIs and tooling that doesn't want the runtime footprint, or that needs JVM args
//   (`--add-opens`, custom GC settings) that can't be applied to a running JVM.
//
// **Limitations**
//
// 1. The Android Robolectric backend has *no* embedded equivalent — the sandbox bootstrap is too
//    invasive. Calling `EmbeddedDesktopRenderSessions.open(...)` against an Android module fails
//    cleanly.
// 2. Multiple embedded sessions in the same JVM share system-property state — descriptor sysprops
//    (`composeai.daemon.previewsJsonPath`, history dir, etc.) are JVM-global. One session at a
//    time is safe; concurrent sessions against different modules need external coordination.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":render-session-api"))
  implementation(project(":common-io"))

  // The actual daemon entry point we run on a background thread, plus the JSON-RPC client we
  // wrap the calling end of the pipes with. `:render-session-subprocess` is depended on for
  // the shared `DaemonClientRenderSession` delegate (and the `NotificationFanout` helper) — the
  // class is transport-agnostic, just needs a `DaemonClient` and a `closeAction` lambda. The
  // subprocess module is currently the canonical home for the delegate; consumers stay light
  // because they pull in its single transport-shared file plus the API jar, not its factory.
  implementation(project(":render-session-subprocess"))
  implementation(project(":daemon:desktop"))
  implementation(project(":daemon:core"))
  implementation(project(":mcp"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-session-embedded-desktop",
    displayName = "Compose Preview — Embedded Desktop Render Session",
    description =
      "In-process Compose Multiplatform Desktop backend for the compose-preview render-session " +
        "API. Hosts the daemon's JSON-RPC server in the calling JVM via piped streams; no " +
        "subprocess fork. Pairs with :render-session-api.",
  )
  inceptionYear.set("2026")
}
