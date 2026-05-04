# Persistent preview server — design

## 1. Goals & non-goals

**Goals**

- Sub-second preview refresh for a single focused preview after a
  no-classpath-change file save in VS Code.
- Eliminate Gradle configuration, JVM fork startup, and Robolectric
  sandbox bootstrap from the per-save hot path.
- Keep the existing `renderPreviews` Gradle task path untouched and
  always available.

**Non-goals (v1)**

- Per-project (cross-module) sandbox sharing — each consumer module
  gets its own daemon JVM.
- The `compose-preview` CLI binary keeps using the Gradle task. (MCP
  daemon mode is shipped separately as `:mcp` — see [MCP.md](MCP.md).)
- Replacing `renderPreviews` — daemon fronts it for the editor loop only.
- Hot kotlinc / compile-daemon integration.
- Tier-3 dependency-graph reachability index — v1 uses a conservative
  "module-changed = all previews stale, filtered by visibility" rule.

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
   └── RenderHost            — renderer-specific sandbox holder + warm spare
            │
            ├── (android) RobolectricHost — Compose-test-rule + ComponentActivity
            └── (desktop) DesktopHost     — Recomposer + Skiko surface
```

**Per-module, not per-project.** Robolectric sandbox config is a
function of the consumer module's classpath + AndroidX versions +
`compileSdk`. One sandbox per module sidesteps version skew.

The daemon is **launched by VS Code** but **bootstrapped by Gradle** —
Gradle is invoked once at startup to compute the test classpath, JVM
args, and `robolectric.properties` (Android) or the Skiko classpath
(desktop). New Gradle task `composePreviewDaemonStart` emits a JSON
descriptor (classpath, JVM args, system props, java launcher path); VS
Code execs `java` with those args.

A manual `./gradlew composePreviewDaemonStart --foreground` mode is
also available for debugging without VS Code in the loop.

### Renderer-agnostic surface

The protocol, the JSON-RPC server, and everything in the VS Code
extension are **deliberately agnostic to which renderer is on the
other end of the wire**. None of the message shapes in
[PROTOCOL.md](PROTOCOL.md) mention Android, Robolectric, Skiko, or
Compose Desktop; they trade in `previewId`, `pngPath`, `metrics.tookMs`.
The only renderer-specific code is the Kotlin `RenderHost`
implementation inside the per-target daemon module.

| Module                     | Host                  | What it sandboxes                                                  |
|----------------------------|-----------------------|--------------------------------------------------------------------|
| `:daemon:android` | `RobolectricHost`     | Robolectric `InstrumentingClassLoader`, `ComponentActivity`        |
| `:daemon:desktop` | `DesktopHost`         | Plain JVM classloader, `Recomposer`, Skiko `Surface`               |

A `:daemon:core` shared module holds the protocol types
(`Messages.kt`), the JSON-RPC server (`JsonRpcServer.kt`), and the
abstract `RenderHost` interface; both per-target modules depend on it.

### Module layout

```
renderer-android/                    UNCHANGED — RobolectricRenderTest.kt etc.
renderer-desktop/                    UNCHANGED — existing Skiko renderer

daemon/core/                NEW — pure JVM, renderer-agnostic
  src/main/kotlin/.../daemon/
    JsonRpcServer.kt                 stdio JSON-RPC + Content-Length framing
    RenderHost.kt                    Abstract host interface
    IncrementalDiscovery.kt          Tier-2 scoped ClassGraph
    DependencyIndex.kt               Tier-3 ASM walk + reverse index (v2)
    ClasspathFingerprint.kt          Tier-1 dirty detection
    SandboxScope.kt                  Per-classloader storage helper
    ProcessCache.kt                  Process-level pure-data cache helper
    SandboxLifecycle.kt              Measurement + recycle policy + warm spare
    protocol/
      Messages.kt                    @Serializable request/response types

daemon/android/             NEW — depends on renderer-android + core
  src/main/kotlin/.../daemon/
    DaemonMain.kt                    Wires RobolectricHost + JsonRpcServer
    RobolectricHost.kt               Holds Robolectric sandbox open
    SandboxHoldingRunner.kt          Robolectric runner that exposes the bridge package
    bridge/DaemonHostBridge.kt       Cross-classloader handoff
    RenderEngine.kt                  Per-preview render body

