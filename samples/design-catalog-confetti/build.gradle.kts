// `:samples:design-catalog-confetti` — a **mobile (phone) Compose Material 3 design
// catalog** modelled on the Confetti conference app (joreilly/confetti). One
// `@Preview` per component in its primary modes (light + dark), authored so the
// upstream `compose-preview` renderer can export the module as an importable sticker
// sheet (see `docs/design/DESIGN_CATALOGS.md`, and the siblings
// `:samples:design-catalog-m3` / `:samples:design-catalog-wear-m3`).
//
// It's the phone counterpart of the Wear catalog: the same self-contained,
// Robolectric-rendered Android module shape, but the primary modes are the M3
// light/dark pair (`@CatalogModes`) rather than Wear's round size breakpoints, and
// the stickers reproduce Confetti's own surfaces — session cards, the bookmark
// toggle, speaker rows, day tabs, the schedule scaffold — rather than bare Material
// widgets. Kept thin (`androidx.compose.material3` off the stable BOM) so it builds
// against the same stable Compose line the rest of the samples do.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Pin Robolectric to SDK 35; the project toolchain is JDK 17 and Robolectric SDK 36
  // needs JDK 21+ (see `:samples:wear` / `:samples:design-catalog-wear-m3`).
  sdkVersion.set(35)
}

// The locales this catalog localises — its own `values-<locale>` string-resource dirs plus the `en`
// default — consumed by `androidResources.localeFilters` below to keep exactly these and drop the
// further locales the AAR dependencies ship. Derived from the resource dirs so a new
// `values-<locale>` translation is covered automatically. Matches the Wear catalog's approach.
val confettiCatalogAuthoredLocales: List<String> =
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
  namespace = "com.example.designcatalogconfetti"

  defaultConfig {
    applicationId = "com.example.designcatalogconfetti"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  // Keep only the locales this catalog localises — the `en` default plus its own `values-<locale>`
  // translations — and drop the AAR-only locales the compose dependencies ship but this catalog
  // never provides, so the packed bundle stays self-contained without post-hoc `resources.arsc`
  // surgery while the renderer's locale-override picker still resolves this catalog's translations.
  androidResources { localeFilters += confettiCatalogAuthoredLocales }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  // `@ScrollingPreview` backs the full-screen schedule scaffold template capture (scroll-to-top is
  // the resting frame here, but the annotation module also carries the catalog multipreview
  // idioms).
  implementation(project(":preview-annotations"))
  // `previewOverride*` — each sticker's editable labels/values become override knobs the daemon can
  // seed and the `compose/overrides` producer can enumerate.
  implementation(project(":data-preview-overrides-runtime"))
  // `PreviewSlot` / `LocalSlotMode` — Figma slot placeholders for the fillable regions of the
  // Confetti cards and list rows.
  implementation(project(":slot-preview-runtime"))
  debugImplementation("androidx.compose.ui:ui-tooling")
}
