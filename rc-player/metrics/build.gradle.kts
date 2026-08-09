plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

// `:rc-player-metrics` — the fixture generator for the text-metrics harness (issue #3595).
//
// It builds Remote Compose documents that measure their own text with `TextMeasure` and draw the
// answers as guide lines, so every player lane renders *its own* metrics. There is deliberately no
// renderer here: the output is `<id>.rc` + `manifest.json` in the shape the existing harnesses
// already read (`RcViewPlayerRenderHarness` for the Java reference lane, `RcEmbeddedRenderHarness`,
// `RcJvmRenderHarness`, and `rc-compare`'s staged inputs), which is what keeps a fixture change
// from
// touching five render paths.

dependencies {
  implementation(project(":rc-player-protocol"))
  implementation(project(":rc-player-runtime"))

  testImplementation(project(":rc-player-runtime"))
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("rcTextMetricFixtures") {
  description = "Generate the text-metric .rc fixtures and their manifest."
  group = "build"
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ee.schimke.composeai.rcplayer.metrics.RcTextMetricsMainKt")
  args(layout.buildDirectory.dir("fixtures").get().asFile.absolutePath)
  outputs.dir(layout.buildDirectory.dir("fixtures"))
}
