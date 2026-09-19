plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  // Same buildscript-classpath bundle story as `:samples:cmp-shared` — apply KGP, the
  // KMP-Android plugin and `kotlin.plugin.compose` by id (no version) so they resolve from
  // the AGP-provided classpath rather than erroring on an unknown-version alias.
  // DELIBERATELY THE HOSTILE APPLY ORDER, and it is the fixture for it.
  //
  // `ee.schimke.composeai.preview` FIRST and `com.android.kotlin.multiplatform.library` LAST is
  // the shape a convention plugin produces, and it is the one that used to lose this module its
  // lane: `org.jetbrains.compose` landing first made the plugin commit to the Desktop renderer
  // before the KMP-Android plugin could ask for Robolectric, and the render then died with
  // `NoClassDefFoundError: android/os/Parcelable`. The plugin now defers that commit to
  // `afterEvaluate` when a KMP module has no KMP-Android plugin YET.
  //
  // Keeping the sample in this order means CI renders the awkward case on every run. The ordinary
  // order — the preview plugin applied last, which the docs show and every other sample uses — is
  // covered by `:samples:cmp-shared` alongside it.
  id("ee.schimke.composeai.preview")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.android.kotlin.multiplatform.library")
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

      // The shared preview-source module, on the runtime classpath so its composables resolve at
      // render time. Its `android` variant is what an `androidJvm` consumer selects, so what
      // Robolectric renders is Android-flavoured Compose — the same source, compiled for this
      // lane.
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
