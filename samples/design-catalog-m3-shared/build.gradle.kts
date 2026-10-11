// `:samples:design-catalog-m3-shared` — the single source of truth for the Compose Material 3
// catalog components (`CatalogComponent(id)`), shared by:
//  * `:samples:design-catalog-m3` (desktop) — the `@Preview` sticker sheet the renderer and daemon
//    build;
//  * `:samples:cmp-wasm-catalog` (wasmJs) — the in-browser "Run in browser (Wasm)" tier;
//  * `:cli:serve-wasm` — the preview UI itself, rendering compose-m3 cards in-process.
//
// A plain library (no preview plugin): `@Preview`s and discovery live in the desktop consumer, so
// this never needs `ui-tooling-preview` (which has no wasm klib) on `wasmJs`.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Applied by id without a version: these plugins are already on the buildscript classpath, and
  // `alias(libs.plugins…)` fails with "already on the classpath with an unknown version".
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // The desktop JVM target the renderer / daemon (`ImageComposeScene`) runs against.
  jvm("desktop") {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    // No `binaries.executable()` — this is a library; the wasmJs *app*
    // (entrypoint + dist) lives in `:samples:cmp-wasm-catalog`.
  }

  sourceSets {
    commonMain {
      // The slot-constraint adapter.
      kotlin.srcDir("src/currentRuntimeMain/kotlin")
      dependencies {
        // String-typed `compose.*` accessors are deprecated in CMP 1.10 in favour
        // of explicit coords, but the renamed coords aren't reliably published to
        // every mirror yet — mirror the sibling CMP samples and accept the warning.
        @Suppress("DEPRECATION") implementation(compose.runtime)
        @Suppress("DEPRECATION") implementation(compose.foundation)
        @Suppress("DEPRECATION") implementation(compose.material3)
        @Suppress("DEPRECATION") implementation(compose.ui)
        implementation(libs.graphics.shapes)
        // Compose Multiplatform string resources, so `localeTag` overrides (and pseudolocales)
        // render translated copy. `api` so the desktop sticker sheet can use the same generated
        // `Res`.
        @Suppress("DEPRECATION") api(compose.components.resources)
        // `PreviewSlot` / `LocalSlotMode`; `api` so the sticker sheet can provide `LocalSlotMode`.
        api(libs.composeai.slot.preview.runtime)
        // The builder's composition document model; `api` so the wasm app can hold a `Screen`. Pure
        // data, compiles to wasmJs.
        api(project(":screen-model"))
      }
    }

    // The `previewOverride*` runtime is JVM-only, so it backs only the desktop `actual`s of the
    // `catalogOverride*` wrappers (wasm returns author defaults) — which is where the daemon seeds
    // them.
    val desktopMain =
      getByName("desktopMain") {
        dependencies { implementation(libs.composeai.data.preview.overrides.runtime) }
      }

    // JVM-runnable unit tests for the pure-Kotlin theme-choice logic (the `theme.colors` serialized
    // app-palette round-trip). Desktop is the JVM target the renderer builds, so `desktopTest` runs
    // under `check` without dragging a wasmJs test toolchain in.
    val desktopTest =
      getByName("desktopTest") {
        dependencies {
          implementation(kotlin("test"))
          // `runComposeUiTest` — drives a real composition and dispatches real clicks, so
          // `CatalogInteractivityTest` can assert the thing a static render can never show: that a
          // click actually moves the UI, and that it does so identically on both render lanes.
          implementation(libs.jetbrains.compose.ui.test)
          @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
        }
      }
  }
}

// A public `Res` accessor so the desktop sticker sheet can resolve the shared strings across the
// module boundary.
compose.resources {
  publicResClass = true
  packageOfResClass = "com.example.designcatalogm3.shared.generated.resources"
}
