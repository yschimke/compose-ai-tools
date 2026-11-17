plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Watch Face Format (WFF / Declarative Watch Face) -> Remote Compose translator, as a library.
//
// `WffRemoteCompose.compile(xml, resources, options)` turns `<WatchFace>` XML plus its `res/` tree
// into serialized `.rc` bytes, the named values the document reads (so a host can drive time-of-day
// aside, ambient, complications, flavor, configuration and sensors without recompiling), and
// diagnostics. It never throws for a bad input: see `docs/DWF_REMOTE_COMPOSE.md`.
//
// This module is the *compiler only*. It has no player, no Skiko window and no daemon protocol:
// the player (`rc-player-compose`), the render daemon and the CLI entry points stay in
// `:rc-render-jvm`, which depends on this module. Layer 1 (docs/design/REPOSITORY_LAYERS.md): it
// opens no socket; the UI builder host in compose-preview-server consumes it as a published
// coordinate.

kotlin {
  // `explicitApi()` and the ABI dump gate, following `:render-matrix`. Only the facade package
  // (`ee.schimke.composeai.dwf`) is the stable contract; the translator internals it grew out of
  // (`ee.schimke.composeai.rccreation.dwf`, `ee.schimke.composeai.rcjvm`) stay public because
  // `:rc-render-jvm` and the Gradle plugin's DWF task link against them, but are not pinned.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
  abiValidation { filters { include { byNames.add("ee.schimke.composeai.dwf.**") } } }
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin; wire it explicitly.
tasks.named("check") { dependsOn("checkKotlinAbi") }

dependencies {
  api(platform(libs.rcplayers.bom))
  // `RcDocument` / `RcDocumentCodec` are on the moved translator's public surface.
  api(libs.rcplayer.protocol)
  implementation(libs.rcplayer.creation.compose)
  // `ImageBitmap` is on `DwfResourceResolver`'s surface; Skia (via compose-ui on the JVM) decodes
  // the resource PNGs.
  api(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  @Suppress("DEPRECATION") testRuntimeOnly(compose.desktop.currentOs)
}

// The Wear OS sample watch faces live beside the renderer's tests; compiled here through the new
// API so the library is exercised on every sample without duplicating the fixtures.
val wearOsSamples =
  layout.projectDirectory.dir("../rc-render-jvm/src/test/resources/wear-os-samples")

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  inputs.dir(wearOsSamples).withPropertyName("wearOsSamples")
  systemProperty("dwf.wearOsSamplesDir", wearOsSamples.asFile.absolutePath)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "dwf-remote-compose",
    displayName = "Compose AI Tools - Watch Face Format to Remote Compose",
    description =
      "Compiles Watch Face Format (Declarative Watch Face) XML and its resources into Remote Compose .rc documents, with the named values the document reads.",
  )
  inceptionYear.set("2026")
}
