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
// **Not published yet.** A published coordinate is permanent, and the node shape is expected to
// move while the builder is being built (slot addressing and variant selection are both still
// open). The preview server will need it as a coordinate eventually; publish it when the shape has
// stopped changing, not before.
plugins {
  id("composeai.base-conventions")
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
