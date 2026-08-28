// The daemon's wire protocol — the `@Serializable` request, response and notification shapes the
// JSON-RPC lane carries, plus the stream frame header and the two reason enums that ride on it.
//
// Extracted from `:daemon:core` for #3824. `:daemon:core` is the daemon *implementation* — the
// JSON-RPC server, the APNG/GIF/ffmpeg encoders, the sandbox lifecycle, incremental discovery,
// the history archive — and it is 653 public declarations. The preview server needs 46 of them,
// and all 46 are protocol shapes. While they lived in the same module, an extracted server's
// dependency floor was the whole daemon: to name a `RenderNowResult` you took the recording
// test generator and the XR session registry with it.
//
// So the split here is shape versus behaviour, not client versus server. Anything that only
// describes what crosses the wire lives here; anything that reads a file, opens a socket or
// computes a result stays in `:daemon:core`. `HistoryDataDelta` is the clearest case: the delta
// shape moved, while `HistoryDataDiff` — which reads two archived entries off disk to produce
// one — did not.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-protocol`. `:daemon:core`
// exposes it as `api`, so every existing consumer of `ee.schimke.composeai.daemon.protocol.*`
// keeps compiling unchanged; the package did not move, only the module boundary around it.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Each of these carries a type that appears in a protocol field, so each is `api` rather than
  // `implementation` — exactly the reasoning `:daemon:core` already applied when it held these
  // shapes. `SemanticsDelta` and `ThemeDelta` are `HistoryDataDelta` fields;
  // `PreviewOverrideValue` is a `PreviewOverrides.namedOverrides` value; the render pipeline's
  // descriptors and sampling policy appear on the render and extension messages.
  api(project(":data-render-core"))
  api(project(":data-layoutinspector-core"))
  api(project(":data-theme-core"))
  api(project(":data-preview-overrides-core"))

  // The shapes are `@Serializable` and consumers construct their own `Json {}` to encode them.
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-protocol",
    displayName = "Compose Preview — Daemon Wire Protocol",
    description =
      "The @Serializable request, response and notification shapes of the compose-preview " +
        "daemon's JSON-RPC lane, without the daemon implementation behind them.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — this is a published contract both halves of the #3824 split compile across a
  // repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
