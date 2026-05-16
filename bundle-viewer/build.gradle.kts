@file:Suppress("DEPRECATION")

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  application
}

application {
  applicationName = "compose-preview-viewer"
  mainClass.set("ee.schimke.composeai.viewer.MainKt")
  // Compose Multiplatform Desktop's Skiko loader uses `System.load` for native libs. JDK 24+
  // would otherwise print a 4-line warning on every launch; pre-declaring native access for the
  // unnamed module silences it.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  // Full Compose Desktop runtime — the viewer composes the bundle's `@Preview` composable LIVE
  // inside its own Window, so every Compose API the bundle's classes resolve against has to be
  // on the parent classloader. Bundle's classes load via a child URLClassLoader (see
  // `BundleLoader.kt`); parent-loader Compose wins on every shared symbol.
  implementation(compose.desktop.currentOs)
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  // `androidx.compose.ui.tooling.preview.Preview` lives here. Bundles compiled against the
  // standard Compose `@Preview` annotation only resolve when this artifact is on the classpath.
  implementation(compose.components.uiToolingPreview)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
