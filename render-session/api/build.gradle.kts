// Public API for the compose-preview render-session library.
//
// This module is the renderer-agnostic interface third-party tooling compiles against:
// the `RenderSession` contract, supporting DTOs, and capability descriptions. It carries no
// renderer / Compose / Robolectric dependencies and is safe to put on any JVM classpath.
//
// Two implementations live alongside it:
//   - `:render-session-subprocess` — spawns a daemon JVM via the launch descriptor written by the
//     gradle plugin's `composePreviewDaemonStart` task, drives it over JSON-RPC, and surfaces the
//     `RenderSession` contract. Works on any JVM with a JDK on the host.
//   - `:render-session-embedded` (future) — drives the renderer in-process. Requires the calling
//     JVM to host the full Robolectric + AGP + Compose classpath; viable mostly inside JUnit test
//     runners. Tracked separately.
//
// Pre-1.0; API may break across minor versions while consumers stabilise. The `RenderSession`
// interface itself is the stable contract we evolve carefully.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Protocol message types (`RenderTier`, `PreviewOverrides`, `FileKind`, etc.) are re-exposed
  // through this module's API. Consumers see them as `ee.schimke.composeai.daemon.protocol.*`
  // and the `RenderSession` contract references them directly — no duplicate DTOs in this jar.
  api(project(":daemon:core"))

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-session-api",
    displayName = "Compose Preview — Render Session API",
    description =
      "Public, renderer-agnostic API for driving compose-preview render sessions from third-party " +
        "tooling. Pre-1.0; pair with :render-session-subprocess (or future embedded backend).",
  )
  inceptionYear.set("2026")
}
