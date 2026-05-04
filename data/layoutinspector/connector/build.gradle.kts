import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android { namespace = "ee.schimke.composeai.data.layoutinspector.connector" }

dependencies {
  api(project(":data-layoutinspector-core"))
  api(project(":data-render-compose"))
  api(project(":daemon:core"))
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-layoutinspector-connector",
    displayName = "Compose Preview - Layout Inspector Data Product Connector",
    description = "Daemon-side layout inspector data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
