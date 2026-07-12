@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; see :splash.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:wear-preview-runtime` — composable-helper authoring path for **Wear TransformingLazyColumn item
// scaling** in an isolated `@Preview`. Sister to `:splash-preview-runtime` /
// `:slot-preview-runtime`:
// a tiny helper consumed inside a regular `@Preview`, no new renderer strategy.
//
// `TlcScalingHost { spec -> … }` hosts a real single-item `TransformingLazyColumn` (the item
// flanked
// by spacer items so the list genuinely scrolls) and hands the caller the *genuine*
// `TransformingLazyColumnItemScope` + `TransformationSpec` — so the preview body is exactly the
// code a
// live list item uses (`Modifier.transformedHeight(this, spec)` + `SurfaceTransformation(spec)`),
// with real Wear scaling. Pair it with a plain `@Preview` for a still, `@ScrollingPreview(GIF,
// reduceMotion = false)` for the scaling scroll GIF, or `ProvideTlcScalePosition` to pin a
// position.
//
// Standalone — no compile dep on the renderer. `wear-compose` is `compileOnly`: consumers are Wear
// apps that already bring it, and keeping it off the published POM avoids pinning a specific
// `wear-compose` alpha onto every consumer (same model as `:splash`/`:notification` for Compose).

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  // Like every other published android-library runtime (`:splash`, `:notification`, …): the
  // maven-publishing convention only runs `configureKotlinCompatibility(...)` when tapmoc is
  // present, so without this the AAR ships without the documented `kotlinCoreLibraries` floor.
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.wear.preview"
  // wear-compose 1.7.0-alpha requires `compileSdk = 37` (mirrors :samples:design-catalog-wear-m3).
  compileSdk = 37

  buildFeatures { compose = true }
}

dependencies {
  compileOnly(platform(libs.compose.bom.stable))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.wear.compose.material3)
  compileOnly(libs.wear.compose.foundation)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "wear-preview-runtime",
    displayName = "Compose Preview — Wear Runtime",
    description =
      "Composable helper that hosts a Wear component in a real single-item TransformingLazyColumn " +
        "so an isolated `@Preview` shows genuine TLC item scaling (scale + fade toward the edges) " +
        "with the component authored in the normal list-item code — pair with a plain `@Preview`, " +
        "`@ScrollingPreview(GIF)`, or a pinned scale position.",
  )
  inceptionYear.set("2026")
}
