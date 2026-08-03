plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":rc-player-protocol"))
  testImplementation(project(":rc-player-runtime"))
  implementation(libs.compose.remote.core)
  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

tasks.register<JavaExec>("generateBaselineFixture") {
  description = "Generate a baseline .rc document using the authoritative AndroidX writer."
  group = "verification"
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ee.schimke.composeai.rcplayer.compat.GenerateBaselineFixtureKt")
  args(layout.buildDirectory.file("fixtures/androidx-baseline.rc").get().asFile.absolutePath)
  outputs.file(layout.buildDirectory.file("fixtures/androidx-baseline.rc"))
}

tasks.register<JavaExec>("generateLayoutFixture") {
  description = "Generate a layout .rc document using the authoritative AndroidX writer."
  group = "verification"
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ee.schimke.composeai.rcplayer.compat.GenerateLayoutFixtureKt")
  args(layout.buildDirectory.file("fixtures/androidx-layout.rc").get().asFile.absolutePath)
  outputs.file(layout.buildDirectory.file("fixtures/androidx-layout.rc"))
}
