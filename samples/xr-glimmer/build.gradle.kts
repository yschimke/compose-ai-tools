plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// `:samples:xr-glimmer` — Jetpack Compose Glimmer previews for Android XR display
// AI glasses. Glimmer (`androidx.xr.glimmer:glimmer`) is a separate Compose UI
// toolkit (not Material 3): components carry their own theme, additive-display
// colour tokens, depth effects, and focus model. The sample mirrors the per-env
// naming the design in `docs/design/GLIMMER_PREVIEW.md` proposes so the
// captures land at predictable filenames (`Glimmer_·_Light` etc.) and slot in
// when the `@GlimmerPreview*` meta-annotations from `:glimmer-preview-runtime`
// arrive — until they do, plain `@Preview(name = "Glimmer · ...")` is enough
// to produce the same discovery shape.

composePreview {
  // Pin Robolectric to SDK 35. Glimmer's published metadata raises
  // `minCompileSdk = 37` (so the module compiles against 37 below), but
  // Robolectric 4.16.1 only ships shadows up to API 36 and requires JDK 21+
  // for that — and the rest of the repo (and our CI toolchain) stays on JDK
  // 17. The Glimmer composables we exercise here don't reach for any API-36+
  // platform symbol at render time, so capturing them under SDK 35 works:
  // pure Compose drawing + the Glimmer additive colour tokens. If a future
  // Glimmer release wires in a platform-version-gated path that 35 doesn't
  // satisfy, bump this together with the JDK toolchain.
  sdkVersion.set(35)

  // `GlimmerCaptureAdditivePixelTest` reads PNGs under
  // `build/compose-previews/renders/`; opt the unit-test tasks into a
  // `dependsOn(composePreviewRenderAll)` chain so `:samples:xr-glimmer:check`
  // renders before asserting.
  renderBeforeUnitTests.set(true)
}

android {
  namespace = "com.example.samplexrglimmer"
  // Glimmer's alpha10+ metadata declares `minCompileSdk = 37` and the
  // AGP 9.2.x line is required to honour that — both are in place here
  // (see `agp = "9.2.x"` in [gradle/libs.versions.toml] and the
  // `platforms;android-37.0` install). Same pattern as `:samples:android-alpha`
  // and `:samples:remotecompose`; this module diverges from the rest of
  // the repo (still on 36) for the same metadata-gating reason.
  compileSdk = 37

  buildFeatures { compose = true }
}

dependencies {
  // `compose-bom-stable` (2026.05.x — Compose 1.10.x) aligns with what
  // Glimmer alpha13's POM resolves to (`androidx.compose.foundation:1.10.0`),
  // so no manual `compose-ui` pin needed. The BOM also brings tooling /
  // preview alongside Compose itself.
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Glimmer itself. Pulls `androidx.compose.foundation:1.10.0` and
  // `androidx.compose.ui:1.10.0` transitively per the alpha13 POM.
  implementation(libs.xr.glimmer)

  // `@FocusedPreview` — read by FQN at discovery time; the annotation is binary-retained
  // so the renderer's focus-walking path (`moveFocus(Enter)` + `moveFocus(Next)` per step,
  // GIF stitching when `gif = true`) sees it without any runtime classpath cost. Used by
  // `GlimmerXrMenuNavigation` to drive focus through the menu items.
  implementation(project(":preview-annotations"))

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