daemon/desktop/             NEW — depends on renderer-desktop + core
  src/main/kotlin/.../daemon/
    DaemonMain.kt                    Wires DesktopHost + JsonRpcServer
    DesktopHost.kt                   Holds Recomposer + Skiko surface open
    RenderEngine.kt                  Per-preview render body

gradle-plugin/                       ADDITIVE ONLY (one helper extraction)
  src/main/kotlin/.../plugin/daemon/
    DaemonBootstrapTask.kt           Emits launch-descriptor JSON
    DaemonExtension.kt               composePreview.daemon { … }
    DaemonClasspathDescriptor.kt     Serialises the JVM launch spec

vscode-extension/                    ADDITIVE ONLY (one router shim)
  src/daemon/
    daemonClient.ts                  JSON-RPC over stdio
    daemonProcess.ts                 Spawn/respawn/health
    daemonProtocol.ts                Types mirroring Messages.kt
    daemonGate.ts                    Feature-flag check + fallback to gradleService

samples/
  android-daemon-bench/              Android latency harness
  desktop-daemon-bench/              Desktop latency harness (D2-desktop)
```

## 5. Daemon lifecycle

### Bootstrap

The daemon runs a single dummy `@Test` whose body blocks on a
`LinkedBlockingQueue<RenderRequest>` until shutdown. This holds a
Robolectric sandbox open without re-implementing sandbox setup — we
inherit all the `robolectric.properties` plumbing for free. The "test"
never returns; the JVM exits when the daemon stops.

**In-JVM sandbox pool (SANDBOX-POOL.md).** With
`composeai.daemon.sandboxCount > 1` the daemon launches that many
worker threads, each running an independent JUnit invocation against
the same `SandboxRunner`. Concurrent `renderNow` requests dispatch
across slots via `Math.floorMod(id, sandboxCount)`. Default 4 sandboxes
per daemon.

### Per-preview render loop

Prologue:

1. Drain ShadowPackageManager records added by the previous preview.
2. Reset `RuntimeEnvironment.setQualifiers/setFontScale` for the new
   preview.
3. Re-create `ComponentActivity`.

Render body: same as `RobolectricRenderTest` — `setContent { ... }`,
`mainClock.autoAdvance = false`, `advanceTimeBy(CAPTURE_ADVANCE_MS)`,
`captureRoboImage(...)`.

Epilogue:

1. `setContent { }` (empty) to give Compose a frame to dispose
   `LaunchedEffect` / `DisposableEffect`.
2. Encode bitmap, then `bitmap.recycle()`.
3. Close any `HardwareRenderer` / `ImageReader` opened by the capture
   path.

### Shutdown

`JsonRpcServer.shutdown` (PROTOCOL.md § 3) drains the in-flight queue
before resolving the response. JVM SIGTERM handler waits for the drain
before exit.

## 8. Staleness cascade — when do we re-render

A four-tier cascade. Each tier is cheaper than the next; stop at the
cheapest "no work" answer.

> **Implementation note:** Tier 2's "preview source changed" trigger is
> necessary but not sufficient on its own. Once discovery has identified
> a stale preview, the daemon still needs to load fresh bytecode for
> that preview class. The parent/child classloader split in
> [CLASSLOADER.md](CLASSLOADER.md) is the source of truth for that save
> loop.

### Tier 1 — project fundamentally changed

**Trigger:** classpath JAR list, Compose/AndroidX versions,
`compileSdk`, Robolectric config, or `robolectric.properties` content
changed.

**Cheap signal:** SHA-256 over a small fixed set: `libs.versions.toml`,
all `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
`local.properties`. Recompute only on file save in those paths.

**Authoritative signal:** SHA over the resolved test runtime classpath
JAR list (paths + mtimes), computed at daemon start and re-checked on
cheap-signal hit.

**Action:** emit `classpathDirty`, exit cleanly. VS Code re-runs
`composePreviewDaemonStart` and the new daemon comes up with the new
classpath. Do **not** swap classloaders in-place.

### Tier 2 — preview list possibly changed

**Trigger:** edit to a `.kt` file that either currently contributes
previews or might newly contribute one.

