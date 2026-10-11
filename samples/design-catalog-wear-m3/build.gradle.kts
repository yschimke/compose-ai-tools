// `:samples:design-catalog-wear-m3` — a Wear Compose Material 3 design catalog: one `@Preview` per
// component, exportable as a sticker sheet (sibling of `:samples:design-catalog-m3`).
//
// Wear is dark-first, so component stickers are a single transparent dark capture
// (`@CatalogWearModes`); only full-screen components fan out to the round breakpoints
// (`@CatalogWearBreakpoints`). Builds against the stable Compose BOM.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; see `:samples:wear` for the JDK 17 toolchain
  // rationale (Robolectric SDK 36 requires JDK 21+).
  sdkVersion.set(35)

  // `WearFocusedPressPixelTest` reads the real renderer outputs and verifies that pressed changes
  // the Button container rather than merely changing its label glyphs (issue #3694).
  renderBeforeUnitTests.set(true)
}

// The locales this catalog localises (its `values-<locale>` dirs plus `en`), derived from the
// resource dirs. Used by `localeFilters` below.
val wearCatalogAuthoredLocales: List<String> =
  (listOf("en") +
      projectDir
        .resolve("src/main/res")
        .listFiles()
        .orEmpty()
        .map { it.name }
        .filter { it.startsWith("values-") }
        .map { it.removePrefix("values-") }
        .filter { it.matches(Regex("[a-z]{2}(-r[A-Z]{2})?")) })
    .distinct()
    .sorted()

android {
  namespace = "com.example.designcatalogwearm3"
  // wear-compose 1.7.0-beta requires `compileSdk = 37`; override the conventions `compileSdk =
  // 36`.
  compileSdk = 37

  defaultConfig {
    applicationId = "com.example.designcatalogwearm3"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // Keep only this catalog's locales and drop the ~68 locales its AAR deps ship (~470 KB of dead
  // `resources.arsc` strings), so bundles stay small without post-hoc surgery.
  androidResources { localeFilters += wearCatalogAuthoredLocales }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  implementation(libs.compose.ui.tooling.preview)
  // Typefaces resolve as downloadable Google fonts (fetched and cached by the renderer), so the
  // module ships no font bytes.
  implementation("androidx.compose.ui:ui-text-google-fonts")
  // @ScrollingPreview(END) — full-screen Wear components (EdgeButton, scaling
  // lists) reveal their bottom-anchored chrome only after the scroll settles, so
  // the catalog captures them scrolled to the end rather than at the resting top.
  implementation(libs.composeai.preview.annotations)
  // `previewOverride*` — each sticker's editable labels/values become override knobs the daemon can
  // seed and the `compose/overrides` producer can enumerate. JVM artifact; the Android compose on
  // this classpath supplies the matching `androidx.compose.*` symbols it compiles against.
  implementation(libs.composeai.data.preview.overrides.runtime)
  // `PreviewSlot` / `LocalSlotMode` — the Figma slot placeholders for the fillable regions of the
  // Wear cards, list rows, and scaffold templates.
  implementation(libs.composeai.slot.preview.runtime)
  // `TlcScalingHost` — hosts a component in a real single-item TransformingLazyColumn so
  // `CardScalingPreview` shows genuine TLC item scaling (see `CardScalingPreview.kt`).
  implementation(project(":wear-preview-runtime"))
  debugImplementation("androidx.compose.ui:ui-tooling")

  // `CatalogInteractivityTest` dispatches real clicks under Robolectric, with `LocalInspectionMode`
  // true and false. Deps mirror `:runtimes:splash`'s Robolectric suite on the same stable BOM.
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(platform(libs.compose.bom.stable))
  testImplementation("androidx.compose.ui:ui-test-junit4")
  // `debugImplementation`: `createComposeRule` launches a `ComponentActivity`, which must be merged
  // into this application module's debug manifest.
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
