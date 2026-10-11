plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35 although `compileSdk = 37`: the plugin auto-detects from
  // `compileSdk`, but SDK 36+ needs JDK 21 and this toolchain is JDK 17. Demonstrates the consumer
  // escape hatch; drop it when the toolchain moves to JDK 21.
  sdkVersion.set(35)

  // resourcePreviews { ... } is on by default: vector / animated-vector / adaptive-icon previews.

  // `ScrollPreviewPixelTest` reads rendered PNGs, so render before unit tests.
  renderBeforeUnitTests.set(true)

  // a11y is daemon-only: the sample's `BadButtonPreview` etc. are exercised through the daemon
  // (VS Code chip toggle, `compose-preview a11y`).
}

android {
  namespace = "com.example.sampleandroid"
  // Compose 1.12 (BOM 2026.08.00) publishes minCompileSdk 37 metadata.
  compileSdk = 37

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
  // `FontPreviewWrapper` extends `PreviewWrapperProvider` (ui-tooling-preview 1.11+), newer than
  // the stable BOM's artifact; compileOnly, since it's interface-only.
  compileOnly(libs.compose.ui.tooling.preview.wrapper)
  // …and on the unit-test runtime, since the renderer instantiates the wrapper reflectively;
  // otherwise it silently renders without it.
  testImplementation(libs.compose.ui.tooling.preview.wrapper)
  implementation(libs.compose.foundation)
  implementation(libs.activity.compose)
  // Coil 2 — `AsyncImagePreviews.kt` is the regression fixture for coil images captured blank.
  // Declared as any app would; `CoilPreviewSupport` in the renderer swaps the `ImageLoader`.
  implementation(libs.coil2.compose)
  // `NavHostPreview.kt` exercises the daemon's `data/navigation` product and `navigation.*` script
  // events.
  implementation(libs.navigation.compose)
  // Exercises `Font(GoogleFont(...), provider)` under Robolectric; the renderer's shadow serves it
  // from a local cache under `~/.cache/composeai/fonts/`.
  implementation("androidx.compose.ui:ui-text-google-fonts")
  // With `emoji2-bundled`, the renderer's `EmojiCompatRenderSupport` renders emoji from the bundled
  // font, as on-device (see `EmojiCompatComparisonPreview`).
  implementation("androidx.emoji2:emoji2-bundled:1.7.0")
  // Roborazzi's per-preview clock control annotation, read by `DiscoverPreviewsTask`.
  implementation(libs.roborazzi.annotations)
  // Our `@ScrollingPreview` lives here — same role as above, read by FQN
  // at discovery time; no runtime behaviour.
  implementation(libs.composeai.preview.annotations)
  // `NotificationContent` helper for `@Preview`-based notification previews.
  implementation(project(":notification-preview-runtime"))
  // Only for `NotificationStyleGallery.MediaStylePreview`; kept off the runtime module so its
  // consumers don't inherit the legacy media artifact.
  implementation("androidx.media:media:1.8.0")
  // Soft-keyboard data extension: `SoftKeyboardAnimatedPreview`'s recording drives text and key
  // highlights through the daemon's `input.keyboard` path.
  implementation(libs.composeai.data.keyboard.connector)
  // Typography specimen helpers (type-role sheet, weight ladder, script coverage).
  implementation(project(":typography-preview-runtime"))
  // Colour specimen helpers (`ColorScheme` role swatches, named palettes).
  implementation(project(":color-preview-runtime"))
  // `GlanceAppWidgetContent` helper: materialises a `GlanceAppWidget` to `RemoteViews` via
  // `composeForPreview(...)` and inflates it, as `AppWidgetHost.createView(...)` does on-device.
  implementation(project(":glance-preview-runtime"))
  // `AppWidgetContent` helper: inflates a `RemoteViews` factory and matches `<appwidget-provider>`
  // metadata against `AppWidgetManager.installedProviders` (the manifest registers
  // `WeatherAppWidgetReceiver`).
  implementation(project(":appwidget-preview-runtime"))
  // `SplashScreenSurface` helper for previewing the Android 12+ SplashScreen window.
  implementation(project(":splash-preview-runtime"))
  // Glance's own `@Preview`, for the native FQN-discovered path in `NativeGlanceWidgetPreview`.
  implementation(libs.glance.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")
  // `@AnimatedPreview(showCurves = true)` probes ui-tooling's `PreviewAnimationClock` reflectively,
  // and ui-tooling is debug-only by default.
  testImplementation("androidx.compose.ui:ui-tooling")
  // `ComposeAnimatedProperty` lives in `animation-tooling-internal`, not `animation-core`; without
  // it the curves path fails at attach.
  testImplementation("androidx.compose.animation:animation-tooling-internal")
}
