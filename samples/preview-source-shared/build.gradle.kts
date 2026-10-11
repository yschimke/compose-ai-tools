// `:samples:preview-source-shared` — previews declared once, rendered on two lanes. A plain
// multiplatform library (no preview plugin) whose `@Preview`s `:samples:cmp-shared` (desktop) and
// `:samples:cmp-android-robolectric` (Robolectric) each render via `composePreviewSource`, since
// discovery otherwise only walks a module's own classes.
//
// `@file:CatalogGroup` in `SharedSourcePreviews.kt` needs the source file, so each consumer also
// sets `composePreview.previewSourceRoots` to this `src`; without it both lanes render ungrouped
// and green, which is what this sample catches.
//
// Two targets so each lane gets its own Compose flavour: `android` for Robolectric,
// `jvm("desktop")` for desktop.
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
