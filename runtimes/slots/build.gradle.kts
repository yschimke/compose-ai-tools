// `:slot-preview-runtime` — composable-helper authoring path for **structured-screen slots**.
//
// `PreviewSlot("leadingIcon") { … }` marks a named region of a preview so a structured-screen
// builder (the design-parity Figma plugin) can fill it with a child component. It is a **no-op in a
// normal render** — it just draws its content, tagged with `testTag = "dp-slot:<name>"` so the
// region is captured into the semantics tree with its bounds (which the `/render/<id>.slots` route
// reads). Under `LocalSlotMode` (the daemon's `slotMode` render override, follow-up) it renders a
// translucent labelled `SlotPlaceholder` instead, so a designer sees exactly where each slot is and
// drops a composable into that precise box.
//
// **Kotlin Multiplatform** (jvm + wasmJs) so the marker is callable from a KMP consumer's
// `commonMain` — the shared catalog bodies in `:samples:design-catalog-m3-shared` (jvm + wasmJs),
// whose slots then show on both the desktop render sheet and the in-browser wasm tier. No Android
// target: an `androidJvm` consumer resolves the `jvm` variant, and declaring one would stamp a
// `minSdk` floor onto the artifact for no benefit (same rationale as `:preview-annotations`,
// #2185).
//
// Standalone on purpose — no compile dep on the renderer. The renderer/daemon depend *onto* this
// module to `CompositionLocalProvider(LocalSlotMode provides …)` around the rendered content,
// exactly as they do for `:lottie-preview-runtime`'s `LocalLottieProgress`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  // KGP-multiplatform + the compose-compiler plugin are already on the buildscript classpath via
  // the Compose bundle, so `alias(libs.plugins…)` errors with "already on the classpath with an
  // unknown version" — apply them by id (mirrors `:samples:design-catalog-m3-shared`).
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // JVM target so `commonMain` compiles against the Desktop flavor of compose-runtime — what the
  // desktop renderer / daemon (`ImageComposeScene`) launches against, and what a plain-JVM/Android
  // consumer resolves. Named "desktop" to mirror `:samples:design-catalog-m3-shared`.
  jvm("desktop") {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    // Library only — the wasmJs *app* (entrypoint + dist) lives in `:samples:cmp-wasm-catalog`.
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.jetbrains.compose.runtime)
      api(libs.jetbrains.compose.foundation)
      api(libs.jetbrains.compose.ui)
    }
    // The `dp-slot:` tag prefix has a single source of truth in the reader
    // (`PreviewSlots.SLOT_TAG_PREFIX`); a test asserts this module's copy agrees so the two can't
    // drift. JVM-only (the reader + junit are JVM), so it lives in the desktop test source set and
    // the marker's common runtime classpath stays serialization-free.
    val desktopTest by getting {
      dependencies {
        implementation(project(":data-layoutinspector-core"))
        implementation(libs.junit)
      }
    }
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "slot-preview-runtime",
    displayName = "Compose Preview — Slot Runtime",
    description =
      "Composable helper that marks a named slot region of a Compose `@Preview` (a `dp-slot:` " +
        "testTag captured with its bounds) so a structured-screen builder can fill it with a child " +
        "component; under a slot-mode override it renders a labelled placeholder for the designer.",
  )
  inceptionYear.set("2026")
}
