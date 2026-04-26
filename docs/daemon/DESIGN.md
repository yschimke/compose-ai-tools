# Persistent preview server — design

> **Status:** experimental design proposal. v1 ships behind `composePreview.experimental.daemon=true`. The Gradle `renderPreviews` task remains the always-available fallback and the CI-canonical render path.

## 1. Goals & non-goals

**Goals**

- Sub-second preview refresh for a single focused preview after a no-classpath-change file save in VS Code.
- Eliminate Gradle configuration, JVM fork startup, and Robolectric sandbox bootstrap from the per-save hot path.
- Keep the existing `renderPreviews` Gradle task path untouched and always available.

**Non-goals (v1)**

- Per-project (cross-module) sandbox sharing — each consumer module gets its own daemon JVM.
- CLI / MCP daemon mode — the `compose-preview` binary keeps using the Gradle task.
- Replacing `renderPreviews` — daemon fronts it for the editor loop only.
- Hot kotlinc / compile-daemon integration — out of scope; we still let Gradle drive Kotlin compilation.
- Tier-3 dependency-graph reachability index — v1 uses a conservative "module-changed = all previews stale, filtered by visibility" rule.

## 2. Verdict on feasibility

The current pipeline is ~80% ready. The Robolectric/Compose render code is **not structurally tied to JUnit's lifecycle** — it leans on `ParameterizedRobolectricTestRunner` for sandbox bootstrap and on `createAndroidComposeRule<ComponentActivity>()` for activity setup, but neither is load-bearing. The `mainClock.autoAdvance=false` + `advanceTimeBy(...)` pattern, the per-preview `RuntimeEnvironment.setQualifiers()`, and the `setContent { ... }` capture loop are all composition-scoped, not test-scoped.

The two actual blockers are:

1. **Classpath immutability.** Robolectric's `InstrumentingClassLoader` is created once per sandbox config and caches shadow installations and native libs. Any classpath mutation (a new dependency, a Compose version bump, an AAR change) requires tearing down the sandbox. The daemon must detect this and recycle cleanly.
2. **Discovery is currently a full ClassGraph re-scan.** `DiscoverPreviewsTask` has no per-file mapping — `PreviewInfo` carries `className` but not the source `.kt` path. The daemon needs that mapping (cheap to add) plus a scoped scan path.

`System.exit`, JVM-wide hooks, and global statics that would prevent reuse are not present. `PixelSystemFontAliases.seedSystemFontMap()` is idempotent and process-cached. `ShadowPackageManager` registrations persist across previews but are additive.

## 3. Why not Robolectric Simulator

`org.robolectric.simulator.Simulator` (Robolectric 4.15+) is explicitly "highly experimental, proof-of-concept stage" and exposes only `start()` — it launches an interactive preview window of your app's main `Activity`. It's meant for human debugging of a single Activity, not headless batch rendering of `@Composable` functions to PNG. There's no documented way to drive it from `main()`, no API to swap compositions or capture frames programmatically.

We do **not** want Simulator. What we want is a long-lived process that bootstraps a Robolectric sandbox once, exposes a render entry point that reuses that sandbox, and talks to VS Code over IPC. The Roborazzi capture path (`captureRoboImage` → `HardwareRenderingScreenshot`) stays — that's the part that actually works correctly under Robolectric.

## 4. Architecture

```
VS Code extension
   │
   │  JSON-RPC over stdio
   ▼
preview-daemon (one JVM per consumer module)
   │
   ├── ManifestWatcher       — debounced file events from VS Code
   ├── IncrementalDiscovery  — re-scans only changed source class dirs
   ├── FocusTracker          — current visible-preview set from VS Code
   ├── RenderQueue           — coalesces, prioritises focused previews first
   └── RobolectricHost       — single hot sandbox + warm spare
            │
            └── ComponentActivity + ComposeTestRule, recycled per preview
```

