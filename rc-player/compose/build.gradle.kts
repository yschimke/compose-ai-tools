plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  id("composeai.maven-publishing")
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

  // Unnamed `jvm()`, not `jvm("desktop")`. This module is published, and the JVM artifact should
  // carry the conventional `-jvm` classifier — the decision `runtimes/slots/build.gradle.kts`
  // already records for a published KMP module. `-desktop` also misnames bytecode that Android
  // resolves perfectly well. The cost is that the source sets are `jvmMain`/`jvmTest` and the test
  // task is `:rc-player-<module>:jvmTest`; nothing in `ci.yml` named the old paths (it runs
  // `allTests`), so the rename is contained to this repo's source layout. See #4063.
  jvm()

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
      // `api`, not `implementation`, for the two that appear in this module's public surface —
      // the ABI dump names `androidx.compose.runtime` (`Composer`, `SnapshotStateMap`) and
      // `androidx.compose.ui` (`Modifier`, `Color`, `FontFamily`, `FontVariation.Settings`) in
      // `RcComposePlayer`'s signature. A consumer needs both on its *compile* classpath to call it,
      // and a published POM that records them as runtime-scope would compile fine for a Compose app
      // that happens to depend on them anyway and fail for one that does not. `compose.foundation`
      // is genuinely internal — nothing from it reaches the surface — so it stays `implementation`.
      @Suppress("DEPRECATION") api(compose.runtime)
      @Suppress("DEPRECATION") api(compose.ui)
      @Suppress("DEPRECATION") implementation(compose.foundation)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    jvmTest.dependencies {
      @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
      implementation(libs.jetbrains.compose.ui.test)
    }
  }
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }

composeAiMavenPublishing {
  coordinates(
    artifactId = "rc-player-compose",
    displayName = "Remote Compose Player — Compose Multiplatform",
    description =
      "RcComposePlayer, a Compose Multiplatform renderer for Remote Compose (.rc) documents on JVM, wasmJs and iOS.",
  )
}
