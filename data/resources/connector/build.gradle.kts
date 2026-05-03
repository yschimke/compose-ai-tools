import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "ee.schimke.composeai.data.resources.connector"
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(project(":data-resources-core"))
  api(project(":daemon:core"))
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.robolectric)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-resources-connector",
    displayName = "Compose Preview - Resources Data Product Connector",
    description = "Daemon-side resources data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