**Per-module, not per-project.** Robolectric sandbox config is a function of the consumer module's classpath + AndroidX versions + `compileSdk`. Two modules in the same project can legitimately have incompatible Compose versions (renderer-vs-consumer alignment is documented in [docs/RENDERER_COMPATIBILITY.md](../RENDERER_COMPATIBILITY.md)). One sandbox per module sidesteps this. A future per-project mode is possible if we share the renderer JAR plus isolate per-module classloaders under a parent — out of scope for v1.

The daemon is **launched by VS Code** (replacing the `GradleApi.runTask("renderPreviews")` call in `gradleService.ts`) but **bootstrapped by Gradle** — we still need Gradle once at startup to compute the test classpath, JVM args, and `robolectric.properties` exactly the way `AndroidPreviewSupport.kt` does today. New Gradle task `composePreviewDaemonStart` emits a JSON descriptor (classpath, JVM args, system props, java launcher path); VS Code execs `java` with those args.

A manual `./gradlew composePreviewDaemonStart --foreground` mode is also available, for debugging the daemon without VS Code in the loop.

## 5. IPC contract (sketch)

JSON-RPC over stdio.

**VS Code → daemon (notifications):**

- `setVisible({ ids })` — currently visible preview cards in the panel; updates on scroll/resize.
- `setFocus({ ids })` — active preview (click, file scope change). Subset of visible. Rendered first.
- `fileChanged({ path, kind: "source" | "resource" | "classpath" })` — saved file event.
- `renderNow({ previews, tier: "fast" | "full" })` — explicit user action.
- `shutdown()`.

**Daemon → VS Code (notifications):**

- `discoveryUpdated({ added, removed, changed })`
- `renderStarted({ id })`, `renderFinished({ id, pngPath, tookMs, metrics })`, `renderFailed({ id, error })`
- `classpathDirty({ reason })` — daemon will exit; VS Code re-bootstraps.
- `sandboxRecycle({ reason, ageMs, renderCount })` — informational; expected periodically.
- `daemonWarming({ etaMs })` — recycle in progress, no spare ready.

The protocol must be locked in Phase 0 of the implementation work — see [TODO.md](TODO.md). Both daemon and VS Code client teams depend on the protocol shape being stable.

## 6. Module layout

```
renderer-android/                    UNCHANGED — RobolectricRenderTest.kt etc.
renderer-android-daemon/             NEW — depends on renderer-android
  src/main/kotlin/.../daemon/
    DaemonMain.kt                    JSON-RPC server, lifecycle, signal handling
    DaemonHost.kt                    Holds Robolectric sandbox open
    RenderEngine.kt                  Per-preview render body (initially duplicated)
    IncrementalDiscovery.kt          Tier-2 scoped ClassGraph
    DependencyIndex.kt               Tier-3 ASM walk + reverse index (v2)
    ClasspathFingerprint.kt          Tier-1 dirty detection
    SandboxScope.kt                  Per-classloader storage helper
    ProcessCache.kt                  Process-level pure-data cache helper
    SandboxLifecycle.kt              Measurement + recycle policy + warm spare
    protocol/
      Messages.kt                    @Serializable request/response types

gradle-plugin/                       ADDITIVE ONLY (one helper extraction)
  src/main/kotlin/.../plugin/daemon/
    DaemonBootstrapTask.kt           Emits launch-descriptor JSON
    DaemonExtension.kt               composePreview.experimental.daemon { … }
    DaemonClasspathDescriptor.kt     Serialises the JVM launch spec

vscode-extension/                    ADDITIVE ONLY (one router shim)
  src/daemon/
    daemonClient.ts                  JSON-RPC over stdio
    daemonProcess.ts                 Spawn/respawn/health
    daemonProtocol.ts                Types mirroring Messages.kt
    daemonGate.ts                    Feature-flag check + fallback to gradleService

samples/
  android-daemon-bench/              NEW — latency harness, diff against existing PNGs
```

## 7. Sharing strategy — what crosses the boundary

