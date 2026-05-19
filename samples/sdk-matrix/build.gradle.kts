// Synthetic single-`@Preview` module driven by `composeai.matrix.*` Gradle properties so the
// nightly `sdk-matrix.yml` workflow can sweep (compileSdk × targetSdk × minSdk) without forking a
// fresh sample per cell. Deliberately does NOT apply `composeai.android-conventions` — that plugin
// pins `compileSdk = 36`, which would defeat the whole point.
//
// `com.android.application` rather than `com.android.library` because AGP 9.x removes `targetSdk`
// from `LibraryDefaultConfig` (library modules aren't supposed to pin a target), and `targetSdk`
// is one of the axes the matrix needs to sweep.
//
// Run locally:
//   ./gradlew :samples:sdk-matrix:renderAllPreviews \
//     -Pcomposeai.matrix.compileSdk=36 \
//     -Pcomposeai.matrix.targetSdk=36 \
//     -Pcomposeai.matrix.minSdk=24
//
// See `docs/SDK_COMPATIBILITY.md` for the full cell matrix and the documented outcomes.
import org.gradle.api.JavaVersion

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

val matrixCompileSdk: Int =
  providers.gradleProperty("composeai.matrix.compileSdk").orNull?.toIntOrNull() ?: 36
val matrixTargetSdk: Int =
  providers.gradleProperty("composeai.matrix.targetSdk").orNull?.toIntOrNull() ?: 36
val matrixMinSdk: Int =
  providers.gradleProperty("composeai.matrix.minSdk").orNull?.toIntOrNull() ?: 24
val matrixSdkOverride: Int? =
  providers.gradleProperty("composeai.matrix.sdkVersion").orNull?.toIntOrNull()

composePreview {
  // `composeai.matrix.sdkVersion` is unset by default (auto-detect path); set it from the
  // workflow when a cell is documenting the override branch.
  matrixSdkOverride?.let { sdkVersion.set(it) }
}

android {
  namespace = "com.example.sdkmatrix"
  compileSdk = matrixCompileSdk

  defaultConfig {
    applicationId = "com.example.sdkmatrix"
    minSdk = matrixMinSdk
    @Suppress("ExpiringTargetSdkVersion")
    targetSdk = matrixTargetSdk
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
