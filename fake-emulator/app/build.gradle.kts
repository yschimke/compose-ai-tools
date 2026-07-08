plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  application
}

// Runnable launcher for the fake Android emulator. Wires the ADB core + the emulator gRPC service +
// a RenderSession-backed FrameSource so a launched @Preview becomes the emulator display, and
// writes
// the Studio discovery file. Unpublished tooling. See docs/fake-emulator/README.md.
dependencies {
  implementation(project(":fake-emulator-core"))
  implementation(project(":fake-emulator-grpc"))
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))
  implementation(project(":daemon:core"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okio)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

application {
  applicationName = "fake-emulator"
  mainClass.set("ee.schimke.composeai.fakeemulator.app.MainKt")
}
