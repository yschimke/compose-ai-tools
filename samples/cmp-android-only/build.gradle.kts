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

// Regression fixture: a `com.android.kotlin.multiplatform.library` module with no `jvm("desktop")`
// target, so the desktop renderer can't render it. It must be skipped fail-soft without breaking
// `composePreviewRender` (an AGP variant ambiguity) or CLI discovery of the other modules. Keep it
// target-poor; `:samples:cmp-shared` is the supported layout.

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

    // This regression fixture intentionally has no test compilation. KMP creates commonTest by
    // default even though the Android-only target cannot consume it, so remove that unused source
    // set instead of emitting a configuration warning on every build.
    remove(getByName("commonTest"))
  }
}
