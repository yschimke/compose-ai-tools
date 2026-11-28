// Host-side controller, virtual ADB/gRPC device, live reload, pool, paired launch, scenario runner,
// DWF runtime resolver, and host-natives provisioning for `compose-preview android-simulator`.
//
// Kept separate from `:cli` so the simulator's host orchestration has no compile dependency on
// preview discovery, bundle rendering, or the rest of the CLI; `:cli` wires in the few host
// provisioning callbacks through `SimulatorHostEnvironment`.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // The converted-APK cache, converter, simulator JVM command line and Data Layer hub, shared with
  // `:render-host`'s `AppSimulatorLauncher`.
  implementation(project(":simulator-launch"))
  // Sidecar lookup (`locateBundleSidecarJars`) and Robolectric JVM args (`AndroidBundleLaunch`).
  implementation(project(":bundle-format"))
  implementation(libs.composeai.common.io)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)
  implementation(libs.asm.commons)
  implementation("org.gradle:gradle-tooling-api:9.9.2")

  // `launch --adb`: the emulator gRPC service Android Studio streams the display and input over.
  // The artifact's own transport is unshaded Netty; the shaded one keeps Netty off the classpath.
  implementation(libs.android.emulator.proto) { exclude(group = "io.grpc", module = "grpc-netty") }
  implementation(libs.grpc.netty.shaded)
  // Its POM scopes these to runtime; the service implementation compiles against them.
  implementation(libs.grpc.stub)
  implementation(libs.grpc.protobuf)
  implementation(libs.protobuf.java)

  constraints {
    implementation("org.slf4j:slf4j-api") {
      version { strictly("2.0.17") }
      because(
        "gradle-tooling-api strictly requires slf4j-api 2.0.17; ktor client pulls 2.0.18",
      )
    }
  }

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}
