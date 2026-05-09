import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android { namespace = "ee.schimke.composeai.data.strings.connector" }

dependencies {
  api(project(":data-strings-core"))
  api(project(":daemon:core"))
  api(project(":data-layoutinspector-core"))
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
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
    artifactId = "data-strings-connector",
    displayName = "Compose Preview - Strings Data Product Connector",
    description = "Daemon-side strings data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
