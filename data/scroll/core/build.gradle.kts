@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.scroll.core" }

dependencies {
  api(project(":data-render-core"))

  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly("androidx.compose.ui:ui-test-junit4")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-scroll-core",
    displayName = "Compose Preview — Scroll Data Product (Core)",
    description =
      "Generic Android scroll data-product primitives: Compose test-rule scroll drivers, long-screenshot stitching, Wear pill clipping, and GIF encoding.",
  )
  inceptionYear.set("2026")
}
