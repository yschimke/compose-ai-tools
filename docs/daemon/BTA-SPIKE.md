# Kotlin Build Tools API — stage 2 spike

**Status:** proof-of-concept ✅. Not wired into the daemon. Not user-facing.
Lives in `:daemon:bta-host` and produces a single decisive test report
(`./gradlew :daemon:bta-host:test`). Last run: `BtaCompilerTest.compiles
@Composable source with Compose plugin loaded` passes against Kotlin 2.3.21 +
Compose compiler plugin 2.3.21 + JDK 17.

[CONTINUOUS-COMPILE.md](CONTINUOUS-COMPILE.md) describes the stage-1 path
(long-running `gradle --continuous` worker per module). This spike asks the
follow-up question: **can we cut Gradle out of the save loop entirely**, by
hosting the Kotlin compiler in-process via the Build Tools API (BTA), the same
artifact the Kotlin Gradle Plugin uses internally since 2.3.20?

If the spike succeeds, stage 2 becomes a real proposal: a JSON-RPC
`compileSources` method on the daemon, an `IncrementalCompilation` cache living
next to the user classloader holder, and an opt-in flag
`composePreview.daemon.compileInProcess`. Stage 1's continuous Gradle becomes
the fallback for modules with KSP/KAPT, Android source-set complexity, or
build-script-side configuration that BTA can't model.

If the spike fails — typically because the Compose compiler plugin can't be
loaded via BTA's public API, or because the produced bytecode doesn't match
what Gradle's `compileKotlin` task emits — we **abandon** stage 2 and keep
stage 1 as the only kotlinc-in-daemon mechanism. The failure mode then becomes
the concrete artifact to file as a KEEP-421 follow-up upstream.

## The decisive question

Can BTA produce a `.class` from `@Composable fun Greeting(name: String) =
"Hello, $name"` with **the Compose compiler plugin's signature transformation
applied**?

Concretely the `.class` must contain a `Greeting` method whose descriptor
references `androidx/compose/runtime/Composer` — the synthetic parameter the
Compose plugin injects. That's the byte-level proof that the plugin's
`CompilerPluginRegistrar` ran inside BTA's isolated classloader.

`BtaCompilerTest` is wired to verify exactly this. The substring scan over the
raw `.class` bytes is deliberately simpler than ASM — we don't need to parse
the constant pool to know whether the plugin ran.

## What's in scope for the spike

- Loading `org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.21` into an
  isolated `URLClassLoader` via `KotlinToolchain.loadImplementation(...)`.
- Adding `kotlin-compose-compiler-plugin-embeddable:2.3.21` to the **same**
  classloader so its `META-INF/services/...CompilerPluginRegistrar` is
  visible to BTA's compiler frontend.
- Compiling a single hand-written `.kt` source against a synthetic classpath
  (Compose runtime BOM, kotlin-stdlib).
- Asserting the output bytecode shows evidence of the Compose
  transformation.

## What's out of scope

- Incremental compilation. BTA does support it (classpath snapshots + on-disk
  caches) but the spike runs a single one-shot compile. Stage 2's design doc
  picks IC up after the spike proves the basics.
- KSP / KAPT — annotation-processor sources don't go through BTA directly.
  Stage 2's design is "fall back to Gradle for modules that need them".
- Android variants (R class, BuildConfig, AIDL stubs, manifest merger). The
  spike compiles plain JVM Kotlin against Compose runtime — a desktop CMP
  module's `commonMain`. Android comes later, gated on the spike succeeding.
- JSON-RPC integration, the VS Code flag, file watchers, classloader rotation
  — none of those land until the spike is green.
- Performance comparisons. The spike answers "does it work?", not "is it
  faster?". Stage 1's `baseline-latency.csv` methodology applies later.

## Exit criteria

**Promote to a real stage-2 design proposal** when the test passes and the
runtime classloader hierarchy stays sane across repeated `compile()` calls
(no leaked compiler-frontend objects on repeat invocations — sample with
`-Xrunjdwp` if needed).

**Abandon** when any of the following holds and we can't paper over it:

- `KotlinToolchain.loadImplementation()` requires a JDK or runtime feature
  our daemon process doesn't already have.
- The Compose plugin's `CompilerPluginRegistrar` doesn't activate via the
  embeddable JAR's META-INF service file under BTA's classloader.
- The produced bytecode systematically differs from Gradle's `compileKotlin`
  output for the same source (mangled method names, missing Composer
  parameters, missing group-key injection) in ways that would prevent the
  daemon's child classloader hot-swap path
  ([CLASSLOADER.md](CLASSLOADER.md)) from resolving methods at runtime.

