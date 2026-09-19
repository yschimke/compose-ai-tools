// The existing screen-model coordinate owns offline generation, editing and highlighting for
// JVM and WASM. Serializable ScreenDocument inputs are api-exported from contracts.
// Generator/discovery implementation remains shared source with the Gradle plugin build,
// which cannot depend on a KMP project in the build that includes it.
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
      dependencies {
        api(libs.kotlinx.serialization.json)
        api(libs.composeai.screen.document)
      }
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
        "generate with no server round-trip. API-exports `ScreenDocument` from contracts and carries `ScreenGenerator` and the " +
        "`ComponentRecord` catalog they read, plus the editing operations and source highlighter " +
        "a builder needs around them.",
  )
  inceptionYear.set("2026")
}
