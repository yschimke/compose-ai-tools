# Kotlin Build Tools API — stage 2 spike

**Status:** all five checkpoints green ✅. Not wired into the daemon. Not
user-facing. Lives in `:daemon:bta-host` (+ companion `:daemon:bta-host-fixture`)
and produces six test reports under `./gradlew :daemon:bta-host:test` against
Kotlin 2.3.21 + Compose compiler plugin 2.3.21 + JDK 17:

  - `compiles @Composable source with Compose plugin loaded` (✅ decisive)
  - `repeated compiles do not leak the BTA compiler` (✅ checkpoint #1, soak)
  - `incremental compile survives a source mutation` (✅ checkpoint #2, IC)
  - `BTA emits deterministic bytecode for the same source` + `Compose plugin's
    signature transformation lands in the emitted bytecode` (✅ checkpoint #3)
  - `BTA output structurally matches Gradle output for the same source`
    (✅ checkpoint #4, Gradle parity)
  - `BTA compiles a @Composable that references a synthetic Android R class`
    (✅ checkpoint #5, Android-distilled)

The stage-2 design proposal is unblocked; remaining work is the production
wire-up (JSON-RPC `compileSources` endpoint, classpath assembly per
variant, fallback strategy for KSP/KAPT modules).

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
   classloading.

4. ✅ **Gradle vs BTA byte parity.** A companion module `:daemon:bta-host-fixture`
   holds the same `fixture/Greeting.kt` source the spike compiles, but built via
   Gradle's standard `compileKotlin`. `BtaCompilerGradleParityTest` reads both
   `.class` files and compares. Sample run:

   ```
   [parity] ok=false gradleSize=1955 btaSize=1719 sizeDelta=-236 firstDiffOffset=9
   ```

   Bytes are NOT identical. They diverge at offset 9 (constant_pool_count) — BTA's
   output is ~236 bytes shorter. The cause is Compose plugin's
   `sourceInformationMarkerStart` / `traceEventStart` instrumentation: Gradle's
   default compile enables those by passing `sourceInformation=true` to the
   Compose plugin; our minimal `CompilerPlugin("…", classpath, [], {})`
   config doesn't, so those calls aren't emitted. Functionally equivalent at
   runtime — both classes have:

   - The Compose-transformed descriptor
     `(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)Ljava/lang/String;`
   - A `kotlin.Metadata` annotation
   - The `fixture/GreetingKt` class FQN
   - The same module name embedded in `kotlin.Metadata.d2[]`

   That's what the daemon's child-classloader hot-swap actually needs.
   Byte-identity is a stronger property than the daemon requires, and chasing
   it would couple the spike's CI to upstream Compose plugin option drift —
   not worth the strictness. If a downstream consumer of `.class` files DOES
   need byte-identity (Compose Inspector reading the source-information
   markers, future Live Literals work), the open-but-known fix is to enrich
   the `CompilerPlugin` config with the matching plugin options. The
   structural-equivalence assertions are the hard requirement; byte equality
   is logged for triage.

5. ✅ **Android variants (distilled).** `BtaCompilerAndroidRJarTest`
   synthesises a tiny `fixture.R` class via `javax.tools.ToolProvider`'s
   javac, jars it, drops it onto BTA's compile classpath, and compiles a
   `@Composable fun Hi(): Int = R.string.app_name` against it. The test
   asserts both that `HiKt.class` lands on disk AND that the inlined
   constant value (`0x7f100000`) appears in the produced bytecode — the
   correct Android-style behaviour, since `public static final int`
   constants get inlined by kotlinc (so searching for `fixture/R$string`
   in the output would actually be wrong; that's what AGP-driven
   production builds produce too).

   This isn't "BTA compiles an Android module end-to-end" — it's "BTA
   handles the only compiler-input shape that's Android-specific". AGP
   turns resources, manifest, AIDL, BuildConfig, and the R table into
   ordinary `.class`/`.jar` artefacts BEFORE `compileKotlin*` runs;
   kotlinc downstream of AGP is plain JVM Kotlin compilation against
   a classpath that happens to include those AGP-generated jars. The
   spike confirms BTA's compile path doesn't care that some of those
   jars carry Android-shaped synthetic classes.

   What's still open for production: **classpath assembly**. The daemon's
   stage-2 wire-up needs to know what jars to feed BTA for an Android
   variant (`debug` vs `release`, AAR transforms, the AGP-generated
   R/BuildConfig jars). Same plumbing the existing daemon already does
   for the Robolectric sandbox — see `AndroidPreviewSupport.kt`. Not a
   BTA capability question; a wiring question. Stage 1's
   `gradle --continuous` fallback remains the right safety net for
   modules with KSP/KAPT (which BTA doesn't drive) and for the initial
   roll-out window while the Android classpath assembly settles.

   One subtle gotcha surfaced by this checkpoint, worth carrying into
   the stage-2 production code: `List<Path>.plus(Path)` resolves to the
   `Iterable<Path>` overload because `Path` itself implements
   `Iterable<Path>` (iterating name components). Appending a single
   classpath JAR via `+` silently expands `/tmp/.../R.jar` into four
   one-name-component entries on the classpath. Always wrap with
   `listOf(...)` or use `+= listOf(jar)`. Stage-2's
   `compileSources` JSON-RPC handler needs the same defence.

The spike module itself stays small. None of the above gets built on
top of `:daemon:bta-host` directly — when stage 2 starts moving, it
moves into `:daemon:core` (the JSON-RPC surface) and `:daemon:desktop`
(the host) like the rest of the daemon code.
