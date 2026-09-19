// `:samples:preview-source-shared` — previews declared ONCE, rendered on TWO lanes.
//
// The module applies **no** `ee.schimke.composeai.preview` plugin and registers no render lane. It
// is a plain multiplatform library that happens to contain `@Preview` functions. Two sibling
// samples name it in their `composePreviewSource` configuration and each renders these same
// previews on its own lane:
//
//   * `:samples:cmp-shared`             — Compose Multiplatform Desktop (`ImageComposeScene`)
//   * `:samples:cmp-android-robolectric` — Robolectric, against real Android
//
// This is the case the `composePreviewSource` configuration exists for. The plugin registers
// exactly ONE lane per module, and discovery method-walks only the module's own classes — a
// dependency JAR stays on the ClassGraph classpath so a multi-preview annotation resolves, but its
// `@Preview` functions are never walked. Before this configuration, a catalog that wanted both
// lanes had to re-declare every preview once per lane, and the two copies drifted.
//
// `@file:CatalogGroup` in `SharedSourcePreviews.kt` is not decoration: it is the half of the
// feature that classes alone cannot carry. The annotation reaches the bytecode on the `…Kt` facade
// class, but resolving that class back to `SharedSourcePreviews.kt` is done by matching its
// package-qualified source name against real files — which is why each consumer also points
// `composePreview.previewSourceRoots` at this module's `src`. Drop that line and both lanes still
// render, ungrouped, with a green build. That is the failure this sample is here to catch.
//
// Two targets so each consumer resolves its own flavour of the compose runtime: `android` for the
// Robolectric lane (whose classes call into `compose-runtime-android`) and `jvm("desktop")` for the
// Desktop one. A jvm-only producer would resolve for both — an `androidJvm` consumer takes a `jvm`
// producer — but the Android lane would then render classes compiled against desktop Compose.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Same buildscript-classpath bundle story as `:samples:cmp-shared` — apply by id without a
  // version, or AGP 9's bundled copies error with "already on the classpath with an unknown
  // version, so compatibility cannot be checked".
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  android {
    namespace = "com.example.previewsourceshared"
    compileSdk = 36
    minSdk = 24

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  jvm("desktop")

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      // The JetBrains-relocated androidx artifact, which ships
      // `androidx.compose.ui.tooling.preview.Preview` on every target — the FQN discovery scans
      // for. See the same note in `:samples:cmp-shared`.
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
      // `@file:CatalogGroup`, from `commonMain` — the multiplatform half of `preview-annotations`.
      implementation(libs.composeai.preview.annotations)
    }
  }
}