**`renderer-android-daemon` depends on `renderer-android`** for already-extracted helpers that have no test-runner coupling: `AccessibilityChecker`, `GoogleFontInterceptor`, `AnimationInspector`, `ScrollDriver`, `PixelSystemFontAliases`, `RenderManifest`, `PreviewRenderStrategy`. These are already separate files and safe to import.

**`RobolectricRenderTest.kt` itself is NOT a dependency.** The per-preview render body (qualifiers + `setContent` + `advanceTimeBy` + `captureRoboImage`) is **duplicated** into `RenderEngine.kt` for v1, because:

- The daemon path is experimental; if it diverges, the JUnit path is untouched.
- Extracting the render body from a 1500-line test class into a shared helper is a real refactor that risks the working path. Not worth it before the daemon proves itself.
- Reconciliation is a v2 task: once the daemon is stable for a release or two, extract the shared body into `renderer-android` and have the test class call into it. CI gate: byte-identical PNGs from both paths against `samples/android`.

**Drift detection:** `samples/android-daemon-bench` renders the full `samples/android` set via the daemon and pixel-diffs against the PNGs from `samples:android:renderAllPreviews`. Runs on every PR. First diff = either a real bug or a missed port.

**One shared-helper extraction in `gradle-plugin`.** The daemon needs the same classpath, JVM args, and system properties that `AndroidPreviewSupport` builds today. Hoist `buildTestClasspath(variant)` and `buildJvmArgs(variant)` into a package-private `AndroidPreviewClasspath.kt`; existing `registerAndroidTasks` calls the helpers instead of inlining. Behaviour byte-identical, callers unchanged otherwise. Existing functional tests catch any regression.

## 8. Staleness cascade — when do we actually re-render?

A four-tier cascade. Each tier is cheaper than the next; stop at the cheapest "no work" answer.

### Tier 1 — project fundamentally changed

**Trigger:** classpath JAR list, Compose/AndroidX versions, `compileSdk`, Robolectric config, or `robolectric.properties` content changed.

