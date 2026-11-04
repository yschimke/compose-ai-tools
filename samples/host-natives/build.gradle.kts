// An app that exercises every native library `host-natives/index.json` serves, one check each, so
// `compose-preview launch` can be seen loading their host builds in place of the APK's Android
// ones (#6411). Each check logs `HostNatives: PASS|FAIL <library> ...` and shows the same line on
// screen; `samples/host-natives/README.md` has the launch command.
//
// Deliberately not a preview sample: it applies no `ee.schimke.composeai.preview` plugin and has no
// `@Preview`s, since the render pipeline is not what it tests.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.composeai.samples.hostnatives"
  // Compose 1.12 (the stable BOM) publishes minCompileSdk 37 metadata.
  compileSdk = 37

  defaultConfig {
    applicationId = "ee.schimke.composeai.samples.hostnatives"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    // The simulator runs on x86_64 and arm64 hosts; the other ABIs only add size (libsignal's AAR
    // is 128 MB across four).
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  buildFeatures { compose = true }

  // libsignal-android requires it (its AAR metadata says so).
  compileOptions { isCoreLibraryDesugaringEnabled = true }

  // libsignal's test-only library, which nothing here loads.
  packaging { jniLibs { excludes += "**/libsignal_jni_testing.so" } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  // Compose UI up to 1.12.x pins androidx.graphics:graphics-path 1.0.1, which loads its library
  // unconditionally: the `graphics-path` check.
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)

  // One per index entry, at a version the index has a build of. Renovate moves them; the index
  // resolves a newer one to its nearest build until the weekly watch adds it.
  implementation("androidx.sqlite:sqlite-bundled:2.7.1")
  implementation("androidx.ink:ink-strokes:1.0.0")
  implementation("androidx.ink:ink-brush:1.0.0")
  implementation("androidx.ink:ink-geometry:1.0.0")
  implementation("com.github.luben:zstd-jni:1.5.7-25@aar")
  implementation("net.java.dev.jna:jna:5.19.1@aar")
  implementation("org.conscrypt:conscrypt-android:2.8.0")
  implementation("org.signal:libsignal-android:0.86.5")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
