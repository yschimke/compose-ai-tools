plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.0.0-SNAPSHOT"

android {
  namespace = "ee.schimke.composeai.data.layoutinspector.connector"
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(project(":data-layoutinspector-core"))
  api(project(":daemon:core"))
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}
