plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Same buildscript-classpath bundle story as `:samples:cmp-shared` — apply KGP, the
  // KMP-Android plugin and `kotlin.plugin.compose` by id (no version) so they resolve from
  // the AGP-provided classpath rather than erroring on an unknown-version alias.
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
  id("ee.schimke.composeai.preview")
}

// Regression coverage for #1852 / #1855: a `com.android.kotlin.multiplatform.library`
// `:shared`-style module with NO `jvm("desktop")` target. Its only resolvable runtime
// classpath is `androidRuntimeClasspath` (carrying `*-android` Compose AARs), so the Compose
// Multiplatform Desktop renderer can't render it — the same shape as the consumer's
// `:meshcore-mobile`. Two regressions ride on this shape, and this module is the standing
// guard for both:
//   * #1852 — resolving `androidRuntimeClasspath` for the desktop render trips an AGP variant
//     ambiguity that, unguarded, hard-fails the whole `composePreviewRender` pipeline.
//   * #1855 — the #1853 "skip non-renderable module" fix must NOT regress CLI detection of the
//     OTHER modules in the build (0.15.3 regressed to "detect nothing"). The `apply`
//     pipeline must still discover the renderable samples with this module present, and this
//     module must be skipped fail-soft (no render, no hard failure) rather than sinking the run.
//
// Intentionally distinct from `:samples:cmp-shared`, which DOES add `jvm("desktop")` and is the
// supported, renderable layout. Keep this one target-poor on purpose.

kotlin {
  // AGP 9 / KMP renamed the `androidLibrary { }` DSL block to `android { }`.
  android {
    namespace = "com.example.cmpandroidonly"
    compileSdk = 36
    minSdk = 24

    // The KMP-Android library plugin keeps android resource processing OFF by default. Material3
    // / the downloadable-fonts provider reference the Google-Fonts certificate `R.array`, so the
    // module won't compile without resources enabled.
    androidResources.enable = true

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  // Deliberately NO `jvm("desktop")` target — that omission is what makes this module
  // non-renderable and is the whole point of the regression fixture.

  sourceSets {
    androidMain.dependencies {
      // Android-flavoured Compose (no desktop target to pull the JVM flavour), matching the
      // real non-renderable shape. The `compose.*` accessors resolve to the `*-android`
      // artifacts here.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
    }
  }
}
