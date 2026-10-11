// `:samples:cmp-wasm-catalog` — the in-browser CMP tier of the public preview server: a `wasmJs`
// app that mounts the shared M3 catalog component named by `?id=` via `ComposeViewport`. It runs in
// the browser sandbox, so even an unverified session is safe. Kept thin (compose runtime +
// `material3`) for the smallest skiko bundle.
plugins {
  id("composeai.base-conventions")
  // Applied by id without a version, as in `:samples:cmp-shared`.
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    // Fixed entrypoint name so the committed `index.html` can reference
    // `composeApp.mjs` regardless of the gradle module path.
    outputModuleName.set("composeApp")
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      // The authoritative M3 component set (its `wasmJs` variant) — the same
      // composables the desktop `:samples:design-catalog-m3` catalog renders, so
      // the in-browser tier and the baked sticker sheet never drift.
      implementation(project(":samples:design-catalog-m3-shared"))
      // The string `compose.*` accessors are deprecated in CMP 1.10, but the replacement
      // coordinates aren't reliably published yet.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
    }
  }
}

// A static, webpack-free distribution servable from disk: the raw Kotlin/Wasm ES-module output,
// skiko runtime and the committed `index.html` (import-mapping `@js-joda/core`). Avoids the Node /
// Yarn / Binaryen toolchain, which `FAIL_ON_PROJECT_REPOS` rejects. Uses the unoptimized
// development executable. Output: `build/wasmDist/`, the preview server's `web/wasm/` for
// `compose-m3`.
tasks.register<Sync>("wasmCatalogDist") {
  description = "Assemble the webpack-free CMP Wasm catalog distribution (build/wasmDist)."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  // Compose Resources (`stringResource` labels from the shared module), laid out as the runtime's
  // `./composeResources/…cvr` fetch expects. Without them the first `stringResource` throws and the
  // catalog renders blank.
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("processedResources/wasmJs/main")) {
    include("composeResources/**")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
    include("index.html", "js-joda.esm.js", "fonts/**")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
