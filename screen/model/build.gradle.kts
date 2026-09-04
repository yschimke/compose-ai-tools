// `:screen-model` — the composition document a UI builder assembles, and the Compose source it
// generates.
//
// A `Screen` is a tree of `ScreenNode`s: a component id, the knob values that instance carries, and
// its children. That is the shape the builder edits ("add a `LazyColumn`, add a `ListHeader`, edit
// its text, add a `Card`"), and it is deliberately *data* — no Compose types, no catalog types, no
// renderer — so the same document can be edited in a browser, rendered by any host that knows the
// component ids, and turned into Kotlin by [ScreenCodegen].
//
// **Kotlin Multiplatform** (jvm + wasmJs), for the same reason `:slot-preview-runtime` is: the
// builder runs in the browser, and the codegen and tests run on the JVM. No Compose dependency at
// all — this module is the model, not the renderer.
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
    commonMain.dependencies { api(libs.kotlinx.serialization.json) }
    val jvmTest by getting { dependencies { implementation(libs.junit) } }
  }
}
