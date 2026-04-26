plugins {
  // KGP, the KMP-Android plugin and `kotlin.plugin.compose` all ship inside
  // the same buildscript-classpath bundle that AGP 9 brings in for any
  // module already applying an AGP plugin elsewhere in the build. Applying
  // them via `alias(libs.plugins…)` errors with "already on the classpath
  // with an unknown version, so compatibility cannot be checked", so we
  // apply by id without a version — Gradle resolves the unknown-version
  // entry from the buildscript classpath.
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
  id("ee.schimke.composeai.preview")
}

// Regression coverage for issue #248: applying `composePreview` to a
// `com.android.kotlin.multiplatform.library` `:shared`-style module didn't
// register `discoverPreviews` (the plugin only recognised
// `com.android.application` / `com.android.library`). The fix routes
// KMP-Android modules through the Compose Multiplatform Desktop renderer
// path — `commonMain` previews are pure-Compose composables that
// `ImageComposeScene` can capture without Robolectric / AGP unit-test
// infrastructure.
//
// Layout convention (see `skills/compose-preview/design/CMP_SHARED.md`):
// previews live in `commonMain` so they compile against the multiplatform
// compose runtime AND a JVM target ("desktop") is configured so the
// Desktop renderer has a JVM compilation to reach for. `@Preview` functions
// in `androidMain` only would compile against the Android-flavor
// compose-runtime AAR (which references `android.os.Parcelable`) and
// can't be rendered by `ImageComposeScene` on the host JVM.

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

  // JVM target so `commonMain` previews compile against the Desktop flavor
  // of compose-runtime — that's what the desktop renderer
  // ([RenderPreviewsTask] → DesktopRendererMain) launches against. Without
  // it the only target output would be `kotlin/android/main`, which calls
  // into `compose-runtime-android` and fails at render time with
  // `ClassNotFoundException: android.os.Parcelable`.
  jvm("desktop")

  sourceSets {
    commonMain.dependencies {
      // The string-typed `compose.runtime` accessors are deprecated in
      // compose-multiplatform 1.10 in favour of explicit module
      // coordinates, but they still resolve to valid coords on every
      // mirror — and the explicit coords were renamed in the 1.10 line
      // and aren't reliably published to all repos yet. Accept the
      // deprecation warning until the JetBrains migration shakes out.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      // `ui-tooling-preview` (the JetBrains-relocated androidx artifact)
      // ships `androidx.compose.ui.tooling.preview.Preview` on every
      // target, so commonMain previews compile against the same FQN that
      // [DiscoverPreviewsTask] scans for. The CMP-bundled
      // `compose.components.uiToolingPreview` re-publishes the annotation
      // under `org.jetbrains.compose.ui.tooling.preview.Preview` instead,
      // which discovery doesn't recognise — declaring the explicit coord
      // sidesteps that.
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
    }
  }
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
