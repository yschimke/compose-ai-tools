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
  description = "Assemble and size-check the optimized CMP Remote Compose Wasm distribution."
  group = "distribution"
  dependsOn("wasmJsProductionExecutableCompileSync", "processSkikoRuntimeForKWasm")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/kotlin")) {
    exclude("*.map", "custom-formatters.js")
  }
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  from(
    rootProject.layout.projectDirectory.dir(
      "samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts"
    )
  ) {
    into("fonts")
  }
  from(
    rootProject.layout.projectDirectory.file(
      "samples/cmp-wasm-catalog/src/wasmJsMain/resources/js-joda.esm.js"
    )
  )
  into(layout.buildDirectory.dir("wasmDist"))
  // Ratchet, not a target: it exists to make an unintended size jump fail the build, so it should
  // only move when the growth is understood. Raised from 23_000_000 for the Compose Multiplatform
  // 1.10.3 -> 1.11.1 bump (#3447), which took the measured distribution to 23_236_608 bytes — about
  // +237 KB, ~1%. Nearly all of it is `skiko.wasm`: that bump crosses skiko 0.9.37.4 -> 0.144.6, a
  // large Skia jump (it is the release that added `org.jetbrains.skia.PathBuilder`), so a bigger
  // binary is expected rather than a leak. The value keeps roughly the same slack the old one did.
  inputs.property(
    "maximumDistributionBytes",
    providers.gradleProperty("rcPlayerWasmMaxBytes").orElse("23500000"),
  )
  doLast {
    val maximumBytes = inputs.properties.getValue("maximumDistributionBytes").toString().toLong()
    val distribution = destinationDir
    val actualBytes = distribution.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    check(actualBytes <= maximumBytes) {
      "CMP Remote Compose Wasm distribution is $actualBytes bytes; budget is $maximumBytes bytes " +
        "(override deliberately with -PrcPlayerWasmMaxBytes=<bytes>)"
    }
    logger.lifecycle("CMP Remote Compose Wasm distribution: $actualBytes / $maximumBytes bytes")
  }
}

tasks.register<Sync>("wasmPlayerTestDist") {
  description = "Assemble the Wasm player with an AndroidX-generated browser smoke fixture."
  group = "verification"
  dependsOn(
    "wasmPlayerDist",
    ":rc-player-compat-tests:generateBaselineFixture",
    ":rc-player-compat-tests:generateComponentValueFixture",
    ":rc-player-compat-tests:generateLayoutFixture",
    ":rc-player-compat-tests:generateScrollFixture",
  )
  from(layout.buildDirectory.dir("wasmDist"))
  from(
    project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-baseline.rc")
  )
  from(
    project(":rc-player-compat-tests")
      .layout
      .buildDirectory
      .file("fixtures/androidx-component-value.rc")
  )
  from(project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-layout.rc"))
  from(project(":rc-player-compat-tests").layout.buildDirectory.file("fixtures/androidx-scroll.rc"))
  into(layout.buildDirectory.dir("wasmTestDist"))
}
