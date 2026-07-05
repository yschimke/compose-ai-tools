// `:samples:design-catalog-m3-shared` — the **single source of truth** for the
// Compose Material 3 catalog's component set, shared by two surfaces:
//
//  * `:samples:design-catalog-m3` (JVM/desktop) authors the `@Preview` sticker
//    sheet against these composables and is the module the `compose-preview`
//    renderer / daemon builds — the baked stickers, `compose/theme`,
//    `compose/semantics-wireframe`, a11y findings, AND the trusted server-side
//    live re-render all come from there.
//  * `:samples:cmp-wasm-catalog` (wasmJs) mounts these same composables in the
//    browser sandbox for the in-browser "Run in browser (Wasm)" tier.
//
// Before this module the two surfaces re-authored the M3 component set twice
// (the Android catalog's `@Preview` stickers vs. the wasm module's id→composable
// map). They now call one authoritative `CatalogComponent(id, interactive)` here,
// so the component list, the theme wrapper, the generic-font plumbing, and the
// stateful/interaction helpers live in exactly one place.
//
// Deliberately thin — only the multiplatform compose runtime + `material3` — and
// applies NO `ee.schimke.composeai.preview` plugin: it's a plain library. The
// `@Preview` annotations (and their discovery) live in the desktop consumer, so
// this module never needs `ui-tooling-preview` on the `wasmJs` target (that
// artifact has no wasm klib).
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Applied by id without a version — KGP-multiplatform + the compose-compiler
  // plugin are already on the buildscript classpath via the AGP/Compose bundle,
  // so `alias(libs.plugins…)` errors with "already on the classpath with an
  // unknown version" (mirrors `:samples:cmp-shared` / `:samples:cmp-wasm-catalog`).
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // JVM target so `commonMain` compiles against the Desktop flavor of
  // compose-runtime — that's what the desktop renderer / daemon
  // (`ImageComposeScene`) launches against. Named "desktop" to mirror
  // `:samples:cmp-shared`; the consumer `:samples:design-catalog-m3` resolves
  // this single JVM variant.
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
    commonMain.dependencies {
      // String-typed `compose.*` accessors are deprecated in CMP 1.10 in favour
      // of explicit coords, but the renamed coords aren't reliably published to
      // every mirror yet — mirror the sibling CMP samples and accept the warning.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
    }
  }
}
