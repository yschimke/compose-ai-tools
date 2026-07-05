plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35 even though `composeai.android-conventions` sets
  // `compileSdk = 36`. The plugin's default is auto-detection (`compileSdk` →
  // `sdk=N` in `robolectric.properties`, see issue #1248), but the project's
  // toolchain is JDK 17 and Robolectric SDK 36 requires JDK 21+
  // (`DefaultSdkProvider.verifySupportedSdk`). The override demonstrates the
  // escape hatch consumers reach for in the same situation. Drop this line
  // when the toolchain moves to JDK 21.
  sdkVersion.set(35)

  // resourcePreviews { ... } is on by default — the sample exercises the
  // Android XML resource preview pipeline (vector / animated-vector /
  // adaptive-icon) without any extra config.

  // `ScrollPreviewPixelTest` reads PNGs under
  // `build/compose-previews/renders/`; opt the unit-test tasks into a
  // `dependsOn(composePreviewRenderAll)` chain so `:samples:android:check`
  // renders before asserting.
  renderBeforeUnitTests.set(true)

  // a11y is daemon-only now — there's no gradle-side toggle to enable it for the sample's
  // `composePreviewRenderAll` run. The sample carries `BadButtonPreview` etc. that exist to
  // demonstrate the ATF report shape; downstream consumers exercise them through the daemon
  // (VS Code chip toggle, `compose-preview a11y`).
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
  // `FontPreviewWrapper` extends `PreviewWrapperProvider`, which only exists in
  // ui-tooling-preview 1.11+. The stable BOM above pins the older annotations-only artifact, so
  // pin the 1.11 variant `compileOnly` (same shape as `:renderer-android`'s
  // `SystemBarsPreviewWrapper`). It's an annotation/interface-only artifact — nothing extra ships
  // in the APK.
  compileOnly(libs.compose.ui.tooling.preview.wrapper)
  // …and put it on the unit-test runtime too. The renderer loads `FontPreviewWrapper` reflectively
  // from `wrapperClassName` during `composePreviewRender` (a Test task, `renderBeforeUnitTests`),
  // and instantiating it needs its super-interface `PreviewWrapperProvider` on the runtime
  // classpath. Without this, a BOM whose `ui-tooling-preview` lacks the class would make the
  // wrapper silently fail to load (render falls back to no wrapper) instead of showing the font.
  testImplementation(libs.compose.ui.tooling.preview.wrapper)
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
  // shared local cache under `~/.cache/composeai/fonts/`.
  implementation("androidx.compose.ui:ui-text-google-fonts")
  // Roborazzi's per-preview clock control annotation. Source-retained
  // metadata read by `DiscoverPreviewsTask` — the annotation itself has no
  // runtime behaviour in production builds.
  implementation(libs.roborazzi.annotations)
  // Our `@ScrollingPreview` lives here — same role as above, read by FQN
  // at discovery time; no runtime behaviour.
  implementation(project(":preview-annotations"))
  // `NotificationContent` composable helper for the `@Preview` + stacked multi-preview
  // notification authoring path. Pairs with `@NotificationPreview` (in `:preview-annotations`)
  // for the FQN-discovered NOTIFICATION strategy.
  implementation(project(":notification-preview-runtime"))
  // `androidx.media.app.NotificationCompat.MediaStyle` — used only by
  // `NotificationStyleGallery.MediaStylePreview` to render the now-playing-card style. Kept on
  // the sample classpath (not pinned in `:notification-preview-runtime`) because consumers may
  // be on Media3 / no media stack at all; we don't want to drag this artifact onto every
  // notification-preview consumer.
  implementation("androidx.media:media:1.8.0")
  // Soft-keyboard data extension — `SoftKeyboardAnimatedPreview` uses
  // `LocalSoftwareKeyboardController.show()` (the natural app-side IME path the connector's
  // around-composable shadows) to raise the band, and writes `KeyboardController.notifyKeyDown`
  // directly to drive per-cap press highlights in the absence of an interactive daemon session.
  implementation(project(":data-keyboard-connector"))
  // `TypographySpecimen` / `FontFamilySpecimen` / `FallbackCoverageSpecimen` helpers — Material 3
  // type-role audit sheet, font-family weight ladder, and a fixed script-coverage check set,
  // each wrapped in a normal `@Preview`. Sister to `:notification-preview-runtime`.
  implementation(project(":typography-preview-runtime"))
  // `ColorSchemeSpecimen` / `ColorSpecimen` helpers — Material 3 `ColorScheme` role swatch sheet
  // and an arbitrary named-colour palette sheet, each wrapped in a normal `@Preview`. Sister to
  // `:typography-preview-runtime`; the compose-ai-tools analogue of Showkase's `@ShowkaseColor`.
  implementation(project(":color-preview-runtime"))
  // `GlanceAppWidgetContent` composable helper for the Glance-widget @Preview. The runtime
  // module re-exposes `androidx.glance:glance-appwidget` as `api`, so the sample's
  // `GlanceWidgetPreview` can declare a `GlanceAppWidget` subclass and pass it to the helper
  // — the helper materialises it to `RemoteViews` via `composeForPreview(...)` (Glance 1.2.0+)
  // and inflates the tree into the surrounding @Preview, same path
  // `AppWidgetHost.createView(...)` takes on-device. The sister RemoteViews preview
  // (`RemoteViewsWeatherWidgetPreview`) doesn't use this — it builds the `RemoteViews` tree by
  // hand from `res/layout/widget_weather.xml`.
  implementation(project(":glance-preview-runtime"))
  // `AppWidgetContent` composable helper that inflates a `RemoteViews` factory + auto-discovers
  // `<appwidget-provider>` metadata for the inflated layout id, offering the matched
  // `min/maxResize` / `targetCell*` / `resizeMode` into `LauncherWidgetMetadataChannel`. The
  // sample registers `WeatherAppWidgetReceiver` in the manifest so
  // `AppWidgetManager.installedProviders` has an entry for the layout to match.
  implementation(project(":appwidget-preview-runtime"))
  // `SplashScreenSurface` composable helper for previewing the Android 12+ SplashScreen window
  // appearance (`SplashScreenGallery.kt`). The runtime module is JVM-friendly and consumed
  // inside a regular `@Preview` — no annotation, no renderer strategy.
  implementation(project(":splash-preview-runtime"))
  // `androidx.glance.preview.Preview` annotation (a separate `glance-preview` artefact from the
  // appwidget runtime). Used by `NativeGlanceWidgetPreview` so the sample exercises the native
  // FQN-discovered Glance preview path — discovery picks up the annotation, the renderer's
  // `GlanceAppWidgetPreviewStrategy` wraps the body in a synthetic `GlanceAppWidget`, and
  // `composeForPreview(...)` materialises the same `RemoteViews` tree the helper-based sample
  // produces.
  implementation(libs.glance.preview)
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
