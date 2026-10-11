// Synthetic single-`@Preview` module driven by `composeai.matrix.*` properties so the nightly
// `sdk-matrix.yml` can sweep compileSdk × targetSdk × minSdk. Doesn't apply
// `composeai.android-conventions` (which pins `compileSdk`). An application module because AGP 9
// removes `targetSdk` from libraries.
//
//   ./gradlew :samples:sdk-matrix:composePreviewRenderAll \
//     -Pcomposeai.matrix.compileSdk=36 \
//     -Pcomposeai.matrix.targetSdk=36 \
//     -Pcomposeai.matrix.minSdk=24
//
// See `docs/SDK_COMPATIBILITY.md`.
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

// Defaults to SDK 35 so a plain run works on the JDK 17 toolchain (Robolectric needs JDK 21+ for
// SDK 36). The nightly workflow always passes explicit values.
val matrixCompileSdk: Int =
  providers.gradleProperty("composeai.matrix.compileSdk").orNull?.toIntOrNull() ?: 35
val matrixTargetSdk: Int =
  providers.gradleProperty("composeai.matrix.targetSdk").orNull?.toIntOrNull() ?: 35
val matrixMinSdk: Int =
  providers.gradleProperty("composeai.matrix.minSdk").orNull?.toIntOrNull() ?: 24
val matrixSdkOverride: Int? =
  providers.gradleProperty("composeai.matrix.sdkVersion").orNull?.toIntOrNull()
// Robolectric snapshot probe (e.g. `4.17-SNAPSHOT`): forced on every configuration so the test
// runtime uses the snapshot. See `docs/SDK_COMPATIBILITY.md`.
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull
// Matrix-only override of `GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK`, so a snapshot
// Robolectric with API 37 can render without the validator throwing.
val matrixMaxSupportedSdk: Int? =
  providers.gradleProperty("composeai.matrix.maxSupportedSdk").orNull?.toIntOrNull()
// JDK toolchain for compile and Test workers, from the matrix `jdk` axis (default 17; SDK 36+ cells
// need 21).
val matrixJvmToolchain: Int =
  providers.gradleProperty("composeai.matrix.jvmToolchain").orNull?.toIntOrNull() ?: 17

composePreview {
  // `composeai.matrix.sdkVersion` is unset by default (auto-detect path); set it from the
  // workflow when a cell is documenting the override branch.
  matrixSdkOverride?.let { sdkVersion.set(it) }
}

if (matrixRobolectricVersion != null) {
  configurations.all {
    resolutionStrategy.force("org.robolectric:robolectric:$matrixRobolectricVersion")
  }
}

afterEvaluate {
  tasks.named(
    "composePreviewGenerateRobolectricProperties",
    ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask::class.java,
  ) {
    // Key the plugin's JDK-aware SDK ceiling off the forked test JVM, not the Gradle JVM.
    buildJavaMajor.set(matrixJvmToolchain)
    if (matrixMaxSupportedSdk != null) {
      maxSupportedSdkOverride.set(matrixMaxSupportedSdk)
    }
  }

  // This module uses `compose-bom-compat` (Compose 1.9.x), which lacks the link-buffer composer
  // flag; the repo default `composePreview.linkBufferComposer=auto` handles that. See
  // `docs/LINK_BUFFER_COMPOSER.md`.
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

kotlin { jvmToolchain(matrixJvmToolchain) }

// `javaLauncher` decides the test JVM; pin it on every Test task, including ones created later.
val javaToolchains = extensions.getByType(JavaToolchainService::class.java)

tasks.withType(Test::class.java).configureEach {
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(matrixJvmToolchain)) }
  )
}

dependencies {
  // This matrix deliberately exercises compileSdk 35/36 clients too. Compose 1.12 itself requires
  // compileSdk 37, so use compose-preview's supported compatibility floor here; the regular samples
  // exercise the current stable BOM.
  implementation(platform(libs.compose.bom.compat))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
