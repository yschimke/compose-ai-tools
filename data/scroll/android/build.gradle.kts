import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.scroll.android" }

dependencies {
  api(project(":data-scroll-core"))

  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly("androidx.compose.ui:ui-test-junit4")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
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
    artifactId = "data-scroll-android",
    displayName = "Compose Preview — Scroll Data Product (Android Driver)",
    description =
      "Android (`AndroidComposeTestRule`)-bound scroll driver: drives the first scrollable " +
        "via SemanticsActions.ScrollBy and advances the paused test main-clock. Used by the " +
        "Robolectric renderer for `@ScrollingPreview` capture.",
  )
  inceptionYear.set("2026")
}
