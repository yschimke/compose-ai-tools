@file:Suppress("DEPRECATION")

// `:data-pseudolocale-connector-desktop` is the JVM-side counterpart to
// `:data-pseudolocale-connector`
// (Android). Both planners read `renderNow.overrides.localeTag` and emit a
// `PreviewOverrideExtension`. What this connector contributes:
//
// - `LayoutDirection.Rtl` for `ar-XB` so RTL bugs surface in the rendered PNG.
// - A wrapped `LocalResourceReader` so every `org.jetbrains.compose.resources.stringResource(...)`
//   lookup returns the pseudolocalised accent / bidi text. CMP Desktop's `stringResource` doesn't
//   walk `LocalContext.resources`, so we intercept at the byte-level reader instead of swapping a
//   `Resources` subclass like Android does. The `LocalComposeEnvironment` env-swap path the issue
//   originally suggested is unreachable — both the env interface and its CompositionLocal are
//   declared `internal` to `org.jetbrains.compose.resources` at CMP 1.10.x.
// - A rewritten `LocaleList` (en-XA → en, ar-XB → ar) so locale-sensitive Compose text rendering
//   resolves against a real BCP-47 locale instead of a tag the JVM doesn't know.
//
// The locale-list rewrite is done at the renderer level (`RenderEngine.localeProviders`), so this
// connector's planner only contributes the around-composable.
//
// See `site/reference/pseudolocale.md` for the platform support matrix.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":data-pseudolocale-core"))
  api(project(":daemon:core"))
  api(project(":data-render-compose"))
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  // `org.jetbrains.compose.resources.LocalResourceReader` — the byte-level interceptor we wrap so
  // every `stringResource(...)` lookup on desktop returns the pseudolocalised form. The `internal`
  // env-swap path (`LocalComposeEnvironment` / `ComposeEnvironment`) isn't reachable from outside
  // the resources module at CMP 1.10.x, so this is the published handle that actually works.
  implementation(libs.jetbrains.compose.components.resources)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-pseudolocale-connector-desktop",
    displayName = "Compose Preview - Pseudolocale Data Product Connector (Desktop)",
    description =
      "Daemon-side pseudolocale data-product connector for Compose Multiplatform Desktop: wraps LocalResourceReader so stringResource(...) returns the pseudolocalised accent / bidi form, provides LayoutDirection.Rtl for ar-XB, and emits a rewritten LocaleList for en-XA / ar-XB.",
  )
  inceptionYear.set("2026")
}
