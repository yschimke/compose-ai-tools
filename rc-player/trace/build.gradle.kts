plugins {
  id("composeai.base-conventions")
  id("org.jetbrains.kotlin.multiplatform")
}

// `:rc-player-trace` — the tracing seam the whole CMP Remote Compose player stack writes through.
//
// The dependency graph is `trace <- protocol <- runtime <- compose <- wasm host`, so every module
// downstream can open a span without taking a new dependency, and nothing above has to know which
// tracer is actually listening.
//
// **Why a facade rather than calling `androidx.tracing` directly.** `androidx.tracing:tracing:2.x`
// is a Kotlin Multiplatform library, but it publishes only `androidJvm` and `jvm` (desktop)
// variants — there is no wasmJs or Apple klib (checked against 2.0.0-rc01). The player targets
// desktop, wasmJs, and three iOS architectures, so `commonMain` cannot name
// `androidx.tracing.Tracer`
// at all. `RcTracePlatform` is the `expect` seam that fixes that: desktop/JVM delegates to the real
// androidx tracer (and therefore to a Perfetto protobuf trace when `androidx.tracing:tracing-wire`
// installs a driver), wasmJs delegates to the browser's User Timing API so the same span names land
// in a DevTools performance profile, and Apple targets are a documented no-op.
//
// The `androidx.tracing` dependency is deliberately `implementation`, not `api`: consumers trace
// through `rcTrace`/`RcTrace`, and keeping the androidx types off the compile classpath is what
// stops a call site from being written in a way that only compiles on desktop.

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

  // `RcTracePlatform` is an `expect object`, which is still flagged Beta (KT-61573). The seam has
  // to
  // be a singleton — it holds the wasm target's enable flag and mark sequence — and an `expect fun`
  // per operation would scatter the same state across five top-level functions.
  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

  jvm("desktop")
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonTest.dependencies { implementation(kotlin("test")) }
    val desktopMain by getting { dependencies { implementation(libs.androidx.tracing.kmp) } }
  }
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
