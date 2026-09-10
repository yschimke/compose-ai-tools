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

// The `com.android.kotlin.multiplatform.library` module that renders through ROBOLECTRIC
// (issue #248's other half). Its previews touch `android.os.Build`, so no amount of
// `ImageComposeScene` will draw them — the Desktop lane every KMP-Android module took until now
// has no `android.jar` to load them against. This is the Wear-catalog shape: UI that is
// Android-only on purpose.
//
// Deliberately distinct from its two siblings, and all three are needed:
//   * `:samples:cmp-shared` — KMP-Android PLUS `jvm("desktop")`, previews in `commonMain`. The
//     default lane, unchanged: multiplatform UI rendered on the host JVM.
//   * `:samples:cmp-android-only` — KMP-Android, no desktop target, no previews. The standing
//     #1852 / #1855 fail-soft fixture; it must stay non-renderable.
//   * this one — KMP-Android, no desktop target, previews that need Android.

kotlin {
  // AGP 9 / KMP renamed the `androidLibrary { }` DSL block to `android { }`.
  android {
    namespace = "com.example.cmpandroidrobolectric"
    compileSdk = 36
    minSdk = 24

    // The host-test compilation the Robolectric lane renders on. The plugin CANNOT add this for
    // the consumer: `withHostTest { }` both creates and configures the compilation, and AGP
    // rejects a second call with "Android host tests have already been enabled". Without it
    // `composePreview { kmpAndroidRobolectric = true }` warns and falls back to Desktop.
    //
    // `isIncludeAndroidResources` is what makes AGP build the merged resource APK
    // (`apk-for-local-test.ap_`) and the `test_config.properties` that points Robolectric at it.
    // Leave it off and library resources resolve to 0 at render time.
    withHostTest { isIncludeAndroidResources = true }

    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  // No `jvm("desktop")`, on purpose: there is nothing here Desktop could render.

  sourceSets {
    androidMain.dependencies {
      // Android-flavoured Compose — the `compose.*` accessors resolve to the `*-android`
      // artifacts with no desktop target present.
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.10.3")
    }
  }
}

composePreview {
  // The opt-in. Without it this module takes the Desktop lane like every other KMP-Android
  // module and its previews fail to render at all.
  kmpAndroidRobolectric = true
}
