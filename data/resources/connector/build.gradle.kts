plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.0.0-SNAPSHOT"

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
