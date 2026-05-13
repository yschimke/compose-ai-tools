plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // resourcePreviews { ... } is on by default — the sample exercises the
  // Android XML resource preview pipeline (vector / animated-vector /
  // adaptive-icon) without any extra config.

  // `ScrollPreviewPixelTest` reads PNGs under
  // `build/compose-previews/renders/`; opt the unit-test tasks into a
  // `dependsOn(renderAllPreviews)` chain so `:samples:android:check`
  // renders before asserting.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.sampleandroid"

  defaultConfig {
    applicationId = "com.example.sampleandroid"
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  // NavHost-based sample (`NavHostPreview.kt`) exercises the daemon's
  // `data/navigation` data product (Intent + back-pressed snapshot) and the
  // `navigation.*` script-event surface end-to-end. The library's public
  // composables (`NavHost`, `composable`, `rememberNavController`) have been
  // stable since 2.7.x; we pin to the latest stable in libs.versions.toml.
  implementation(libs.navigation.compose)
  // Exercises the `Font(GoogleFont(...), provider)` path under Robolectric —
  // the shadow in `renderer-android` swaps the GMS provider lookup for a
  // local cache under `.compose-preview-history/fonts/`.
  implementation("androidx.compose.ui:ui-text-google-fonts")
  // Roborazzi's per-preview clock control annotation. Source-retained
  // metadata read by `DiscoverPreviewsTask` — the annotation itself has no
  // runtime behaviour in production builds.
  implementation(libs.roborazzi.annotations)
  // Our `@ScrollingPreview` lives here — same role as above, read by FQN
  // at discovery time; no runtime behaviour.
  implementation(project(":preview-annotations"))
  debugImplementation("androidx.compose.ui:ui-tooling")
  // `@AnimatedPreview(showCurves = true)` reflectively probes
  // `androidx.compose.ui.tooling.animation.PreviewAnimationClock` /
  // `AnimationSearch` on the unit-test classpath. ui-tooling is only on
  // the debug variant by default, so add it to the unit-test scope so
  // the renderer can attach the animation inspector during render runs.
  testImplementation("androidx.compose.ui:ui-tooling")
  // `getAnimatedProperties(...)` returns
  // `List<androidx.compose.animation.tooling.ComposeAnimatedProperty>` —
  // those tooling types live in the `animation-tooling-internal`
  // artifact, NOT in `animation-core`. The compose-bom pins it to the
  // matching version; without this dep the curves path errors at attach
  // time with "Missing class: ComposeAnimatedProperty".
  testImplementation("androidx.compose.animation:animation-tooling-internal")
}
