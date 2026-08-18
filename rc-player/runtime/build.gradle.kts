plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
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
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(project(":rc-player-protocol"))
      implementation(libs.kotlinx.datetime)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