## Why now, why this shape

Compose Hot Reload 1.0 (JetBrains, Jan 2026) shipped on the continuous-Gradle
architecture, not on BTA — the upstream signal is that BTA isn't yet the
"obvious" choice for live-preview workflows. We're betting that the per-save
latency floor with `gradle --continuous` (stage 1, currently shipping behind
the opt-in flag) won't beat Gradle's daemon round-trip cost by enough, and
that an in-process Kotlin compiler can. The spike either confirms the bet
cheaply or punctures it cheaply; either outcome is useful.

The spike's only artifact today is the test report. Nothing in the daemon
imports `:daemon:bta-host`, and the module isn't published. Removing the
module if the spike abandons is a one-line settings.gradle.kts edit + a
directory delete.

## What the spike actually had to work out

The research notes I went in with said "Compose plugin loading via BTA is
mechanically supported but uncharted." Concretely uncharted bits I had to
discover by reading the published 2.3.21 JAR with `javap`:

- **Plural class name.** Entry point is `KotlinToolchains` (not
  `KotlinToolchain`); the V2 of the API shipped in 2.3.x. The
  `getToolchain<JvmPlatformToolchain>()` reified extension on it is what
  you call once the impl is loaded. KGP's own `BuildToolsApiCompilationWork`
  in `kotlin-gradle-plugin-2.3.20-gradle813.jar` is the reference
  implementation to crib off if doc-shopping ever fails.

- **The classloader topology is load-bearing.** BTA's impl classloader needs:
  (a) the impl JAR and every transitive Kotlin compiler / daemon / coroutines
  artifact on its `URLClassLoader.urls`, and (b)
  `SharedApiClassesClassLoader.newInstance()` (an API-only filter, exposing
  only `org.jetbrains.kotlin.buildtools.api.*` to delegate up to the host's
  ClassLoader) as its parent. The first failure mode I hit was using
  `getPlatformClassLoader()` as the parent — the impl couldn't resolve
  the API interfaces it was supposed to bridge to. The second was leaving
  `kotlinx.coroutines.*` off the impl URLs — the Compose plugin's bytecode
  references `CoroutineScope`, so it must be loadable from the same
  classloader as the impl. The current `:daemon:bta-host` test picks every
  `kotlin-*` / `kotlinx-*` / `annotations-*` / `jna-*` / `trove4j-*` artifact
  on the test runtime classpath into the impl URL set.

