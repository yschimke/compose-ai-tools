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
      // Compose Multiplatform string resources: the catalog's component labels resolve from
      // `commonMain/composeResources/values*/strings.xml`, so a `localeTag` override (or the
      // `en-XA`/`ar-XB` pseudolocale) renders translated / pseudolocalised copy through the
      // daemon's `LocaleList` provider. `api` so the desktop `@Preview` sticker sheet
      // (`:samples:design-catalog-m3`) can reference the same generated `Res` for its
      // scaffold-template strings without re-declaring its own resource set.
      @Suppress("DEPRECATION") api(compose.components.resources)
      // `PreviewSlot` / `LocalSlotMode` for the slotted-card component. `api` so the desktop
      // sticker
      // sheet (`:samples:design-catalog-m3`) can provide `LocalSlotMode` for its slot-mode sticker.
      api(project(":slot-preview-runtime"))
    }

    // The named-override runtime (`previewOverride*`) is a plain JVM artifact with no wasm klib, so
    // it can only back the desktop `actual`s of the `catalogOverride*` wrappers (the `wasmJs`
    // actuals return the author default). Desktop is the target the renderer / daemon builds, so
    // that's exactly where the knobs resolve against real daemon seeds.
    val desktopMain by getting {
      dependencies { implementation(project(":data-preview-overrides-runtime")) }
    }
  }
}

// Generate a **public** `Res` accessor so the desktop `@Preview` sticker sheet
// (`:samples:design-catalog-m3`) can resolve the shared string resources for its scaffold
// templates, not just the component bodies authored here. Default visibility is `internal`,
// which would keep `Res` invisible across the module boundary.
compose.resources {
  publicResClass = true
  packageOfResClass = "com.example.designcatalogm3.shared.generated.resources"
}

// Published-preview runtime pinning — the SHARED half of the note in
// `:samples:design-catalog-m3`'s build. Most `catalogOverride*` / `PreviewSlot` usage lives in THIS
// module's commonMain/desktopMain, so the released-runtime guard + coordinate substitution must
// apply here too. Without it, this module compiles its runtime usage against the HEAD
// `project(...)`
// deps while the consumer links the released jars — an unreleased runtime API would then slip
// through the guard and only surface later as a linkage / render error, not a compile failure. Same
// gate + release-please-managed version as the consumer (kept in lockstep by hand — a shared
// convention over both modules is the DRY follow-up).
if (providers.gradleProperty("composeaiUseReleasedRuntimes").orNull.toBoolean()) {
  val version =
    providers.gradleProperty("composeaiReleasedRuntimeVersion").orNull
      ?: error(
        "composeaiUseReleasedRuntimes is set but composeaiReleasedRuntimeVersion is missing from " +
          "gradle.properties"
      )
  configurations.all {
    resolutionStrategy.dependencySubstitution {
      substitute(project(":data-preview-overrides-runtime"))
        .using(module("ee.schimke.composeai:data-preview-overrides-runtime:$version"))
        .because("published previews reference released preview-runtimes (see :design-catalog-m3)")
      substitute(project(":slot-preview-runtime"))
        .using(module("ee.schimke.composeai:slot-preview-runtime:$version"))
        .because("published previews reference released preview-runtimes (see :design-catalog-m3)")
    }
  }
}
