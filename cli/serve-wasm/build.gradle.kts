plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("previewServer")
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":samples:design-catalog-m3-shared"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    wasmJsTest.dependencies { implementation(kotlin("test")) }
  }
}

// Keep the prototype webpack-free, matching the catalog and Remote Compose Wasm apps. This makes
// the output a plain static directory that `serve --wasm-dir preview-ui=<dir>` can host through the
// server's existing same-origin Wasm asset lane.
tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the experimental preview-server Wasm frontend."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  from(layout.buildDirectory.dir("processedResources/wasmJs/main")) {
    include("composeResources/**")
  }
  from(
    rootProject.layout.projectDirectory.file(
      "samples/cmp-wasm-catalog/src/wasmJsMain/resources/js-joda.esm.js"
    )
  )
  // The typefaces the native catalog lane composes with (#4821, ported from
  // compose-preview-server's `wasm-ui`). Staged from `:samples:cmp-wasm-catalog`'s own resources —
  // the same files that sample loads and the offline parity harness registers — rather than a
  // second vendored copy under `src/wasmJsMain/resources`, so the two can never drift onto
  // different outlines. `CatalogFonts.FONTS_BASE` fetches them from `fonts/`.
  //
  // Without these the lane fell back to the CMP bundled font while claiming to reproduce snapshots
  // the Android renderer rasterized with Roboto, so text metrics and wrapping differed in the
  // default lane. Upstream stages the byte-identical set from its own `assets/rc-fonts`; only the
  // source path differs, which is why `build.gradle.kts` is not one of the files
  // `check-serve-wasm-fork.py` compares.
  from(
    rootProject.layout.projectDirectory.dir(
      "samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts"
    )
  ) {
    include("*.ttf", "fonts.json", "*OFL.txt", "LICENSE.txt")
    into("fonts")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
