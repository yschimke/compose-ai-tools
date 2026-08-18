plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  // `explicitApi()` — every declaration in this module must state its visibility, and every public
  // declaration must state its return type. The player modules were already written this way by
  // convention (`public` modifiers throughout); this makes the convention a compile error rather
  // than a habit, so a `public` that should have been `internal` can't slip into the published
  // surface. See docs/API_STABILITY.md and #4062.
  explicitApi()

  // ABI dump gate. `checkKotlinAbi` (wired into `check` below) diffs the module's real public ABI
  // against the committed dumps in `api/`, so a change to the published surface shows up as a diff
  // in review rather than as a surprise after release. Kotlin's own ABI validation ships in the
  // Kotlin Gradle plugin from 2.2 (still `@ExperimentalAbiValidation` at 2.4), so this needs no
  // extra plugin on the classpath — which is why the player stack gets the gate first rather than
  // waiting for a repo-wide rollout (docs/API_STABILITY.md notes no module had one until now).
  // Both dumps are written: `<module>.api` for the JVM target and `<module>.klib.api` covering the
  // klib-based targets (iOS + wasmJs) together. Regenerate with `./gradlew updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()

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

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
