// `:samples:cmp-wasm-catalog` — the **in-browser CMP tier** of the public
// preview server (Workstream C / model 1).
//
// A Compose Multiplatform `wasmJs` app that holds the M3 catalog component
// composables in `commonMain` (CMP `material3`, no Android `@Preview` tooling)
// and mounts the one named by the `?id=` query parameter via `ComposeViewport`.
// It renders the *published* design catalogs (`design-artifacts/<system>`)
// client-side, in the browser sandbox, with no server round-trip — so even an
// unverified session is safe to run (execution is client-side, not on our box).
//
// Deliberately thin: only the multiplatform compose runtime + `material3`, so
// `wasmJsBrowserDistribution` produces the smallest skiko-backed bundle. The
// Android design-catalog modules can't compile to `wasmJs` (Android-only
// `@Preview` / `Configuration` / `wear.compose`), so the M3 component set is
// re-authored here against the CMP `material3` artifact — same components, same
// ids the catalog uses, a plain id→composable registry instead of `@Preview`.
plugins {
  id("composeai.base-conventions")
  // Apply KGP-multiplatform + the compose-compiler plugin by id (no version):
  // they're already on the buildscript classpath via the AGP/Compose bundle, so
  // `alias(libs.plugins…)` would error with "already on the classpath with an
  // unknown version" (see the sibling `:samples:cmp-shared`).
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
      // The string-typed `compose.*` accessors are deprecated in CMP 1.10 in
      // favour of explicit coords, but the renamed coords aren't reliably
      // published to every mirror yet — mirror `:samples:cmp-shared` and accept
      // the deprecation warning.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
    }
  }
}

// Assemble a static, **webpack-free** distribution servable straight from disk:
// the raw Kotlin/Wasm ES-module output plus the skiko runtime and the committed
// `index.html` (which import-maps `@js-joda/core`). This avoids the Node / Yarn
// / Binaryen download toolchain the Kotlin JS/Wasm plugins want — which the
// build's `FAIL_ON_PROJECT_REPOS` mode rejects — so the bundle builds in CI and
// offline. Uses the development executable (unoptimized); enabling the Binaryen
// `wasm-opt` production path is a deploy-time size optimization). Output: `build/wasmDist/` → serve
// as the preview
// server's `web/wasm/` carriage for the `compose-m3` catalog.
tasks.register<Sync>("wasmCatalogDist") {
  description = "Assemble the webpack-free CMP Wasm catalog distribution (build/wasmDist)."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
    include("index.html", "js-joda.esm.js", "fonts/**")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
