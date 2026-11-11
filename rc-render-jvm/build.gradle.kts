plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// The `compose-preview serve` **cmp-jvm** render worker and DWF (Declarative Watch Face) preview
// compiler/renderer: draws a captured Remote Compose `.rc` document to PNG or layered SVG through
// `rc-player-compose` (the CMP player), compiles `<WatchFace>` XML into `.rc` +
// `.remotecompose.json`
// sidecars (`DwfRenderMain`), and renders one document per process (`RcJvmRenderMain`) or many per
// process (`RcJvmRenderWorkerMain`, the pooled worker).
//
// Published as `ee.schimke.composeai:rc-render-jvm` so external consumers of the Gradle plugin can
// run `composePreviewRenderDwf`, and staged by `:cli` into `lib-rcjvm/` so the serve host and
// `compose-preview launch` spawn it as a subprocess off `lib-rcjvm/*` + Skiko natives — keeping
// Compose Desktop and Skiko natives off the CLI's own classpath.

dependencies {
  implementation(platform(libs.rcplayers.bom))
  implementation(libs.rcplayer.compose)
  implementation(libs.rcplayer.runtime)
  implementation(libs.rcplayer.protocol)
  implementation(libs.rcplayer.creation.dwf)

  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.composeai.daemon.protocol)
  implementation(libs.composeai.data.remotecompose.core)

  // `compose/figma-svg` export: the layout-inspector payload and the SVG writer the desktop daemon
  // uses for an ordinary `@Preview`, driven here over the player's own Compose tree.
  implementation(libs.composeai.data.layoutinspector.connector)
  implementation(libs.composeai.data.layoutinspector.core)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  @Suppress("DEPRECATION") testRuntimeOnly(compose.desktop.currentOs)
}

tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

publishing.publications.withType<MavenPublication>().configureEach {
  versionMapping { allVariants { fromResolutionResult() } }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "rc-render-jvm",
    displayName = "Compose AI Tools - Remote Compose JVM Renderer",
    description =
      "JVM Remote Compose player and Declarative Watch Face (DWF) compiler/renderer over rc-player-compose.",
  )
  inceptionYear.set("2026")
}
