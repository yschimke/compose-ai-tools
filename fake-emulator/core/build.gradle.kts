plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

// Unpublished tooling module (like :daemon:harness) — a fake Android emulator's ADB transport,
// console, screencap, the `am start … PreviewActivity` preview-launch parser, and the
// FrameSource / PreviewLauncher SPIs. Deliberately dependency-light (coroutines + okio only) and
// free of the render classpath + protobuf/grpc toolchain so its `dadb` tests run fast and
// hermetically. See docs/fake-emulator/README.md.
dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okio)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  // dadb speaks the device transport protocol directly to our fake adbd — no real `adb` / device.
  testImplementation(libs.dadb)
  testImplementation(libs.kotlinx.coroutines.core)
}
