// Google Fonts resolution: the CSS-API queries, the TTF download, and the machine-local cache.
//
// Extracted from `:renderers-android` because two independent lanes need the *same* resolution, and
// a second copy would be worse than a shared module: `downloadFromGoogleFonts` encodes a
// stage-1/stage-2 contract whose failure mode is a silently wrong typeface cached under the right
// filename (see its KDoc). Two implementations would drift into resolving the same family to
// differently-metricked faces.
//
// The consumers are the Robolectric renderer's downloadable-font shadow (a Compose
// `Font(GoogleFont(...))` request) and the Remote Compose connector's typeface resolver (a
// `RemoteFontFamily.Named("google:…")` in an `.rc` document). Neither Compose nor Remote Compose
// types appear here — the whole surface is `(family, weight, italic) -> File` — which is what lets
// a
// pure-JVM module sit under an Android renderer and a Compose connector alike.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":common-io"))
  implementation(libs.okhttp)
  // `api` so consumers writing a custom downloader get Okio's `FileSystem` without re-declaring it.
  api(libs.okio)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-fonts-google",
    displayName = "Compose Preview — Google Fonts",
    description =
      "Google Fonts CSS-API resolution and machine-local TTF cache, keyed by (family, weight, " +
        "italic). Shared by the Robolectric downloadable-font shadow and the Remote Compose " +
        "typeface resolver so both lanes resolve a family to the same file.",
  )
  inceptionYear.set("2026")
}
