plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  // `compose-preview` is injected at CI time via the CLI-materialised
  // `apply-compose-ai-preview.init.gradle.kts` (passed via `--init-script`
  // — see `.github/workflows/integration.yml`). The init script seeds
  // `mavenLocal()` for the locally-published plugin + renderer-android
  // (via `COMPOSE_PREVIEW_INIT_USE_MAVEN_LOCAL=1`) and auto-applies the
  // plugin to every module with `com.android.application`.
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

// The compose-preview plugin is applied by the CI-bundled init script after
// AGP, so the `composePreview` extension is registered on the project but
// its Kotlin type isn't on this script's buildscript classpath — we can't
// reference it directly in the typed DSL. Configure `sdkVersion = 35`
// reflectively so Robolectric synthesizes a SDK 35 framework instead of
// auto-detecting SDK 36 from `compileSdk = 36`. That keeps the agp8-min
// job on JDK 17 (the realistic toolchain for AGP 8.x consumers): JDK 17 +
// SDK 36 trips `DefaultSdkProvider.verifySupportedSdk`, but JDK 17 + SDK
// 35 + manifest `minSdk = 24` is the documented "rescue path" row in
// docs/SDK_COMPATIBILITY.md. Production consumers can set this via the
// typed DSL: `composePreview { sdkVersion.set(35) }`.
afterEvaluate {
  val ext = project.extensions.findByName("composePreview") ?: return@afterEvaluate
  @Suppress("UNCHECKED_CAST")
  val sdk = ext.javaClass.getMethod("getSdkVersion").invoke(ext)
      as org.gradle.api.provider.Property<Int>
  sdk.set(35)
}

dependencies {
  // compose-bom 2025.11.01 pins compose-ui / compose-runtime to 1.9.5 —
  // matches `compose-bom-compat` in `gradle/libs.versions.toml`, which is
  // the version renderer-android compiles its public API against. Below
  // this BOM the renderer's bytecode references method signatures (e.g.
  // `ComposeUiNode.setCompositeKeyHash`, first shipped in compose-ui 1.9)
  // that the consumer's runtime doesn't have, and `renderPreviews` fails
  // with `NoSuchMethodError` at render time. The agp8-min CI job
  // deliberately downgrades this BOM to 2024.12.01 (Compose 1.7.6) to
  // demonstrate that failure mode and confirm `compose-preview doctor`
  // surfaces the `env.compose-bom-version` finding, then bumps it back to
  // exercise the success path. See `.github/workflows/integration.yml`,
  // the `agp8-min` job.
  val composeBom = platform("androidx.compose:compose-bom:2025.11.01")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.activity:activity-compose:1.9.3")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
