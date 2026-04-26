plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Regression coverage for issue #136: applying `composePreview` to a
// `com.android.library` module on AGP 9.x used to fail at configuration time
// because the plugin depended on `process${Cap}Resources`, which exists only
// on application variants. Rendering this module verifies the library path
// stays configured + executes end-to-end.

android {
  namespace = "com.example.samplelibrary"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }

  testOptions { unitTests.all { it.jvmArgs("-Xmx2048m") } }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
