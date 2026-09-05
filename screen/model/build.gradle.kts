// `:screen-model` — the composition document a UI builder assembles, made multiplatform.
//
// A `ScreenDocument` is a tree of `ScreenNode`s: a component id, the values that instance carries,
// and its children by slot. That is the shape the builder edits ("add a `LazyColumn`, add a `Card`,
// edit its text"), and it is deliberately *data* — no Compose types, no catalog types, no renderer.
//
// The document and the generator that turns it into Kotlin are **not defined here**. They live in
// `screen/generator/`, shared as a source directory by this module and by the Gradle plugin's
// `:preview-discovery`, because the plugin build is a `kotlin-dsl` build pinned to
// `embeddedKotlinVersion` and cannot host a KMP module, and an included build cannot depend on the
// build that includes it. One copy on disk, compiled by both — not a mirror that can drift. What
// this module adds around it is browser-side: editing operations, the highlighter, and the M3
// palette the builder offers.
//
// **Kotlin Multiplatform** (jvm + wasmJs), for the same reason `:slot-preview-runtime` is: the
// builder runs in the browser, and the tests run on the JVM. No Compose dependency at all — this
// module is the model, not the renderer.
//
// **Published**, as of the preview server's UI builder needing it. That reverses a deliberate
// "not yet": a coordinate is permanent and the node shape was expected to move, so the rule was to
// wait until it stopped. What changed is not the shape's stability — it is that the wait now has a
// cost nobody was paying before.
//
// `compose-preview-server`'s `/ui-builder/` generates Kotlin from a hand-written
// `CapabilityComposeCodeExporter`: a 1,588-line `when (componentId)` restating, by hand, the same
// component table its renderer restates and its capability JSON declares. The server's *export
// lane* already has the honest path — `ScreenDocumentProjection` projects a saved document onto
// `ScreenDocument` and runs the real generator — but the **editor** cannot, because the editor is
// wasm and `preview-discovery` is `kotlin("jvm")`. So the browser gets the hand-written exporter
// and the server gets the generator, and they drift; that is the third table
// `docs/design/COMPONENT_RECORD.md` §1.4 counts.
//
// This module is the generator compiled for `wasmJs`, which is exactly what closes that. Publishing
// it is the one thing the merge cannot be done without.
//
// The shape may still move. `:preview-annotations` and `:slot-preview-runtime` set the precedent
// for how that is handled — a coordinate at 0.x, and the schema constants in `ComponentRecord`
// carrying the compatibility story rather than the version number.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain {
      // The shared generator source, compiled here for `wasmJs` (so the browser builder can
      // generate with no server) and by `:preview-discovery` in the plugin build for the JVM. One
      // copy on disk: see that module's build script for why it cannot simply depend on this one.
      kotlin.srcDir("../generator/src/commonMain/kotlin")
      dependencies { api(libs.kotlinx.serialization.json) }
    }
    val jvmTest by getting { dependencies { implementation(libs.junit) } }
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "screen-model",
    displayName = "Compose Preview — Screen Model",
    description =
      "The composition document a UI builder assembles and the generator that turns it into " +
        "Compose source, compiled for the JVM and for `wasmJs` so a browser-side builder can " +
        "generate with no server round-trip. Carries `ScreenDocument`, `ScreenGenerator` and the " +
        "`ComponentRecord` catalog they read, plus the editing operations and source highlighter " +
        "a builder needs around them.",
  )
  inceptionYear.set("2026")
}