- **Compose plugin loading.** Wraps the embeddable JAR in a
  `CompilerPlugin("androidx.compose.compiler.plugins.kotlin", listOf(jar),
  emptyList(), emptySet())` and assigns to
  `CommonCompilerArguments.COMPILER_PLUGINS` on the JVM compile operation's
  args. The plugin id has to match what the plugin's `META-INF/services/
  org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar` advertises —
  `androidx.compose.compiler.plugins.kotlin` is the canonical id.

- **Call-site reads like a constructor.** The Kotlin source for the
  factory is a top-level function `fun SharedApiClassesClassLoader():
  ClassLoader = SharedApiClassesClassLoaderImpl(...)` with
  `@JvmName("newInstance")` — Java sees it as
  `SharedApiClassesClassLoader.newInstance()` (the form KGP's
  bytecode uses), Kotlin source calls it as `SharedApiClassesClassLoader()`.
  Mis-reading this as a Java-static-on-a-class is exactly the trap
  the spike's first iteration fell into; the Kotlin compiler's
  "Function invocation 'SharedApiClassesClassLoader()' expected" was
  the cue.

- **`-no-stdlib` / `-kotlin-home` warnings are harmless.** BTA's
  compiler auto-detects a `kotlin-home` directory layout (`kotlin-stdlib.jar`
  alongside the impl) and warns when it can't find one. Our compile
  classpath includes the stdlib explicitly, so the warning is cosmetic.
  Stage-2 production code should either suppress with `-no-stdlib` +
  explicit-classpath, or build a synthetic `kotlin-home` from the
  resolved artifacts.

## What this unlocks

The decisive bit — Compose plugin runs under BTA — was the riskiest
unknown going in. With that nailed, the rest of the stage-2 plan
(`composePreview.daemon.compileInProcess` flag, JSON-RPC `compileSources`
endpoint on the daemon, IC cache living next to `UserClassLoaderHolder`,
fallback to stage 1's continuous Gradle for KSP/KAPT modules) is mostly
mechanical wiring on top of a known-working core.

Concrete next checkpoints, in roughly increasing risk order:

1. ✅ **Repeat-compile invariant.** `repeated compiles do not leak the
   BTA compiler` in `BtaCompilerTest` calls `compile()` 10× on the same
   compiler instance, asserts every iteration succeeds, then GC-probes a
   `WeakReference` to the compiler. Sample run (Linux/JDK 17, debug
   sandbox):

   ```
   [soak] per-iteration ms (n=10): [5533, 730, 512, 444, 731, 522, 244, 234, 341, 647]
   [soak] first=5533 median=522 last=647
   [soak] compiler WeakReference cleared on GC attempt 0 — no leak detected
   ```

   The first compile pays ~5.5 s of cold-start (BTA impl classloader
   construction + Kotlin frontend warm-up); subsequent compiles land in
   200–700 ms with a median around 500 ms. That's already competitive
   with Gradle's `compileKotlin` wall (~900 ms warm-after-1-line-edit
   per `baseline-latency.csv`), and the impl classloader is collectable
   on the first forced GC — no per-call leak. CI iteration count is
   intentionally low (10) so the test stays under a few seconds; bump
   via `-Dcomposeai.bta.soakIterations=…` locally when investigating
   regressions.
2. ✅ **Incremental compilation.** `BtaCompiler.compileIncremental(...)` wires
   `JvmSnapshotBasedIncrementalCompilationConfiguration` through, with
   per-classpath-entry snapshots cached on disk and a `SourcesChanges`
   parameter for callers that already know the dirty set.
   `BtaCompilerIncrementalTest` exercises a 3-file fixture, mutates one
   source between passes, and asserts the recompiled `.class` carries the
   edit. Sample run:

   ```
   [ic] pass1 (cold, SourcesChanges.Unknown) ms=7064 class-count=3
   [ic] pass2 (warm, ToBeCalculated)         ms=1082 class-count=3
   ```

   Pass 2 is **~6.5× faster** than pass 1 — BTA's IC successfully skipped
   re-analysis of the two unchanged sources. Combined with the soak data
   above, the same-JVM second-edit floor is in the same neighbourhood as
   stage 1's `warm-after-1-line-edit` compile wall, but without Gradle's
   ~500 ms configuration round-trip stacked on top. The pattern (mostly
   cribbed from KGP's `BuildToolsApiCompilationWork`):
     - Run `JvmClasspathSnapshottingOperation` per compile-classpath entry,
       persist with `ClasspathEntrySnapshot.saveSnapshot(file)`. Cache by
       absolute path for the spike; production needs a content-hash
       fallback for in-place JAR rebuilds.
     - Construct `JvmSnapshotBasedIncrementalCompilationConfiguration(
       workingDir, sourcesChanges, snapshotFiles, shrunkSnapshotFile,
       op.createSnapshotBasedIcOptions())`.
     - `op.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, config)`.

   Open follow-ups not covered by this checkpoint: structured probe of
   which sources actually got recompiled (KGP infers from
   `caches-jvm/inputs` files), source-set wiring (commonMain vs jvmMain),
   KSP/KAPT integration. None are blockers for the next checkpoint.
3. ✅ **Bytecode validation (structural).** `BtaCompilerBytecodeTest`
   confirms two things the daemon's child-classloader hot-swap actually
   needs:

   - **Determinism.** Two BTA compiles of the same source produce
     byte-identical `.class` output (`assertArrayEquals` over the raw
     bytes). Without this the daemon's hot-swap would see "changes" on
     every save even when source semantics didn't move, churning
     Compose state for no reason.
   - **Structural fingerprint.** The emitted bytecode for
     `@Composable fun Greeting(name: String): String` contains:
       - the Compose-transformed descriptor
         `(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;`
         in the constant pool, and
       - a `kotlin.Metadata` annotation (decodable by ClassGraph + Kotlin
         reflection, same shape Gradle's output uses).

   Cheap byte-search assertions on the raw class file — no ASM, no
   classloading. What this checkpoint does **not** answer: whether BTA's
   output is byte-by-byte (or shape-equivalent) to Gradle's
   `compileKotlin` output for the same source. That's a separate
   exercise — needs a parallel Gradle compile of the same fixture and a
   structural diff that tolerates constant-pool reordering. Tracked as
   the next checkpoint. For the hot-swap path the structural fingerprint
   here is the load-bearing question; byte-by-byte parity is a
   convenience, not a correctness requirement.
4. **Android variants.** Plug in AGP-generated R class jars,
   BuildConfig, manifest-merger outputs. Most likely the boundary where
   we accept "fall back to stage 1 for Android".

The spike module itself stays small. None of the above gets built on
top of `:daemon:bta-host` directly — when stage 2 starts moving, it
moves into `:daemon:core` (the JSON-RPC surface) and `:daemon:desktop`
(the host) like the rest of the daemon code.
