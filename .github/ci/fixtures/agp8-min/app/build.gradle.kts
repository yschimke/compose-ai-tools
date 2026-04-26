plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  // `compose-preview` is patched in by `bundle/ci/patch-consumer.py` at CI
  // time — same path the other integration matrix entries take. The
  // `apply-compose-ai.init.gradle.kts` init script wires `mavenLocal()` so
  // resolution finds the locally-published plugin + renderer-android.
}

android {
  namespace = "com.example.agp8min"
  // AGP 8.13 supports compileSdk 36; renderer-android publishes with
  // minCompileSdk=36, so the fixture has to match or AGP rejects the AAR
  // at metadata-merge time.
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example.agp8min"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures { compose = true }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.activity:activity-compose:1.9.3")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
