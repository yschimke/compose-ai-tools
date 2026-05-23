plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.metro)
  id("ee.schimke.composeai.preview")
}

// Demonstrates that the renderer's `@Preview` pipeline handles composables
// whose ViewModels come from Metro's compile-time DI graph. Two patterns
// are shown side-by-side in [CounterPreviews]:
//
//  * State-hoisting — the stateless `CounterScreenContent(state, …)` is
//    rendered with a literal state value. No DI involved, the renderer
//    sees a plain `@Composable`. This is the idiomatic preview path and
//    the one to reach for first.
//  * Full DI graph — `CounterScreen()` calls `metroViewModel()` which
//    reads `LocalMetroViewModelFactory`. The preview builds the same
//    `AppGraph` the app would (via `createGraph<AppGraph>()`) and
//    provides its `MetroViewModelFactory` to the composition. Exercises
//    the production wiring under Robolectric.

composePreview {
  // JDK 17 toolchain — Robolectric 4.16.1 supports SDK 36 only on JDK 21+,
  // so the rest of the samples pin to 35 too. See `:samples:android` for
  // the rationale block.
  sdkVersion.set(35)
}

android {
  namespace = "com.example.metroviewmodel"

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
