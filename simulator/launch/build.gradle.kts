// What launching a simulator JVM takes, shared by `compose-preview android-simulator`
// (`:simulator-cli`) and hosts that launch simulators themselves (`AppSimulatorLauncher` in
// `:render-host`, which the preview server links): the converted-APK cache, the converter's
// invocation, the simulator JVM's command line, and the Wearable Data Layer hub that pairs two.
//
// Kept apart from `:simulator-cli` because that module carries the Gradle Tooling API, gRPC and
// Ktor for live reload and the virtual adb device, none of which a published library may drag onto
// a server's classpath. This one needs the JDK, kotlinx-serialization and the shared cache dir.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(libs.composeai.common.io)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "simulator-launch",
    displayName = "Compose Preview — Simulator Launch",
    description =
      "The converted-APK cache, converter invocation, simulator JVM command line and Wearable " +
        "Data Layer hub for launching compose-preview's Robolectric simulator.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // A published contract `:render-host` and `:simulator-cli` both compile against.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin; wire it so the gate runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
