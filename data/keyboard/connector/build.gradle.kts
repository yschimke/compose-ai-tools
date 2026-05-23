// `:data-keyboard-connector` glues `:data-keyboard-core` (the wire-shape identity) to the daemon's
// data-product / preview-override surface. Mirrors `:data-focus-connector`'s split — kind constant
// in core, daemon-side machinery (controller, around-composable extension, planner, band
// composable) here. Ships:
//
//  - `KeyboardController` — process-static state holder for `softInputVisible` + `pressedKey`,
//    written from three places (the around-composable observing real Android IME signals, the
//    `KeyboardOverride` seed, and the daemon's `InteractiveSession.dispatch(KEY_*)`).
//  - `KeyboardOverrideExtension` / `KeyboardPreviewOverrideExtension` — `AroundComposable` plumbing
//    that shadows `LocalSoftwareKeyboardController`, observes `WindowInsetsCompat.Type.ime()`, and
//    overlays a fake Gboard-shaped band on top of preview content when the IME is up. Planner
//    always emits the extension so the observer is in place even without an explicit override —
//    the renderer's "default behaviour when this app state changes" surface.
//  - `SoftKeyboardBand` — internal composable rendering the band; not part of the public API.
//    Consumers don't reach for it directly; they get the band for free through the extension.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.keyboard.connector" }

dependencies {
  // Wire-shape identity — re-exported via `api` so consumers (`:daemon:android`,
  // `:renderer-android`) can refer to `Material3KeyboardProduct.KIND` without adding a second
  // `project` dependency.
  api(project(":data-keyboard-core"))

  // DataExtension / AroundComposableExtension. Re-exported so the planner / extension classes can
  // be referenced from `RobolectricHost`'s `previewOverrideExtensions` list without a second
  // project dep on the consumer.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // Foundation widgets (`Box`, `Column`, `Row`, `BasicText`) for the band composable. Compiled
  // against the older `compose-bom-compat` (Compose 1.9.x) so the published AAR's bytecode stays
  // binary-backward-compatible with consumers pinned to older Compose BOMs — same rationale as
  // `:renderer-android` (see its `compileOnly` block for the long-form story). `compileOnly`
  // because the consumer always brings its own foundation through its own BOM; we just need the
  // API surface at compile time. `testImplementation` mirrors so the connector's own unit tests
  // have actual runtime classes.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.foundation)
  // `androidx.core` for `WindowInsetsCompat` / `ViewCompat.dispatchApplyWindowInsets(view, insets)`
  // — the connector publishes synthetic `WindowInsetsCompat.Type.ime()` insets to the host view so
  // consumer code reading `WindowInsets.ime` (`Modifier.imePadding()`,
  // `Modifier.windowInsetsPadding(WindowInsets.ime)`,
  // `WindowInsets.ime.asPaddingValues()`) sees the band's height and reshapes accordingly.
  // `compileOnly` because the consumer always brings its own `androidx.core` (transitively, via
  // `androidx.compose.ui:ui`); we just need the API surface at compile time. Pinned to the
  // matching compat floor (1.13.0) so the published AAR's bytecode stays binary-backward-
  // compatible with consumers on older `androidx.core` lines — same rationale as
  // `compose-foundation`.
  compileOnly("androidx.core:core:1.13.1")
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.foundation)
  testImplementation("androidx.core:core:1.13.1")

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
  // Robolectric — `WindowInsetsCompat.Builder.setInsets(...)` wraps the framework `WindowInsets`
  // class internally; against the Android-jar stubs it silently returns the empty inset set, so
  // the test for [buildKeyboardInsets] (#1360 non-IME-inset preservation regression) needs a real
  // Android runtime to exercise the merge behaviour.
  testImplementation(libs.robolectric)
}

mavenPublishing {
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-keyboard-connector",
    displayName = "Compose Preview - Soft Keyboard Data Product Connector",
    description =
      "Daemon-side soft-keyboard (IME) data-product connector: drives the around-composable that reflects an app's natural IME state (LocalSoftwareKeyboardController + WindowInsetsCompat.Type.ime()) as a Gboard-shaped overlay on previews, and lets `renderNow.overrides.keyboard` / `interactive/input` `KEY_*` dispatches drive the same band from the agent side.",
  )
  inceptionYear.set("2026")
}
