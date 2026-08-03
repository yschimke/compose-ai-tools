plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
  jvm("desktop")
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies { api(project(":rc-player-protocol")) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
