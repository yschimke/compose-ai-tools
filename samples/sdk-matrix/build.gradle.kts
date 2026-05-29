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
//   ./gradlew :samples:sdk-matrix:composePreviewRenderAll \
//     -Pcomposeai.matrix.compileSdk=36 \
//     -Pcomposeai.matrix.targetSdk=36 \
//     -Pcomposeai.matrix.minSdk=24
//
// See `docs/SDK_COMPATIBILITY.md` for the full cell matrix and the documented outcomes.
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

// Defaults pinned to SDK 35 (not 36) so the no-override path renders cleanly under the project's
// default JDK 17 toolchain — Robolectric refuses to bootstrap an SDK 36 sandbox without JDK 21+
// (`DefaultSdkProvider.verifySupportedSdk`), which would fail every regular `preview-baselines`
// run that doesn't set the matrix `-P` overrides. The nightly `sdk-matrix.yml` workflow always
// passes explicit cell values, so it sweeps the full {35, 36, 37} range regardless of the default.
val matrixCompileSdk: Int =
  providers.gradleProperty("composeai.matrix.compileSdk").orNull?.toIntOrNull() ?: 35
val matrixTargetSdk: Int =
  providers.gradleProperty("composeai.matrix.targetSdk").orNull?.toIntOrNull() ?: 35
val matrixMinSdk: Int =
  providers.gradleProperty("composeai.matrix.minSdk").orNull?.toIntOrNull() ?: 24
val matrixSdkOverride: Int? =
  providers.gradleProperty("composeai.matrix.sdkVersion").orNull?.toIntOrNull()
// Robolectric snapshot version probe. When set (e.g. `4.17-SNAPSHOT`) the snapshots repo
// declared in `settings.gradle.kts` is honoured and `resolutionStrategy.force(...)` pins this
// version on every configuration so the test runtime classpath swaps in the snapshot regardless
// of what `renderer-android` compiled against. See `docs/SDK_COMPATIBILITY.md` for the
// snapshot-probe cells and the upstream commit (`0e89b68`) the snapshot picks up.
val matrixRobolectricVersion: String? =
  providers.gradleProperty("composeai.matrix.robolectricVersion").orNull
// Matrix-only escape hatch for `GenerateRobolectricPropertiesTask.MAX_SUPPORTED_SDK`. Production
// consumers never reach for this — auto-detect clamps above-ceiling values so they don't trip a
// runtime sandbox failure. The snapshot probe cells pair this with
// `composeai.matrix.robolectricVersion` so a snapshot Robolectric that ships API 37 can render
// without the task's validator throwing.
val matrixMaxSupportedSdk: Int? =
  providers.gradleProperty("composeai.matrix.maxSupportedSdk").orNull?.toIntOrNull()
// JDK toolchain the Kotlin compile + Test workers fork into. Driven by the workflow's matrix
// `jdk` axis. Defaults to JDK 17 (the project's baseline), but bumps to 21 for cells that
// exercise Robolectric SDK 36+: Robolectric's `DefaultSdkProvider.verifySupportedSdk` refuses
// SDK 36 unless the test JVM is JDK 21+, so the matrix's whole JDK axis only does its job if the
// Test task actually forks into the matrix-selected JDK rather than the project default.
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

if (matrixMaxSupportedSdk != null) {
  afterEvaluate {
    tasks.named(
      "composePreviewGenerateRobolectricProperties",
      ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask::class.java,
    ) {
      maxSupportedSdkOverride.set(matrixMaxSupportedSdk)
    }
  }
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

// Belt-and-braces: the Test task's `javaLauncher` is what actually decides which JDK the test
// JVM forks into. `kotlin { jvmToolchain(N) }` sets it on the AGP-created Test tasks, but a
// fresh `Test` task created later wouldn't inherit. Pin it explicitly so every Test task — now
// and future — honours the matrix's JDK axis.
val javaToolchains = extensions.getByType(JavaToolchainService::class.java)

tasks.withType(Test::class.java).configureEach {
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(matrixJvmToolchain)) }
  )
}

dependencies {
  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.foundation)
  debugImplementation("androidx.compose.ui:ui-tooling")
}
