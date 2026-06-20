// The Wear OS "session viewer" client. Same engine as the mobile app (`:clients:core`), shrunk to
// the watch: tapping a `composeai://` link (forwarded from the phone, or a Wear deep link) connects
// to the `compose-preview serve` streamed-frame lane and paints the preview on the round display,
// forwarding taps and rotary-bezel scrolls as input. Wear Compose Material 3 throughout.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Robolectric SDK 35; see the matching note in `:samples:wear`.
  sdkVersion.set(35)
}

android {
  namespace = "ee.schimke.composeai.clients.wear"

  defaultConfig {
    applicationId = "ee.schimke.composeai.clients.wear"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
  }

  buildFeatures { compose = true }
}

dependencies {
  implementation(project(":clients:core"))

  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  implementation(libs.wear.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
