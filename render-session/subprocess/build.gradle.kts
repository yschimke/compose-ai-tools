// Subprocess-backed implementation of the public render-session library: spawns the preview daemon
// from the plugin's `composePreviewDaemonStart` launch descriptor and drives it over
// Content-Length-framed JSON-RPC on stdio. The default backend; callers need no Robolectric / AGP /
// Compose deps.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":render-session-api"))

  // `api`: Okio's `FileSystem` appears in `SubprocessRenderSessions.open`'s public signature, so it
  // must be on consumers' compile classpath.
  api(libs.composeai.common.io)

  // The JSON-RPC client and subprocess spawning; client types stay internal to this module.
  implementation(libs.composeai.daemon.client)
  implementation(libs.composeai.daemon.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)

  // `DescriptorSchemaVersionTest` opens a session against a descriptor that only exists in memory,
  // which is what the defaulted `fileSystem` parameter on `open` is for — see its KDoc.
  testImplementation(libs.okio.fakefilesystem)
}

// `NonGradleContractTest` rebuilds a `daemon-launch.json` from `:samples:cmp`'s outputs
// (`composePreviewDiscover`, `composePreviewDaemonStart`) and drives a real session. It self-skips
// when they're missing; CI and `check` pre-build them.
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
  // `explicitApi()`: this is a published contract compiled against across a repo boundary, so every
  // public declaration should be deliberate.
  explicitApi()

  // ABI dump gate: `checkKotlinAbi` diffs the public ABI against `api/`. Regenerate with `./gradlew
  // :render-session-subprocess:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