**Cheap signal:** SHA-256 over a small fixed set: `libs.versions.toml`, all `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `local.properties`. Recompute only on file save in those paths.

**Authoritative signal:** SHA over the resolved test runtime classpath JAR list (paths + mtimes), computed at daemon start and re-checked on cheap-signal hit.

**Action:** emit `classpathDirty`, exit cleanly. VS Code re-runs `composePreviewDaemonStart` and the new daemon comes up with the new classpath. Do **not** swap classloaders in-place — that's where leaks and version-skew bugs live.

### Tier 2 — preview list possibly changed

**Trigger:** edit to a `.kt` file that either currently contributes previews or might newly contribute one.

**"Currently contributes" set is free** once we add `sourceFile: String?` to `PreviewInfo` (kotlinc writes the `SourceFile` bytecode attribute; ClassGraph exposes it). Save to a file in this set → re-run discovery scoped to just that file's compiled classes.

**"Might newly contribute" via cheap pre-filter:** regex-grep the saved file's text for `@Preview` (or any registered multi-preview annotation name we already know). Match → escalate to discovery. No match → Tier 2 clean. ~1ms per save, no false negatives unless the user defines a brand-new multi-preview meta-annotation in the same save (rare; recovered by the next save touching anything).

**Incremental discovery scope:** ClassGraph filtered to a single classpath element / package. After kotlinc rebuilds, re-scan only `build/.../classes/` paths whose mtime moved. Diff against cached `previews.json`, emit `discoveryUpdated`.

### Tier 3 — a preview's render output may have changed

This is the hard tier. `FooPreview()` calls `BarComposable()` defined in another file; `BarKt` changed; `FooPreview` is now stale even though `FooKt` wasn't touched.

**v1 conservative:** any `.kt` change inside the module's source set marks **every preview in the module** as stale. Combined with Tier 4 (focus filter), the waste is bounded — we only re-render what the user is looking at.

**v2 precise (deferred):** per-preview reachable-class set built via ASM walk at discovery time. Reverse index `class → previews that transitively reference it`. Map class → sourceFile via the `SourceFile` attribute. Stale set = union of reverse-index lookups for changed classes. Cost: one ASM walk per preview at discovery, ~ms per class.

**Resources:** treat any `res/**` change as "all previews in module stale" for v1. Resource edits are infrequent enough that brute-force + visibility filter is fine.

### Tier 4 — is the user looking at this?

**State from VS Code:**

- `setVisible({ ids })` — preview cards currently visible in the panel webview (not just the file scope; the actual visible cards).
- `setFocus({ ids })` — active selection (click, hover, file scope change). Rendered first.

**Render policy:**

- Stale ∩ visible → render now, in priority order (focus first).
- Stale ∩ not-visible → mark stale, render lazily on scroll-into-view, or background after the visible queue drains.
- Not stale → no-op.

**Coalescing:** rapid saves produce overlapping stale sets. The render queue dedupes by preview ID. If a render is in-flight when its preview is re-marked stale, mark "needs another pass after this one finishes" rather than cancelling — Robolectric mid-render cancellation is a leak source.

### Cost shape

| Tier | Per-save cost when clean | Per-save cost when dirty |
|------|--------------------------|--------------------------|
| 1    | 4 file SHAs              | daemon restart (~5s)     |
| 2    | regex grep of one file   | scoped ClassGraph + diff (~50–200ms) |
| 3    | reverse-index lookup (μs) | N × per-preview render (visible only) |
| 4    | set intersection          | render                   |

Clean save (file in module, no previews depend on it, user not looking) → ~5ms total. That's the floor.

## 9. Sandbox lifecycle

### Bootstrap

The daemon runs a single dummy `@Test` whose body blocks on a `LinkedBlockingQueue<RenderRequest>` until shutdown. This holds a Robolectric sandbox open without re-implementing sandbox setup — we inherit all the `robolectric.properties` plumbing for free. The "test" never returns; the JVM exits when the daemon stops.

### Per-preview render loop

Prologue:

1. Drain ShadowPackageManager records added by the previous preview (see § 11).
2. Reset `RuntimeEnvironment.setQualifiers/setFontScale` for the new preview.
3. Re-create `ComponentActivity` (cheaper than risking unknown reuse leaks).

Render body: same as `RobolectricRenderTest` — `setContent { ... }`, `mainClock.autoAdvance = false`, `advanceTimeBy(CAPTURE_ADVANCE_MS)`, `captureRoboImage(...)`.

Epilogue:

1. `setContent { }` (empty) to give Compose a frame to dispose `LaunchedEffect` / `DisposableEffect`.
2. Encode bitmap, then `bitmap.recycle()`.
3. Close any `HardwareRenderer` / `ImageReader` opened by the capture path.

### Recycle policy

Trigger on any:

- **Heap (post-GC) >** `daemon.maxHeapMb` (default 1024). Hard ceiling.
- **Heap drift > 30% over rolling window of 50 renders.** Catches slow leaks before the ceiling.
- **Render time drift > 50% over rolling window.** Catches GC-pressure leaks.
- **Class histogram delta > Y for any tracked class over rolling window.** Catches known leak shapes early.
- **Render count >** `daemon.maxRendersPerSandbox` (default 1000). Belt-and-braces.
- **`leakSuspected` event from active detection (§ 10).** Immediate.

Each trigger emits `sandboxRecycle({ reason, ageMs, renderCount })`.

### Warm spare

The naive recycle is "drop sandbox, GC, build new one" — a 3–6s user-visible pause. Avoid that:

- Daemon keeps two sandbox slots: `active` and `spare`.
- Background thread builds a new `spare` after every recycle and at startup.
- Recycle = atomically swap `spare → active`, schedule old `active` for teardown, kick off building a new `spare`. User-visible cost on recycle: zero, assuming spare is ready.
- Spare not ready → block the next render, surface `daemonWarming` to VS Code so the panel shows a spinner. If this happens twice in a row, bump recycle thresholds.

Spare cost: doubles daemon idle memory. Configurable via `daemon.warmSpare=false` for memory-constrained dev machines.

### Sandbox teardown verification

Drop the strong sandbox reference, force GC, then check the WeakReference to the sandbox classloader. If it doesn't clear within 2 GCs → log `sandboxLeaked`. After 3 `sandboxLeaked` events, exit cleanly; VS Code re-bootstraps. This is the ultimate safety valve — the JVM permanently leaks per leaked sandbox, so we cap accumulation.

## 10. Memory leak defense in depth

Three layers, plus warm-spare to hide cost, plus proactive fixes for known leak shapes.

### Layer 1 — measure on every render (always on)

Cheap (<5ms), in-band, emitted on `renderFinished`:

- Heap after GC: `MemoryMXBean.getHeapMemoryUsage().used` post-GC.
- Native/off-heap: `BufferPoolMXBean` for direct buffers; `Debug.getNativeHeapAllocatedSize()` inside the sandbox for HardwareRenderer + Bitmaps.
- Class instance counts: programmatic `jcmd GC.class_histogram` via `DiagnosticCommandMBean.invoke("gcClassHistogram")`. Track `Composition`, `Recomposer`, `ComposeView`, `ComponentActivity`, `Bitmap`, `HardwareRenderer`, `ImageReader`.
- Render time (already measured).
- Sandbox age: render count + wall-clock since sandbox start.

Bench harness consumes this as CSV; CI soak test asserts bounded growth.

### Layer 2 — active leak detection (periodic, opt-in)

Every Nth render (default 50) or via `--detect-leaks` daemon flag:

- **Weak-reference probe.** Hold `WeakReference` to activity, composition, root `View`. After the next preview's `setContent` swap, force GC twice with 50ms sleep; check if previous refs cleared. Anything reachable → emit `leakSuspected` with class name and last-render context.
- **LeakCanary JVM (`leakcanary-jvm-test`).** On-demand via `--detect-leaks=heavy`. Heavy (seconds), runs Shark on a heap dump for reference chains. Worth wiring up because reference-chain output is exactly what we'd ask for in a bug report.
- **JFR.** Always enabled with a 5MB in-memory ring buffer. On `leakSuspected` or recycle, dump JFR to `.compose-preview-history/leaks/`. Negligible cost.

### Layer 3 — recycle (see § 9)

### Layer 4 — known leak shapes, fix proactively

Render loop's per-preview prologue/epilogue handles these without waiting for detection:

- **Compose disposal.** Empty `setContent { }` flush before teardown, so `LaunchedEffect`/`DisposableEffect` cleanup runs.
- **Activity recreate per preview** (v1 default; reuse only if bench shows it's necessary).
- **Bitmap recycle** after encoding.
- **HardwareRenderer/ImageReader** closed in `finally`.
- **ShadowPackageManager adds** tracked and reversed (see § 11).

## 11. Scope discipline — `SandboxScope` and `ProcessCache`

The invariant: **sandbox death wipes everything written by our helpers.** Helpers needing cross-render state declare a scope; recycle path doesn't need bespoke per-helper cleanup.

### Two storage tiers

**`ProcessCache`** — JVM-process-level, survives sandbox recycle. **Only pure data**: bytes, strings, primitives, paths. Anything that could hold a transitive reference to a class loaded by the Robolectric classloader is banned.

Examples that fit: downloaded GoogleFont bytes (`Map<String, ByteArray>`), the disk cache directory, parsed manifest config.

**`SandboxScope`** — keyed off the current sandbox's classloader via `WeakHashMap<ClassLoader, MutableMap<String, Any>>`. Holds anything referencing sandbox-loaded classes: `Typeface` registrations, the GoogleFont interceptor instance with its Compose hook, `ShadowPackageManager` add-records, cached `Class<?>`, `Recomposer` instances, mid-render `Bitmap` refs.

When the sandbox is dropped and the classloader becomes unreachable, the WeakHashMap entry collects automatically. The "recycle" mechanic is just "drop active sandbox reference and GC."

### Helper

```kotlin
object SandboxScope {
  private val store = WeakHashMap<ClassLoader, MutableMap<String, Any>>()

  @Suppress("UNCHECKED_CAST")
  fun <T : Any> get(key: String, init: () -> T): T {
    val cl = Thread.currentThread().contextClassLoader  // Robolectric sets this
    val map = synchronized(store) { store.getOrPut(cl) { mutableMapOf() } }
    return synchronized(map) { map.getOrPut(key) { init() } } as T
  }

  fun activeSandboxCount(): Int = synchronized(store) {
    System.gc(); store.size  // diagnostic; not hot path
  }
}
```

`activeSandboxCount()` is the leak canary — after a recycle and 2 GCs it should drop by one. If it doesn't, something on the process-level path is pinning a sandbox classloader.

### Helper audit

| Helper | Today | Daemon-safe? | Action |
|--------|-------|--------------|--------|
| `GoogleFontInterceptor` (disk cache) | Disk files keyed by URL hash | yes (ProcessCache shape) | leave |
| `GoogleFontInterceptor` (Compose hook + `Typeface` cache) | Hook into sandbox-loaded Compose runtime, holds `Typeface` refs | currently a singleton | wrap behind `SandboxScope.get("googleFontInterceptor") { … }` in daemon path; JUnit path unchanged |
| `PixelSystemFontAliases.seedSystemFontMap()` | `Map<String, String>` of name → path | yes (pure strings) | verify; if it secretly holds a `Typeface`, move to SandboxScope |
| `ShadowFontsContractCompat` | Robolectric `@Implements` shadow | yes (sandbox-bound by definition) | leave |
| `ShadowPackageManager` adds | No record of what *we* added | no — leaks across previews within a sandbox (correctness, not memory) | `SandboxScope.get("pmAdds") { … }`; reverse adds in per-preview epilogue |
| `AccessibilityChecker` | Per-render ATF state | yes | leave |
| `AnimationInspector` | Hooks into Compose recomposer | sandbox-loaded refs | SandboxScope it |
| `RuntimeEnvironment.setQualifiers/setFontScale` | Robolectric internal static | yes (lives inside sandbox classloader) | leave |
| Manifest / config parsing | Pure data | yes | leave |

The two real fixes are GoogleFont interceptor wrapping and ShadowPackageManager add-tracking.

### JUnit path unchanged

The original `RobolectricRenderTest` runs one sandbox per Gradle test fork JVM, then exits. Process-level statics in helpers don't cause leaks there because the JVM dies. So the `SandboxScope` discipline is **daemon-module-only** — existing helpers in `renderer-android` keep working unchanged.

### Lint to keep the discipline

A small static check in the daemon module's build: scan for `companion object` / `object` declarations holding any field whose declared type comes from Compose, AndroidX, or Android framework packages. If found → fail with "use SandboxScope." Cheap, catches the regression class that's easiest to introduce by accident.

## 12. VS Code extension routing

One shim, one place. In `extension.ts` where `gradleService.renderPreviews(...)` is called today:

```ts
const renderer = daemonGate.isEnabled(config)
  ? daemonClient
  : gradleService;
await renderer.renderPreviews(module, tier);
```

`daemonGate` checks `composePreview.experimental.daemon` and verifies daemon health. On daemon failure, falls back to `gradleService` automatically and surfaces a notification. Existing `gradleService.ts`, file watcher, and debouncer in `extension.ts` are untouched — the daemon client receives the same calls, plus visibility/focus signals from the webview.

## 13. Latency budget

Estimates below; **measured baseline captured by P0.1 — see [`baseline-latency.csv`](baseline-latency.csv)** + [methodology sidecar](baseline-latency.md). The bench module is `:samples:android-daemon-bench`. Re-run with `./gradlew :samples:android-daemon-bench:benchPreviewLatency`.

| Phase | Cold | Warm (Gradle daemon hot, no code change) | Daemon warm |
|-------|------|------------------------------------------|-------------|
| Gradle config + up-to-date checks | 3–8s | 1–3s | 0 |
| `compileKotlin` (single file) | — | 1–4s | 1–4s |
| `discoverPreviews` ClassGraph scan | 1–3s | 1–3s | 10–100ms (incremental) |
| Test JVM fork + Robolectric sandbox init | 3–6s | 3–6s | 0 |
| Render N previews | 0.3–1s each | 0.3–1s each | 0.3–1s each |

Daemon-warm floor for a single focused preview: **kotlinc (1–2s) + 1 render (0.3–1s) ≈ 1.5–3s**. Sub-second is achievable when the user pre-saves a class-only file with a non-source change, or with a v2 kotlinc-daemon path. v1 target: **< 1s for a single focused preview when no kotlinc work is needed; < 3s with kotlinc.**

### Measured baseline (Ryzen 9 3900X, JDK 21, Gradle 9.4.1, AGP 9.2.0, 5 trivial previews)

Median per (phase, scenario) from the bench, rounded:

| Phase         | Cold (median) | Warm-no-edit | Warm-after-1-line-edit |
| ------------- | ------------- | ------------ | ---------------------- |
| `config`      | 1.3s          | 0.55s        | 0.53s                  |
| `compile`     | 1.7s (incl. config) | 0.5s (UP-TO-DATE) | 0.9s (incl. config) |
| `discovery`   | 1.2s (incl. config) | 0.55s (UP-TO-DATE) | 0.7s (incl. config) |
| `forkAndInit` | 2.9s          | 0.6s (renderPreviews UP-TO-DATE) | 1.7s |
| `render`      | 5.5s for 5 previews ≈ 1.1s each | 0 (UP-TO-DATE) | 5.5s for 5 ≈ 1.1s each |

Surprises versus the estimated table:

- **Cold Gradle config is ~1.3s, not 3–8s.** The estimate was conservative; on this machine config is consistently sub-1.5s once toolchains are downloaded. Likely much higher on first-ever run where toolchain provisioning dominates — the bench skips that.
- **`forkAndInit` warm-after-edit is ~1.7s, not 3–6s.** Test JVM fork + Robolectric init was estimated as a fixed ~5s cost; in practice the JVM fork itself is < 1s on a warm OS page cache and Robolectric sandbox init for 5 previews is closer to ~1s. The estimate was sized for `:samples:android` (heavier classpath, more shadows). The daemon's value-add here is closer to ~2s amortised, not ~5s — still a meaningful win, especially compounded across many edits.
- **Render is at the high end of 0.3–1s/preview.** 1.1s/preview on this trivial workload sets the floor — the daemon path can't beat this without changes to the per-preview render body itself (out of v1 scope).
- **Pure-comment edits leave `renderPreviews` UP-TO-DATE.** Because kotlinc emits identical bytecode for comment-only changes, the downstream input snapshots don't budge. The bench therefore uses a **string-literal swap** to force a full pipeline run; comment-only edits in the wild will measure as `warm-no-edit` performance (i.e., near-instant).

The headline takeaway: **the cold→warm-after-edit delta on this machine is ~12s → ~9s** (`config + compile + discovery + forkAndInit + render`), with the daemon's addressable surface being the **~2.5s of `config + forkAndInit`** in the warm-edit case. Hitting the v1 sub-second target requires both eliminating that 2.5s and short-circuiting the per-preview render to a single focused preview (saving ~4.4s of the 5.5s render row). Both are designed for; the bench is now in place to verify.

## 14. Validation strategy

Three checkpoints, each a buildable artifact:

1. **Bench harness** (week 1, before any rewrite). `:samples:android-daemon-bench:benchPreviewLatency` times: cold render, warm render with no edit, warm render after a 1-line edit. Captures baseline. Re-run after each phase.
2. **Headless prototype** (week 1). One-off `main()` that uses the dummy-`@Test`-holds-sandbox pattern, renders 10 previews from `samples/android` in a loop, reading manifest entries from stdin. If sandbox reuse + composition replacement works for 10 iterations without leaks or visual drift vs. baseline PNGs → architecture validated. If not, we know early.
3. **End-to-end with VS Code** (week 2). Daemon + extension behind the flag. Compare render-after-save latency against bench numbers. Acceptance: ≥5× speedup on warm path for a single focused preview, no visual regressions in `samples/android` PNG diffs.

## 15. CI gates before un-flagging

1. `samples:android-daemon-bench:renderAll` produces PNGs that pixel-diff identical to `samples:android:renderAllPreviews`.
2. 1000-render soak test in a single sandbox completes without OOM, with bounded heap growth (< 50MB drift over the run).
3. Tier-1 dirty detection correctly recycles the daemon on a `libs.versions.toml` bump in a synthetic functional test.
4. End-to-end save→render latency < 1s for a single focused preview on `samples/android`, on the reference dev machine. Baseline captured before any of this work starts.
5. Zero `sandboxLeaked` events and ≤ 2 `sandboxRecycle` events over the 1000-render soak.

## 16. Risks

- **Robolectric sandbox memory leak over many renders.** Mitigated by the multi-layer detection in § 10 and the soak gate in § 15.
- **Hidden global state in renderer.** `ShadowPackageManager` adds, GoogleFont interceptor caches, font alias seeding. Mitigated by the helper audit in § 11 and the `SandboxScope` discipline.
- **Classpath drift undetected.** Daemon misses a `libs.versions.toml` bump and silently renders against stale code. Mitigated by Tier-1 fingerprint over both file SHAs and the resolved classpath JAR list.
- **AGP/Robolectric version churn.** Pipeline is already pinned to Robolectric SDK 35 with known-fragile combinations. Daemon doesn't change that surface but extends "things that can break." Mitigated by pinned versions + samples in CI on bumps.
- **VS Code lifecycle.** Daemon must shut down on extension deactivate / window close, not leak JVMs. Mitigated by parent-PID watchdog + idle timeout.
- **Concurrency.** Two saves arriving 100ms apart racing. Mitigated by single-threaded render queue + dedup-by-preview-ID + "needs another pass" flag.
- **Drift between duplicated render body in `RenderEngine.kt` vs `RobolectricRenderTest.kt`.** Mitigated by per-PR pixel-diff gate. Reconciled in v2 by extracting shared body.

## 17. Decisions log

Resolved during design discussion:

- **Per-module vs per-project sandbox:** per-module. Robolectric sandbox semantics + renderer-vs-consumer version alignment risk make per-project not worth it for v1.
- **Daemon process lifecycle:** spawn-on-demand from VS Code by default; manual `gradle composePreviewDaemonStart --foreground` mode also available for debugging.
- **CLI daemon mode:** no, v1 is VS Code-only. CLI keeps using the Gradle task. CLI's primary use case (CI, agent rendering) doesn't benefit from a hot sandbox.
- **Keep Gradle `renderPreviews` task indefinitely:** yes, for now. It's the always-available fallback and the CI-canonical path. Daemon never replaces it.
- **Render body sharing:** duplicated into daemon module for v1; reconciled in v2 once daemon stable. Drift caught by pixel-diff CI gate.
- **Plugin helper extraction:** `buildTestClasspath` and `buildJvmArgs` hoisted into package-private helpers so daemon and existing test task share the same logic. Behaviour byte-identical, callers unchanged.
- **Tier-3 dependency index:** deferred to v2. v1 uses conservative module-stale + visibility filter.
- **kotlinc / compile-daemon integration:** out of scope. We let Gradle drive Kotlin compilation as today.

## 18. Future work (v2+)

- Extract shared render body from daemon back into `renderer-android`; `RobolectricRenderTest` becomes a thin wrapper.
- Per-preview ASM-walk reachability index for precise Tier-3 invalidation.
- Per-project sandbox sharing (parent classloader + per-module child loaders).
- CLI / MCP daemon mode for batch rendering without Gradle.
- Direct kotlinc-daemon integration to skip the Gradle compile step.
- Resource-edit precision: parse changed XML, cross-reference Compose's resolved-resource cache.
