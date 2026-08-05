plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// `:rc-player-profile` — the process that turns the CMP player's trace spans into numbers.
//
// It is an *application*, not a library, and that distinction is the point: `androidx.tracing`
// documents `Tracer.setGlobalTracer` as something a library must never call, so the player modules
// only ever read `Tracer.global`. This module is the process that installs a driver, so it is the
// only place in the repo that registers one.
//
// It runs headless. `ImageComposeScene` rasterizes through skiko's software path with no `DISPLAY`
// (see docs/AGENTS.md), which is what lets `./gradlew :rc-player-profile:rcPlayerProfile` produce a
// real profile in CI and in an agent sandbox rather than only on a developer's desktop.

dependencies {
  implementation(project(":rc-player-compose"))
  implementation(project(":rc-player-protocol"))
  implementation(project(":rc-player-runtime"))
  implementation(project(":rc-player-trace"))

  // Compose Desktop supplies `ImageComposeScene` and skiko's software rasterizer.
  @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
  @Suppress("DEPRECATION") implementation(compose.runtime)
  @Suppress("DEPRECATION") implementation(compose.foundation)
  @Suppress("DEPRECATION") implementation(compose.ui)

  // androidx.tracing 2.x plus the wire driver that serializes spans as Perfetto `TracePacket`s.
  implementation(libs.androidx.tracing.kmp)
  implementation(libs.androidx.tracing.wire)

  implementation(project(":common-io"))
  implementation(libs.okio)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("rcPlayerProfile") {
  description = "Profile the CMP Remote Compose player over the four reference documents."
  group = "verification"
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ee.schimke.composeai.rcplayer.profile.RcProfileMainKt")
  // Not declared as task outputs on purpose: a profile is a measurement, not a build artifact, and
  // declaring it would make the task UP-TO-DATE on the second run — exactly when a re-measure is
  // what was asked for.
  args(layout.buildDirectory.dir("profile").get().asFile.absolutePath)
}