**"Currently contributes" set is free** once `sourceFile: String?` is
on `PreviewInfo`. Save to a file in this set → re-run discovery scoped
to just that file's compiled classes.

**"Might newly contribute" via cheap pre-filter:** regex-grep the saved
file's text for `@Preview`. Match → escalate to discovery. No match →
Tier 2 clean. ~1ms per save.

**Incremental discovery scope:** ClassGraph filtered to a single
classpath element / package. After kotlinc rebuilds, re-scan only
`build/.../classes/` paths whose mtime moved. Diff against cached
`previews.json`, emit `discoveryUpdated`.

### Tier 3 — a preview's render output may have changed

**v1 conservative:** any `.kt` change inside the module's source set
marks **every preview in the module** as stale. Combined with Tier 4
(focus filter), the waste is bounded.

**v2 precise (deferred):** per-preview reachable-class set built via
ASM walk at discovery time. Reverse index `class → previews that
transitively reference it`.

**Resources:** treat any `res/**` change as "all previews in module
stale" for v1.

### Tier 4 — is the user looking at this?

> See also [PREDICTIVE.md](PREDICTIVE.md) for the speculative-prefetch
> tiers on top of the reactive `setVisible` / `setFocus` signals.

**State from VS Code:**

- `setVisible({ ids })` — preview cards currently visible.
- `setFocus({ ids })` — active selection. Rendered first.

**Render policy:**

- Stale ∩ visible → render now, in priority order (focus first).
- Stale ∩ not-visible → mark stale, render lazily on scroll-into-view.
- Not stale → no-op.

**Coalescing:** rapid saves produce overlapping stale sets. The render
queue dedupes by preview ID. If a render is in-flight when its preview
is re-marked stale, mark "needs another pass after this one finishes"
rather than cancelling — Robolectric mid-render cancellation is a leak
source.

## 9. No mid-render cancellation — invariant + enforcement

Once a render has started, it runs to completion. This is load-bearing
for memory safety: aborting between any prologue / body / epilogue step
leaves the sandbox holding a half-disposed Compose graph, an
unrecycled `Bitmap` whose native `GraphicBuffer` is still owned by the
`HardwareRenderer`, or `ShadowPackageManager` / `ActivityScenario`
state the next preview will trip over. The worst failure shape is
silent visual drift — colour-bleed across previews when a buffer is
reused.

Enforced in code:

- Render thread does **not** poll `Thread.interrupted()`; the daemon's
  own code never calls `interrupt()` on it.
- Shutdown is a poison-pill on `DaemonHost`'s queue, not a thread
  abort. The in-flight render finishes before the sandbox tears down.
- `JsonRpcServer.shutdown` (PROTOCOL.md § 3) drains the in-flight queue
  before resolving the response.
- JVM SIGTERM handler waits for the drain before exit.
- A regression test submits a render, immediately invokes shutdown, and
  asserts the render still completes and the result is observable.
