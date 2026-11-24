// An app with an androidTest suite run in Robolectric's simulator, beside the app, with no
// emulator: `./gradlew :samples:android-instrumented:simulatorDebugAndroidTest` (the preview
// plugin's task), or `compose-preview android-simulator --instrument` on the two APKs. The tests
// cover the androidx.test surfaces a suite leans on first: InstrumentationRegistry,
// ActivityScenario, Espresso, and a Compose test rule.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

android {
  namespace = "ee.schimke.composeai.samples.instrumented"
  compileSdk = 37
  defaultConfig {
    applicationId = "ee.schimke.composeai.samples.instrumented"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  debugImplementation(libs.ui.test.manifest)

  androidTestImplementation(platform(libs.compose.bom.stable))
  androidTestImplementation(libs.ui.test.junit4)
  androidTestImplementation(libs.junit)
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.test:core-ktx:1.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
