// The mobile "session viewer" client. Tapping a `composeai://` (or serve viewer) link opens this
// app, which connects to the `compose-preview serve` streamed-frame lane and presents the rendered
// preview full-screen — painting pushed frames and forwarding taps/keys back as input, so the
// remote composition behaves like a complete local app.
//
// UI chrome (connect screen, status overlays) carries `@Preview`s so the compose-preview pipeline
// gives the repo's required visual evidence for this surface.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Robolectric SDK 35 — the JDK-17 toolchain rationale matches `:samples:android`.
  sdkVersion.set(35)
}

android {
  namespace = "ee.schimke.composeai.clients.mobile"

  defaultConfig {
    applicationId = "ee.schimke.composeai.clients.mobile"
    // 26+ so the frame decoder's `java.util.Base64` (API 26+) is available without core-library
    // desugaring — `:clients:core` is a plain-JVM module packaged into this app.
    minSdk = 26
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
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
