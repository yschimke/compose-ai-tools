plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Applied by id without a version: these plugins are already on the buildscript classpath via AGP
  // 9, and `alias(libs.plugins…)` fails with "already on the classpath with an unknown version".
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
  id("ee.schimke.composeai.preview")
}

// Regression coverage: `composePreview` on a `com.android.kotlin.multiplatform.library` module
// routes through the desktop renderer.
//
// Previews live in `commonMain` (see `compose-preview/references/cmp-shared.md` in yschimke/skills)
// with a JVM "desktop" target; `androidMain`-only previews compile against the Android compose
// runtime (`android.os.Parcelable`) and can't render on the host JVM.

kotlin {
  // AGP 9 / KMP renamed the `androidLibrary { }` DSL block to `android { }`.
  android {
    namespace = "com.example.cmpshared"
    compileSdk = 36
    minSdk = 24

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  // The desktop JVM target the renderer launches against; without it rendering fails with
  // `ClassNotFoundException: android.os.Parcelable`.
  jvm("desktop")

  sourceSets {
    commonMain.dependencies {
      // The string `compose.runtime` accessor is deprecated in CMP 1.10, but the replacement
      // coordinates aren't reliably published yet.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      // The JetBrains-relocated `ui-tooling-preview` ships
      // `androidx.compose.ui.tooling.preview.Preview`, the FQN discovery scans for; CMP's bundled
      // accessor uses an `org.jetbrains` FQN instead.
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
      // The compose-preview annotations, consumed from `commonMain` — the KMP scenario the
      // multiplatform `preview-annotations` artifact exists for (mirrors meshcore's
      // `:meshcore-components`, whose tokens live in shared code). Exercised by `SharedTokens.kt`.
      implementation(libs.composeai.preview.annotations)

      // On the runtime classpath so its composables resolve; `composePreviewSource` below is what
      // makes its `@Preview`s discoverable. Kept separate so dependencies' own previews don't
      // become this module's.
      implementation(project(":samples:preview-source-shared"))
    }
  }
}

dependencies {
  // Render `:samples:preview-source-shared`'s previews on THIS module's lane (Desktop). The same
  // module is named by `:samples:cmp-android-robolectric`, which renders the same previews on
  // Robolectric — one declaration, two lanes, which is the whole point of the configuration.
  composePreviewSource(project(":samples:preview-source-shared"))
}

composePreview {
  // Its sources, so the shared previews resolve back to the file that declares them and the
  // `@file:CatalogGroup` on it still applies. Classes alone find a preview; only the source file
  // places it.
  previewSourceRoots.from(file("../preview-source-shared/src"))
}
