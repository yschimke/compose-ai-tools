import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.scroll.core" }

dependencies {
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

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
    artifactId = "data-scroll-core",
    displayName = "Compose Preview — Scroll Data Product (Core)",
    description =
      "Generic Android scroll data-product primitives: Compose test-rule scroll drivers, long-screenshot stitching, Wear pill clipping, and GIF encoding.",
  )
  inceptionYear.set("2026")
}
