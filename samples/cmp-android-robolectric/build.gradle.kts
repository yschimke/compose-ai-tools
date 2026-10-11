plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Applied by id without a version, as in `:samples:cmp-shared`.
  //
  // Deliberately the hostile order: the preview plugin first and
  // `com.android.kotlin.multiplatform.library` last, as a convention plugin might. The preview
  // plugin defers its renderer choice to `afterEvaluate` for a KMP module without the KMP-Android
  // plugin yet; keeping this order makes CI render that case. `:samples:cmp-shared` covers the
  // ordinary order.
  id("ee.schimke.composeai.preview")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.android.kotlin.multiplatform.library")
}

// A `com.android.kotlin.multiplatform.library` module that renders through Robolectric: its
// previews touch `android.os.Build`, so the desktop lane can't draw them (the Wear-catalog shape).
//
// Distinct from its siblings, all three needed:
//   * `:samples:cmp-shared` — KMP-Android plus `jvm("desktop")`, previews in `commonMain`.
//   * `:samples:cmp-android-only` — no desktop target, no previews; must stay non-renderable.
//   * this one — no desktop target, previews that need Android.

kotlin {
  // AGP 9 / KMP renamed the `androidLibrary { }` DSL block to `android { }`.
  android {
    namespace = "com.example.cmpandroidrobolectric"
    compileSdk = 36
    minSdk = 24

    // The host-test compilation the Robolectric lane renders on; only the consumer can add it (AGP
    // rejects a second `withHostTest`). Without it, `kmpAndroidRobolectric = true` falls back to
    // Desktop. `isIncludeAndroidResources` builds the merged resource APK and
    // `test_config.properties`, or library resources resolve to 0.
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

      // The shared preview-source module; its `android` variant is selected here, so Robolectric
      // renders Android-flavoured Compose from the same source.
      implementation(project(":samples:preview-source-shared"))
    }
  }
}

dependencies {
  // The SAME module `:samples:cmp-shared` names, rendered here on the OTHER lane. One set of
  // preview declarations, two renderers: this is the case `composePreviewSource` exists for, and
  // having both samples point at one module is what keeps CI honest about it.
  composePreviewSource(project(":samples:preview-source-shared"))
}

composePreview {
  // The opt-in. Without it this module takes the Desktop lane like every other KMP-Android
  // module and its previews fail to render at all.
  kmpAndroidRobolectric = true

  // Sources of the shared module above, so its previews resolve back to the file that declares
  // them and its `@file:CatalogGroup` still applies — on this lane exactly as on Desktop.
  previewSourceRoots.from(file("../preview-source-shared/src"))
}
