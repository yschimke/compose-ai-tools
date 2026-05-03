import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ee.schimke.composeai.data.strings.connector"
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

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
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
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
