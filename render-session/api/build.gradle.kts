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
  //
  // `:daemon-protocol`, NOT `:daemon:core`. Every one of the 24 types this module's public
  // surface names is declared in the protocol module; none comes from the daemon implementation.
  // Depending on `:daemon:core` therefore put 653 public declarations — the JSON-RPC server, the
  // encoders, the sandbox lifecycle, the history archive — on the compile ABI of a contract that
  // needs none of them, which is what kept this module out of the extracted contracts repository
  // (compose-ai-tools#4732: "publishing it from there would publish the daemon from there").
  //
  // Package is not module: these types all live in the `…daemon.protocol` package, but that alone
  // proved nothing — `docs/design/PREVIEW_SERVER_SPLIT.md` records the same assumption being wrong
  // elsewhere. Each declaration site was checked before this narrowed.
  api(project(":daemon-protocol"))

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
  // than a downstream break. Regenerate with `./gradlew :render-session-api:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
