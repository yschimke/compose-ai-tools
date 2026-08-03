plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("rcPlayer")
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":rc-player-compose"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}

tasks.register<Sync>("wasmPlayerDist") {
  description = "Assemble the webpack-free CMP Remote Compose Wasm player distribution."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  from(
    rootProject.layout.projectDirectory.file(
      "samples/cmp-wasm-catalog/src/wasmJsMain/resources/js-joda.esm.js"
    )
  )
  into(layout.buildDirectory.dir("wasmDist"))
}

tasks.register<Sync>("wasmPlayerTestDist") {
  description = "Assemble the Wasm player with an AndroidX-generated browser smoke fixture."
  group = "verification"
  dependsOn(
    "wasmPlayerDist",
    ":rc-player-compat-tests:generateBaselineFixture",
    ":rc-player-compat-tests:generateLayoutFixture",
    ":rc-player-compat-tests:generateScrollFixture",
  )
  from(layout.buildDirectory.dir("wasmDist"))
  from(
    project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-baseline.rc")
  )
  from(project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-layout.rc"))
  from(project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-scroll.rc"))
  into(layout.buildDirectory.dir("wasmTestDist"))
}
