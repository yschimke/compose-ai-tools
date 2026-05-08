// `:data-pseudolocale-connector` glues `:data-pseudolocale-core`'s pure transform to the daemon's
// preview-override seam. Mirrors `:data-focus-connector`'s shape — Android-only because the
// runtime resource interception wraps `Context` / `Resources` (Android types), and Compose needs
// `LocalContext` / `LocalLayoutDirection`.
//
// Ships:
//
//  - `PseudolocaleResources` — `Resources` subclass that post-processes return values from
//    `getString*` / `getText*` / `getQuantityString*` through `Pseudolocalizer`.
//  - `PseudolocaleContext` — `ContextWrapper` that returns the wrapped `Resources` from
//    `getResources()`, so `LocalContext.current.resources.getString(id)` (the path
//    `androidx.compose.ui.res.stringResource` walks) picks up the pseudolocalised value.
//  - `PseudolocaleOverrideExtension` — `AroundComposable` extension that installs the wrapped
//    `LocalContext` and (for `ar-XB`) `LocalLayoutDirection = Rtl`.
//  - `PseudolocalePreviewOverrideExtension` — planner mapping `renderNow.overrides.localeTag`
//    in {`en-XA`, `ar-XB`} to the around-composable.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.data.pseudolocale.connector"
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(project(":data-pseudolocale-core"))
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  testImplementation(libs.junit)
  // Robolectric-driven span-preservation test for `PseudolocaleResources`: real `SpannableString`
  // construction needs an Android runtime, and `Resources(AssetManager, ...)` needs an initialised
  // app to pull from. The test reaches the application via
  // `RuntimeEnvironment.getApplication()` so we don't need a separate `androidx.test.core` dep.
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
    artifactId = "data-pseudolocale-connector",
    displayName = "Compose Preview - Pseudolocale Data Product Connector",
    description =
      "Daemon-side pseudolocale data-product connector: wraps Resources to pseudolocalise getString* on the fly when localeTag is en-XA or ar-XB, with no build-time pseudoLocalesEnabled / resConfigs requirement.",
  )
  inceptionYear.set("2026")
}
