@file:Suppress("DEPRECATION")

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.runtime)
  implementation(compose.components.uiToolingPreview)

  testImplementation(libs.junit)
}
