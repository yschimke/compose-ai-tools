// `:samples:design-catalog-m3` — a Compose Multiplatform (desktop) design catalog: one `@Preview`
// per component, exported as a sticker sheet (renders, `compose/theme` tokens, semantics wireframes
// and a11y findings). Component bodies live in `:samples:design-catalog-m3-shared`; this module
// owns the sticker layer and theme.
//
// Desktop CMP, not Android (no AGP plugin), so it renders on `ImageComposeScene` and the public
// desktop-only preview server can live re-render it (`serve --allow-render-trusted`).
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  // The shared, authoritative M3 component set (its `desktop` JVM variant).
  implementation(project(":samples:design-catalog-m3-shared"))

  // `previewOverride*` for the template's editable knobs; a JVM-only runtime the shared module
  // doesn't expose as `api`.
  implementation(libs.composeai.data.preview.overrides.runtime)

  // `@TypographyCatalog` / `@ColorCatalog` / `@ShapeCatalog` — the whole-object theme catalogs the
  // module declares (Roboto Flex + Google Sans Flex type scales, the light/dark schemes, the shape
  // scale) for discovery to auto-detect. See `CatalogCatalogs.kt`.
  implementation(libs.composeai.preview.annotations)

  // Desktop CMP compose — mirrors the sibling `:samples:cmp` desktop sample.
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.ui.tooling)
  // Republishes `androidx.compose.ui.tooling.preview.Preview` — the FQN
  // `PreviewDiscovery` scans for — on the desktop JVM target.
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)

  // String resources for the template copy via the shared module's public `Res`, so `localeTag`
  // translates it. Declared directly since this module uses it head-on.
  implementation(libs.jetbrains.compose.components.resources)
}
