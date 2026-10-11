plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
  id("ee.schimke.composeai.preview")
}

// Previews of composables whose ViewModels come from Metro's compile-time DI graph (see
// [CounterPreviews]):
//  * State hoisting — the stateless `CounterScreenContent(state, …)` with a literal state; the
//    idiomatic preview path to reach for first.
//  * Full DI graph — `CounterScreen()` uses `metroViewModel()`; the preview builds the app's
//    `AppGraph` and provides its `MetroViewModelFactory`.
//
// No `sdkVersion` pin: `samples/sdk21/` runs only on JDK 21, so Robolectric renders the
// auto-detected SDK 36.

android {
  namespace = "com.example.metroviewmodel"
  // Compose 1.12 (BOM 2026.08.00) publishes minCompileSdk 37 metadata.
  compileSdk = 37

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  // `metroViewModel()` + `LocalMetroViewModelFactory` + `MetroViewModelFactory`
  // and `ViewModelGraph` come from this artifact. It transitively pulls in
  // `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` which
  // Gradle variant-resolves to the Android variant for this module — same
  // `androidx.lifecycle.viewmodel.compose.*` classes as the AndroidX-classic
  // artifact, so no duplicate-class trouble.
  implementation(libs.metro.viewmodel.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
