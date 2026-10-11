// Public API for the render-session library: the renderer-agnostic `RenderSession` contract and its
// DTOs, with no renderer / Compose / Robolectric dependencies. Implemented by
// `:render-session-subprocess` (a daemon JVM over JSON-RPC) and the in-process embedded backend.
// Pre-1.0: may break across minor versions, though `RenderSession` itself evolves carefully.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Protocol types (`RenderTier`, `PreviewOverrides`, `FileKind`, …) referenced by the contract are
  // re-exposed from `:daemon-protocol` — not `:daemon:core`, which would put the whole daemon
  // implementation on this contract's compile ABI.
  api(libs.composeai.daemon.protocol)

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
  // `explicitApi()`: this is a published contract compiled against across a repo boundary, so every
  // public declaration should be deliberate.
  explicitApi()

  // ABI dump gate, following `:rc-player-*` and `:daemon-client`. `checkKotlinAbi` diffs the real
  // public ABI against the committed dump in `api/`, so a surface change is a diff in review rather
  // than a downstream break. Regenerate with `./gradlew :render-session-api:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
