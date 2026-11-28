// A Rive app for `compose-preview launch`. Three RiveAnimationViews each exercise one part of the
// rive-android host build (`host-natives/rive`): vector animation, embedded images and embedded
// fonts, drawn by Rive's Canvas renderer into the simulator's TextureViews. A fourth row is Rive's
// Compose API, which needs a GPU the host build lacks, so its worker must fail cleanly. Each logs
// `RiveSample: PASS|FAIL ...`; `samples/rive/README.md` has the launch command.
//
// Deliberately not a preview sample: it applies no `ee.schimke.composeai.preview` plugin.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.composeai.samples.rive"
  // Compose 1.12 (the stable BOM) publishes minCompileSdk 37 metadata.
  compileSdk = 37
  buildFeatures { compose = true }

  defaultConfig {
    applicationId = "ee.schimke.composeai.samples.rive"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    // The simulator runs on x86_64 and arm64 hosts; the other ABIs only add size.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }
}

dependencies {
  // At the version host-natives/rive builds. Renovate moves it; the index resolves a newer one to
  // its nearest build until a recipe for it is released.
  implementation("app.rive:rive-android:11.17.0")
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
}
