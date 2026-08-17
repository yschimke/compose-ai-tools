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
// colour tokens, depth effects, and focus model. The sample uses a per-env
// naming convention so the
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
  // `compose-bom-stable` aligns with what Glimmer's own POM resolves to, so no manual `compose-ui`
  // pin is needed here; the BOM also brings tooling / preview alongside Compose itself.
  //
  // Deliberately NOT naming the resolved Compose version in this comment. It used to read
  // "2026.05.x — Compose 1.10.x", which was true of Glimmer alpha13 and went stale the moment
  // either ref moved: the module resolves `androidx.compose.runtime:1.12.0-beta02` today, because
  // Glimmer's alpha line drags Compose forward and conflict resolution takes the higher version.
  // A stale version in a comment is worse than none — it was read as evidence that this module
  // sits below the Compose 1.11.0 floor where `ComposeRuntimeFlags.isLinkBufferComposerEnabled`
  // does not exist, and therefore that the repo-wide `composePreview.linkBufferComposer`
  // (gradle.properties, see docs/LINK_BUFFER_COMPOSER.md) would abort this module's renders.
  // It does not — and since that default is now `auto`, a module below the floor degrades to the
  // old composer with a notice rather than aborting anyway:
  // `./gradlew :samples:xr-glimmer:composePreviewRenderAll` renders clean.
  // Check the resolved graph, not this comment:
  //   ./gradlew :samples:xr-glimmer:dependencies --configuration debugRuntimeClasspath
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Glimmer itself. Pulls Compose foundation / ui transitively per its own POM — again, see the
  // resolved graph rather than a version pinned in prose here.
  implementation(libs.xr.glimmer)

  // `@FocusedPreview` — read by FQN at discovery time; the annotation is binary-retained
  // so the renderer's focus-walking path (`moveFocus(Enter)` + `moveFocus(Next)` per step,
  // GIF stitching when `gif = true`) sees it without any runtime classpath cost. Used by
  // `GlimmerXrMenuNavigation` to drive focus through the menu items.
  implementation(project(":preview-annotations"))

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  // Contrast calibration reads the connector-owned environment resources; application code has no
  // dependency on the connector and therefore cannot accidentally ship preview scenery.
  testImplementation(project(":data-glimmer-environment-connector"))
}
