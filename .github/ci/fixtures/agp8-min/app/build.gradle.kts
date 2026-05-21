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
  // compose-bom 2026.05.00 — `compose-bom-stable` in the project's own
  // version catalog, what `:samples:android` and the rest of the codebase
  // build against. Ships material3 1.4.0 (which is what `Previews.kt`'s
  // `VerticalDragHandle` requires; the M3 1.3.1 line in compose-bom
  // 2024.12.01 doesn't have that composable). The agp8-min CI job
  // downgrades this to 2024.12.01 in Phase 1 so the consumer's own
  // preview source fails to compile against the older material3 — the
  // same failure shape a real consumer hits when they pull a new Compose
  // API ahead of their BOM. `compose-preview doctor` warns about the
  // too-old BOM through its `env.compose-bom-version` pre-flight check
  // (grep-based against `build.gradle.kts`, runs before Gradle). Phase 3
  // restores 2026.05.00 and asserts render then succeeds.
  val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.activity:activity-compose:1.9.3")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
