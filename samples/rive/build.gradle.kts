// A Rive app for `compose-preview launch`: three RiveAnimationViews, each exercising one part of
// the
// rive-android host build (`host-natives/rive`) — vector animation, embedded images (decoded
// through
// android.graphics) and embedded fonts (HarfBuzz) — drawn by Rive's Canvas renderer into the
// simulator's TextureViews. Each view logs `RiveSample: PASS|FAIL <file> ...` once it has drawn;
// `samples/rive/README.md` has the launch command.
//
// Deliberately not a preview sample: it applies no `ee.schimke.composeai.preview` plugin.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
}

android {
  namespace = "ee.schimke.composeai.samples.rive"

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
}
