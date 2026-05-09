@file:Suppress("DEPRECATION")

// `:data-pseudolocale-connector-desktop` is the JVM-side counterpart to
// `:data-pseudolocale-connector`
// (Android). Both planners read `renderNow.overrides.localeTag` and emit a
// `PreviewOverrideExtension`, but the desktop variant has a narrower contract: CMP Desktop's
// string-resource path (`org.jetbrains.compose.resources`) doesn't go through
// `LocalContext.resources`,
// so we don't pseudolocalise text content here. What we *do* provide:
//
// - `LayoutDirection.Rtl` for `ar-XB` so RTL bugs surface in the rendered PNG.
// - A rewritten `LocaleList` (en-XA → en, ar-XB → ar) so locale-sensitive Compose text rendering
//   resolves against a real BCP-47 locale instead of a tag the JVM doesn't know.
//
// The locale-list rewrite is done at the renderer level (`RenderEngine.localeProviders`), so this
// connector's planner only contributes the around-composable.
//
// See `site/reference/pseudolocale.md` for the platform support matrix.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":data-pseudolocale-core"))
  api(project(":daemon:core"))
  api(project(":data-render-compose"))
  implementation(compose.runtime)
  implementation(compose.ui)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-pseudolocale-connector-desktop",
    displayName = "Compose Preview - Pseudolocale Data Product Connector (Desktop)",
    description =
      "Daemon-side pseudolocale data-product connector for Compose Multiplatform Desktop: provides LayoutDirection.Rtl for ar-XB and a rewritten LocaleList for en-XA / ar-XB. Text-content pseudolocalization is Android-only — see :data-pseudolocale-connector.",
  )
  inceptionYear.set("2026")
}