- End-to-end coverage: scenario S2 in
  [TEST-HARNESS.md § 3](TEST-HARNESS.md#3-scenarios-catalogue).

## 10. Memory leak defense

Three layers, plus warm-spare to hide cost, plus proactive fixes for
known leak shapes.

**Layer 1 — measure on every render (always on).** Cheap (<5ms),
emitted on `renderFinished`: heap after GC, native/off-heap, class
instance counts (Composition, Recomposer, ComposeView,
ComponentActivity, Bitmap, HardwareRenderer, ImageReader), render time,
sandbox age.

**Layer 2 — active leak detection (periodic, opt-in).** Every Nth
render or via `--detect-leaks`: weak-reference probe, LeakCanary JVM
on-demand, JFR ring buffer.

**Layer 3 — recycle.** Triggers: heap > `daemon.maxHeapMb`, heap drift,
render time drift, render count > `daemon.maxRendersPerSandbox`,
`leakSuspected`. Each trigger emits `sandboxRecycle({ reason, ageMs,
renderCount })`.

**Layer 4 — known leak shapes, fixed proactively.** Empty `setContent
{ }` flush before teardown, Activity recreate per preview, Bitmap
recycle, HardwareRenderer/ImageReader closed in `finally`,
ShadowPackageManager adds tracked and reversed.

**Warm spare.** Daemon keeps two sandbox slots: `active` and `spare`.
Background thread builds a new `spare` after every recycle. Recycle =
atomically swap `spare → active`, schedule old `active` for teardown.

**Sandbox teardown verification.** Drop the strong sandbox reference,
force GC, check the WeakReference. If it doesn't clear within 2 GCs →
log `sandboxLeaked`. After 3 events, exit cleanly.

## 13. Latency budget

**Daemon-warm floor for a single focused preview:** kotlinc (1–2s) + 1
render (0.3–1s) ≈ 1.5–3s. Sub-second is achievable when no kotlinc
work is needed. v1 target: **< 1s for a single focused preview when no
kotlinc work is needed; < 3s with kotlinc.** Measured baseline captured
by P0.1 — see [`baseline-latency.csv`](baseline-latency.csv) +
[methodology sidecar](baseline-latency.md).

## 17. Module split (renderer-agnostic surface)

The seam at `:daemon:core` lets desktop and Android share everything
except the `RenderHost` implementation. `JsonRpcServer.kt` and
`Messages.kt` live in core; `RobolectricHost.kt` stays in
`:daemon:android`; `DesktopHost.kt` stays in `:daemon:desktop`. Both
backends evolve in parallel against a single protocol surface.

**Why desktop first for new features.** Desktop is the simpler
implementation surface — no Robolectric `InstrumentingClassLoader`, no
`bridge` package classloader workaround, no `HardwareRenderer`/`Bitmap`
native-buffer leak shapes, sub-second cold init. UX-facing features
(predictive prefetch, the cost model in [PREDICTIVE.md
§ 6a](PREDICTIVE.md#6a-ux-response--predicted-vs-measured-cost-model),
`MetricsSink` observability, the multi-tier render queue) get a shorter
feedback loop on desktop. Once a feature is proven on desktop, the
Android backend picks it up via the shared `:daemon:core` module
without code duplication.

**Roborazzi as `compileOnly`.** When the daemon ships as a Maven
artifact, Roborazzi stays runtime-supplied — the consumer's existing
Compose + Roborazzi pair is what gets loaded. Same pattern the daemon
already uses for `compose-ui-test-junit4`, `activity-compose`, etc.
Public Roborazzi surface used by `RenderEngine` is small
(`captureRoboImage`, `RoborazziOptions`,
`RoborazziOptions.RecordOptions(applyDeviceCrop = …)`). Fallback if
Roborazzi's API ever breaks: § 19.

## 19. captureToImage fallback path

If a future Roborazzi release breaks public-API binary stability — by
removing `RoborazziOptions.RecordOptions(applyDeviceCrop)`, changing
`captureRoboImage`'s signature, or moving classes between packages —
the daemon has a documented migration path that does not require
shipping multiple JARs.

**Replacement: `androidx.compose.ui.test.captureToImage()`.** This is
the upstream Compose UI Test API. It walks the same `HardwareRenderer`
path Roborazzi does under `@GraphicsMode(NATIVE)` (already required by
`RobolectricHost.SandboxRunner`), so the Robolectric prerequisite is
unchanged. The replacement is local to `RenderEngine.render()`:

```kotlin
// Before (Roborazzi):
val opts = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))
rule.onRoot().captureRoboImage(file = outputFile, roborazziOptions = opts)

// After (captureToImage):
val captured = rule.onRoot().captureToImage().asAndroidBitmap()
val final = if (isRound) applyCircularCrop(captured) else captured
FileOutputStream(outputFile).use { final.compress(Bitmap.CompressFormat.PNG, 100, it) }
```

`applyCircularCrop` is a ~20-line Bitmap+Canvas+BitmapShader helper
replacing Roborazzi's `applyDeviceCrop`.

**What this fallback does NOT cover.** Roborazzi's richer features used
by `:renderer-android`'s `RobolectricRenderTest` —
`RoborazziComposeOptions` builders, animated/scroll/GIF stitching,
accessibility tree extraction, `roborazzi-accessibility-check` — are
out of scope for the daemon. If `:renderer-android` needs to migrate
too, that's a much larger project; this fallback only addresses the
daemon's Roborazzi surface.
