plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// The `compose-preview serve` **cmp-jvm** render worker: draws a captured Remote Compose `.rc`
// document to PNG or layered SVG through `rc-player-compose` (the CMP player), one document per
// process (`RcJvmRenderMain`) or many per process (`RcJvmRenderWorkerMain`, the pooled worker).
//
// This replaces the desktop-JVM cut of the vendored AndroidX embedded player
// (`third-party-rc-embedded-player-jvm`), which yschimke/rc-players stopped publishing in 2.0.0
// ("consumers that need a JVM Remote Compose player should depend on rc-player-compose"). Only the
// player underneath changed: the argv, the seed file, the pooled frame protocol and the
// `compose/figma-svg` export keep their shapes, so `RcJvmServerRenderer` and `RcJvmWorkerPool` in
// `:render-host` change a class name and nothing else.
//
// Not published. `:cli` stages this module's runtime classpath into `lib-rcjvm/`, and the serve
// host
// spawns it as a subprocess off `lib-rcjvm/*` + `lib-daemon-desktop/*` — the same isolation the
// desktop renderer uses, so Compose Desktop and Skiko natives stay off the CLI's own classpath.

dependencies {
  implementation(platform(libs.rcplayers.bom))
  implementation(libs.rcplayer.compose)
  implementation(libs.rcplayer.runtime)
  implementation(libs.rcplayer.protocol)

  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.kotlinx.coroutines.core)

  // `compose/figma-svg` export: the layout-inspector payload and the SVG writer the desktop daemon
  // uses for an ordinary `@Preview`, driven here over the player's own Compose tree.
  implementation(libs.composeai.data.layoutinspector.connector)
  implementation(libs.composeai.data.layoutinspector.core)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  @Suppress("DEPRECATION") testRuntimeOnly(compose.desktop.currentOs)
}

tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
