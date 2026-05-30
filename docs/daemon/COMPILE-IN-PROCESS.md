# In-process compile — stage-2 production design

**Status:** implemented and shipping behind the experimental, off-by-default
workspace flag `composePreview.daemon.compileInProcess`. Builds on the
spike findings in [BTA-SPIKE.md](BTA-SPIKE.md) (all five checkpoints
green) and on the stage-1 baseline in
[CONTINUOUS-COMPILE.md](CONTINUOUS-COMPILE.md) (`gradle --continuous`
worker, already shipping behind `composePreview.daemon.continuousCompile`).

> **As-built note.** The sections below are the original design. What
> actually landed differs from the "Module layout" sketch in two ways:
> the per-module session is `daemon/core/.../bta/BtaCompileSession.kt`
> (a new class, not a moved `BtaCompiler.kt`), and the spike modules
> `:daemon:bta-host` / `:daemon:bta-host-fixture` were **kept** as
> standalone parity/soak test harnesses rather than removed — nothing in
> production depends on them. The eligibility predicate
> ([`ComposePreviewTasks.detectStageTwoIneligibility`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/ComposePreviewTasks.kt))
> short-circuits KSP, KAPT, `annotationProcessor` dependencies, and KMP
> modules to stage 1. The bench-harness measurement work in § "What we
> expect to measure" landed in
> [#1586](https://github.com/yschimke/compose-ai-tools/issues/1586): the
> `:samples:*-daemon-bench:benchCompileStages` task drives the stage-1 and
> stage-2 legs and writes the graduation verdict
> (`docs/daemon/stage-2-verdict-<target>.md`) against § "Promote / demote
> criteria" below.

This doc describes the next layer: a JSON-RPC `compileSources` endpoint
on `:daemon:core` that hosts the Kotlin compiler in-process via the
Build Tools API, behind a third opt-in flag
`composePreview.daemon.compileInProcess`. When eligible and enabled,
the save loop never leaves the daemon JVM — no Gradle subprocess, no
tooling-API round-trip, no file-watcher debounce.

## Why

Stage 1 (`gradle --continuous`) cut the per-save Gradle plumbing from
~2 s (config + compile + discovery walls in
[`baseline-latency.csv`](baseline-latency.csv)) to ~1 s by keeping
one Gradle invocation resident. The remaining floor is still Gradle's
internal task-execution overhead + its file watcher's quiet-period
debounce (~250 ms). The spike measured a BTA warm compile at
**200–700 ms in-process**, with IC reuse, no Gradle overhead, no
debounce — see [BTA-SPIKE.md](BTA-SPIKE.md) §1 + §2 for the numbers.

Compose Hot Reload 1.0 (JetBrains, Jan 2026) deliberately stayed on
continuous Gradle. The argument they made — "Gradle handles
dependency resolution, KMP source-set wiring, Compose-plugin
configuration, and Android variants for free" — is correct, but
load-bearing only for the **classpath assembly**. Once that's
computed, the Kotlin compiler itself doesn't need Gradle wrapping
it. The spike confirmed BTA + Compose-plugin + Android-style synthetic
R-jar all work in-process; what's left is wire-up.

DESIGN.md § 1's non-goal "Hot kotlinc / compile-daemon integration"
was first exempted by stage 1. Stage 2 is the follow-on, with stage 1
remaining as the fallback for modules where in-process compile can't
work (see § "Eligibility" below).

## How

### Save-loop flow comparison

```
Stage 0 (default before either flag, current ./gradlew render loop):
  save → ./gradlew :m:composePreviewCompile (~2 s) → fileChanged → render
                                                                    ↑
                                                  Daemon hot-swap, no JVM fork

Stage 1 (composePreview.daemon.continuousCompile = true):
  save → worker.waitForNextBuild() (~1 s)        → fileChanged → render
         ↑ gradle --continuous resident, no
           per-save Gradle config wall

Stage 2 (composePreview.daemon.compileInProcess = true):
  save → daemon.compileSources(...)  (~300 ms, IC warm)
         ↑ BtaCompiler in-process; daemon swaps its own child
           classloader and dispatches the render in the same call
       → renderFinished
```

### JSON-RPC surface

One new method on `JsonRpcServer.kt`, two new notifications. Same
shape as the existing `fileChanged` family — see
[PROTOCOL.md](PROTOCOL.md):

```
compileSources({
  moduleId: string,
  // Absolute paths to .kt sources to compile. Daemon resolves the
  // compile classpath itself from the launch descriptor — clients
  // never see it.
  sources: string[],
  // Optional. When the editor knows the dirty set, pass it; otherwise
  // BTA recomputes via SourcesChanges.ToBeCalculated against its IC
  // cache.
  changes?: { modified: string[], removed: string[] },
}) → {
  result: "ok" | "compileError" | "fallback",
  errors?: KotlinCompileError[],
  durationMs: number,
}

// Notifications already exist; we reuse them:
//   discoveryUpdated — fires after compileSources if the preview set
//     drifted (same predicate the existing fileChanged path uses).
//   renderFinished   — fires per re-rendered preview, as today.
```

`result = "fallback"` is the explicit signal that the daemon refused
in-process compile for this call (e.g. the module's classpath has a
KSP-generated dependency that's now stale). VS Code retries via the
existing stage-1 or stage-0 path with no user-visible churn — the
fallback predicate is documented under § "Eligibility" so the routing
decision is deterministic.

### Module layout (additive only)

```
daemon/core/                  EXTENDED — pure JVM, renderer-agnostic
  src/main/kotlin/.../daemon/
    bta/
      BtaCompiler.kt          MOVED IN from :daemon:bta-host, hardened
      BtaCompileSession.kt    NEW — per-module session: IC cache dir
                                    + lazy KotlinToolchains + the
                                    fallback predicate
    JsonRpcServer.kt          ADDS compileSources handler
    UserClassLoaderHolder.kt  UNCHANGED — same child-loader rotation
                                          path the save loop already
                                          uses

daemon/bta-host/              REMOVED — the spike's standalone module
daemon/bta-host-fixture/      REMOVED — and its companion fixture

gradle-plugin/                ADDITIVE ONLY
  src/main/kotlin/.../plugin/daemon/
    DaemonClasspathDescriptor.kt
                              EXTENDS the launch JSON with the BTA
                              compiler classpath (kotlin-build-tools-impl
                              + kotlin-compiler-embeddable + the
                              Compose plugin embeddable). Always
                              populated — the daemon only loads these
                              JARs into BTA's isolated classloader on
                              the first `compileSources` call, which
                              is itself gated by the VS Code workspace
                              setting `composePreview.daemon.compileInProcess`.

vscode-extension/             ADDITIVE ONLY
  src/daemon/
    daemonClient.ts           NEW compileSources() method
    daemonScheduler.ts        Save loop routes through compileSources
                              when the per-module gate is on; same
                              fallback pattern as stage 1's
                              waitForNextBuild() returning null
```

### BTA-side lifecycle

Per-module `BtaCompileSession` holds:

| Field                | Lifetime                    | Notes                                                                                                                                  |
| -------------------- | --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `kotlinToolchains`   | Per daemon JVM              | Lazy. The impl classloader's URLs come from the launch descriptor; same isolation pattern the spike uses.                              |
| `icCacheDir`         | Per daemon JVM              | `build/compose-previews/daemon-state/bta-ic-<moduleId>/`. Cleared on classpath-dirty (Tier-1 signal). Persists across source-only saves.|
| `classpathSnapshots` | Per daemon JVM              | One snapshot file per compile-classpath JAR, content-hashed (NOT path-hashed — production has to survive AAR rebuilds in place; see § "Risks").|
| `lastCompileNs`      | Per call                    | Logged on every `compileSources` so the panel's "edit→update" journey metric reads sane numbers.                                       |

Lifecycle:

- **Daemon spawn:** `BtaCompileSession` is constructed lazily on first
  `compileSources` for the module. The cold compile pays the BTA impl
  classloader bootstrap (~5 s in the spike).
- **Source-only save (Tier 2):** `compileSources` reuses the session;
  `compileIncremental` does the work; IC cache mutates in place.
- **Classpath dirty (Tier 1):** same signal that already recycles
  the user classloader recycles the session — drop the strong
  reference, GC, new session lazy-init on next call. Per-daemon-JVM
  weak-reference probe (same shape as
  [`CLASSLOADER.md` "WeakReference soak probe"](CLASSLOADER.md)).
- **Daemon shutdown:** session is GC'd with everything else; the IC
  cache stays on disk so a fresh daemon picks it up.

### Compiled-classes rotation

Stage 2's BTA writes `.class` files directly into the same directory
the daemon's child classloader is watching:
`build/intermediates/built_in_kotlinc/<variant>/classes/` (Android)
or `build/classes/kotlin/<variant>/main/` (JVM/CMP). The
`compileSources` handler then triggers the existing
`UserClassLoaderHolder.swap()` path — the same one stage 0 and stage 1
use after `fileChanged({kind:source})`. **No new classloader code
needed**; the spike already proved BTA's output is structurally
equivalent to Gradle's output for hot-swap purposes
([BTA-SPIKE.md](BTA-SPIKE.md) §3 + §4).

The render dispatch after compile is also unchanged — same
`renderQueue.drainStale()` the existing save loop calls.

## Eligibility

Stage 2 is **opt-in per workspace**
(`composePreview.daemon.compileInProcess` in VS Code settings). The
gate is off by default. The gradle plugin always populates the BTA
classpath in the daemon launch descriptor, but the daemon only loads
BTA lazily — when the editor's save loop actually calls
`compileSources`, which is gated by the workspace setting. Within an
enabled workspace, the daemon picks the path per module:

| Predicate                                                          | Path     | Why                                                                                                                                                     |
| ------------------------------------------------------------------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Module applies `com.google.devtools.ksp`                           | Stage 1  | KSP isn't driven by BTA. A save in a KSP-processed file would render with stale generated code.                                                          |
| Module applies `org.jetbrains.kotlin.kapt`                         | Stage 1  | Same reasoning as KSP.                                                                                                                                  |
| Module declares any `annotationProcessor` configuration            | Stage 1  | Same reasoning.                                                                                                                                          |
| Module is `commonMain` of an active KMP source set                 | Stage 1  | KMP source-set wiring is non-trivial; spike covered single source set only. Re-evaluate when KMP support is explicitly added.                            |
| Otherwise                                                          | Stage 2  | Eligible for in-process compile.                                                                                                                         |

The predicate is computed at daemon warm time by
`DaemonBootstrapTask`. Re-evaluated on classpath-dirty. The decision
is logged so consumers can see why their module is on a particular
path.

Stage 1 stays available as the universal fallback. Stage 0 (per-save
Gradle invocation) stays as the floor when the workspace flag is off
— exactly the same code path as today.

## What we expect to measure

Implemented by `:samples:{android,desktop}-daemon-bench:benchCompileStages`
(#1586) — it `javaexec`s `:daemon:core`'s `BtaBenchMain`, which drives the
production `BtaCompileSession.compileIncremental()` using the `btaCompile`
block from the module's `daemon-launch.json`. Same baseline methodology as
[`baseline-latency.csv`](baseline-latency.csv). New columns:

```
target,phase,scenario,run,milliseconds,notes
desktop,compile,stage-2-warm-after-1-line-edit,1,<…>,BtaCompileSession.compileIncremental()
desktop,classloader-swap,stage-2-warm,1,<…>,UserClassLoaderHolder rotation (same code as stage 0)
desktop,render,stage-2-warm,1,<…>,unchanged from stage 0
```

Expected ranges from the spike + stage-1 measurements:

| Phase                                | Stage 0 (Gradle) | Stage 1 (continuous) | Stage 2 (BTA in-process) |
| ------------------------------------ | ---------------- | -------------------- | ------------------------ |
| Cold first save after daemon spawn   | ~3 s             | ~3 s                 | ~5 s                     |
| Warm save after 1-line edit, compile | ~950 ms          | ~500 ms              | **~300 ms**              |
| Save → pixel total                   | ~2.5 s           | ~1.5 s               | **~700 ms target**       |

The cold-first-save is WORSE on stage 2 because of BTA's impl
classloader bootstrap — single tax paid per daemon JVM. The warm path
is what justifies the work.

## Risks and mitigations

### Memory cost in the daemon JVM

The launch descriptor balloons by ~80 MB (kotlin-compiler-embeddable +
kotlin-daemon-embeddable + kotlin-build-tools-impl + the compose
compiler plugin). The daemon's resident set climbs by roughly the
same once BTA's frontend is loaded.

**Mitigation:** lazy load. `BtaCompileSession.toolchains` is `by lazy`,
so the daemon JVM only pulls those JARs into its (isolated) BTA
classloader on the first `compileSources` call. Consumers who don't
flip the VS Code workspace flag never reach that call, and the
resident memory stays where stage 1's daemon sat.

### Classpath cache invalidation when a JAR is rebuilt in place

The spike's `BtaCompiler.compileIncremental` keys its classpath-snapshot
cache by absolute JAR path. In production a consumer might rebuild
the same JAR path (e.g. an AAR transform writes to the same temp
location). The cache would serve stale data.

**Mitigation:** content-hash keys, not path keys. SHA-256 over the
JAR's contents on each call. Cheap on Linux's page cache; one-time
~5 ms per JAR. Same shape Tier-1 already uses for the launch-descriptor
fingerprint.

### Compose plugin option drift

Spike's `CompilerPlugin("…", classpath, [], {})` doesn't pass plugin
options. Gradle's `compileKotlin` does — typically
`sourceInformation=true` (the `~236 byte` delta surfaced in §4 of
[BTA-SPIKE.md](BTA-SPIKE.md)). Some downstream tooling (Compose
Inspector, Live Literals) reads those markers.

**Mitigation:** pass `CompilerPluginOption("sourceInformation", "true")`
in the production stage-2 plugin config, plus any other Gradle defaults
the Compose plugin's KGP integration sets. Production code stays
close to byte-parity with Gradle's output; spike's "structurally
equivalent" deliberately punted on this for simplicity.

### KSP/KAPT detection drift

The eligibility predicate reads `project.plugins.hasPlugin(...)` at
daemon-warm time. A consumer can add KSP after warm and the daemon
won't notice until the next classpath-dirty signal. The fallback
predicate `result = "fallback"` exists for exactly this — the daemon
detects the post-warm change at compile time (KSP's output dirs would
be empty or stale) and asks the editor to retry through stage 1.

**Mitigation:** explicit `result = "fallback"` return value, paired
with a panel log line so the consumer sees why the path changed.

### BTA experimental status

Kotlin 2.3.x labels BTA as `@OptIn(ExperimentalBuildToolsApi::class)`.
KGP itself uses BTA by default for Kotlin/JVM at 2.3.20+, so the impl
side is well-exercised; the public API may still drift.

**Mitigation:** version-lock `kotlin-build-tools-impl` to the consumer's
`kotlin` version (read from `libs.versions.toml` / the consumer's
buildscript). API drift surfaces as a `BtaCompiler` compile failure
at our build time, not a runtime failure at theirs — same way the
spike caught the V1 → V2 rename.

### Path-iteration footgun (caught by spike #5)

`List<Path>.plus(Path)` resolves to the `Iterable<Path>` overload —
silently expands a single jar into its name components. The spike's
classpath-assembly code wraps appends with `listOf(...)`. Production
code uses the same idiom; a unit test on the descriptor builder asserts
the bug doesn't sneak back.

## Promote / demote criteria

**Promote stage 2 to the default-eligible path** (still opt-in per
build, but default-on per workspace) when:

- Save → pixel total lands **< 1 s on desktop** and **< 2 s on
  Android** for the standard harness scenarios, sustained across 10
  minutes of continuous editing without the session getting wedged.
- Memory delta vs stage 1 on the same fixture workspace stays under
  +250 MB resident per warm module.
- A real KSP-using consumer module exercises the fallback predicate
  cleanly — stage 1 takes over with no user-visible break.

**Demote stage 2 back to abandoned** (or keep behind the flag
indefinitely) when:

- The warm-path advantage over stage 1 collapses to < 200 ms on
  desktop. The Gradle daemon's tooling-API overhead is already low
  enough that BTA's win comes from removing it; if other costs
  dominate, the work isn't worth the production complexity.
- BTA's bytecode diverges from Gradle's `compileKotlin` in ways the
  daemon's hot-swap can't paper over — particularly Compose
  mangled-method-name parity (the existing
  `S3_5RecompileSaveLoopRealModeTest` Android gate is the canonical
  signal here).
- KGP's BTA integration churns its public API faster than we can keep
  up across consumer Kotlin-version bumps.

In all cases, stage 1 stays in place. Stage 2 is a layer ON TOP of the
existing kotlinc-in-daemon escape hatch, not a replacement.
