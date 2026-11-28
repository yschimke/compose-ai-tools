// Host-side controller, virtual ADB/gRPC device, live reload, pool, paired launch, scenario runner,
// DWF runtime resolver, and host-natives provisioning for `android-simulator`.
//
// Self-contained: zero project dependencies on `:cli` or `:bundle-format`. Packages a standalone
// `android-simulator` distribution (`installDist` / `distTar`) with `lib-simulator/` and
// `lib-dex2jar/` staged alongside its `bin/` and `lib/`.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

application {
  applicationName = "android-simulator"
  mainClass.set("ee.schimke.composeai.cli.simulator.SimulatorMainKt")
}

val simulatorSidecar =
  configurations.create("simulatorSidecar") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

val simulatorDex2jar =
  configurations.create("simulatorDex2jar") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
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

  add("simulatorSidecar", project(":simulator-host"))
  addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
    "simulatorSidecar",
    libs.robolectricSimulator,
  ) {
    isTransitive = false
  }
  add("simulatorDex2jar", libs.dex2jar.tools)

  constraints {
    implementation("org.slf4j:slf4j-api") {
      version { strictly("2.0.17") }
      because("gradle-tooling-api strictly requires slf4j-api 2.0.17; ktor client pulls 2.0.18")
    }
  }

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

distributions {
  main {
    distributionBaseName.set("android-simulator")
    contents {
      into("lib-simulator") { from(simulatorSidecar) }
      into("lib-dex2jar") { from(simulatorDex2jar) }
    }
  }
}

tasks.named<Tar>("distTar") { compression = Compression.GZIP }
