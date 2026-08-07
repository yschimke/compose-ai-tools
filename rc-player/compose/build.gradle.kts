plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  jvm("desktop")

  // No `iosX64()` here, unlike the rest of the `:rc-player-*` stack (`trace`, `runtime`,
  // `protocol`), which still publishes it. This module is the only one that depends on Compose
  // Multiplatform, and CMP 1.11 stopped publishing the Intel-iOS-simulator variant: 1.10.3 shipped
  // the legacy `uikitX64`/`uikitArm64`/`uikitSimArm64` triple, while 1.11.x replaced it with
  // `iosArm64` + `iosSimulatorArm64` only. Declaring `iosX64()` against 1.11.x therefore fails
  // resolution outright ("Couldn't resolve dependency 'org.jetbrains.compose.runtime:runtime' in
  // 'iosMain' for all target platforms") rather than degrading. Device (`iosArm64`) and the
  // Apple-silicon simulator (`iosSimulatorArm64`) are the targets that still exist; the non-Compose
  // siblings keep `iosX64` because nothing constrains them.
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "RcComposePlayer"
      isStatic = true
    }
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(project(":rc-player-runtime"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.ui)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    val desktopTest by getting {
      dependencies {
        @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
        implementation(libs.jetbrains.compose.ui.test)
      }
    }
  }
}
