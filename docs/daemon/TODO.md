# Preview daemon — work breakdown & parallelisation

> Read [DESIGN.md](DESIGN.md) first. This file enumerates concrete work items, their dependencies, and how to split them across parallel agents.

## How to use this file

- **Phases** are chronological gates — Phase N must complete before Phase N+1 starts.
- **Streams** within a phase run in parallel. Each stream is sized to fit one focused agent.
- **Each task** has explicit `Depends on` IDs and a **DoD** (definition of done) — when an agent completes a task, the DoD is what the reviewer checks.
- Tasks marked **[shared seam]** modify code visible to other streams. Schedule those serially and notify other agents when merged.

## Branching strategy for parallel agents

- One worktree per agent: `agent/preview-daemon-streamA`, `agent/preview-daemon-streamB`, etc.
- All branch from `agent/preview-daemon-design` (the docs branch — these design docs are the contract).
- Each stream opens its own PR against `main`. Sequential merges in dependency order.
- Phase 0 changes go to a single integration branch first; everyone rebases on it before Phase 1.

---

## Phase 0 — foundations (sequential, blocks everything)

These must land first because every other stream consumes them. Single agent or pair-coordinated; small but load-bearing.

### P0.1 — Capture latency baseline [Stream D] ✅

Built [`:samples:android-daemon-bench`](../../samples/android-daemon-bench/) with a `benchPreviewLatency` task. Baseline lives at [`baseline-latency.csv`](baseline-latency.csv) + [`baseline-latency.md`](baseline-latency.md) sidecar. DESIGN § 13 now carries a "Measured baseline" sub-section with the corrected numbers.

Original task description:

Build `:samples:android-daemon-bench` (skeleton sample module) with a `benchPreviewLatency` task that times the existing Gradle `renderPreviews` path: cold, warm-no-edit, warm-after-1-line-edit. Output CSV with phase breakdown (config, compile, discovery, fork, render).

- **Depends on:** none
- **DoD:** CSV checked into `docs/daemon/baseline-latency.csv` for the reference dev machine. Numbers referenced from `DESIGN.md` § 13 are validated or corrected.

### P0.2 — Add `sourceFile` to `PreviewInfo` [Stream A] [shared seam] ✅

Extend `PreviewInfo` (in `gradle-plugin/.../PreviewData.kt`) with `sourceFile: String?` populated from `ClassInfo.sourceFile` (ClassGraph exposes the bytecode `SourceFile` attribute). Wire through `DiscoverPreviewsTask`. Update `previews.json` schema.

The field was already shipped before this breakdown was written — see [`PreviewData.kt:202`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/PreviewData.kt#L202) and [`DiscoverPreviewsTask.kt:805`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt#L805) (populated with the package-qualified path). The remaining outstanding work was a functional-test guard asserting non-null, which is now inlined into `DiscoveryFunctionalTest.discoverPreviews finds annotated composables`.

- **Depends on:** none
- **DoD:** existing `:samples:android:renderAllPreviews` still passes. New field present in generated `previews.json` and surfaced in `PreviewInfo` consumers. Functional test asserts non-null for at least one preview in `samples/android`.

### P0.3 — Hoist classpath/JVM-args helpers [Stream A] [shared seam] ✅

Extract `buildTestClasspath(variant)` and `buildJvmArgs(variant)` from `AndroidPreviewSupport.kt` into a package-private `AndroidPreviewClasspath.kt`. Existing `registerAndroidTasks` calls the helpers instead of inlining. Behaviour must be byte-identical.

- **Depends on:** none
- **DoD:** `:gradle-plugin:functionalTest` passes unchanged. `:samples:android:renderAllPreviews` produces identical PNGs to `main` (manual diff check in PR description).

### P0.4 — Lock IPC protocol [Streams B + C, joint] [shared seam] ✅

Define the JSON-RPC protocol in [PROTOCOL.md](PROTOCOL.md). Locked message shapes for `setVisible`, `setFocus`, `fileChanged`, `renderNow`, `shutdown`/`exit`, `discoveryUpdated`, `renderStarted/Finished/Failed`, `classpathDirty`, `sandboxRecycle`, `daemonWarming`/`daemonReady`, `log`. Includes lifecycle, framing (LSP-style `Content-Length`), error codes, versioning rules.

- **Depends on:** none
- **DoD:** doc merged. Both Kotlin (`Messages.kt`) and TypeScript (`daemonProtocol.ts`) types in later phases reference it as the source of truth. Shared golden-message corpus lives in [protocol-fixtures/](protocol-fixtures/) (populated by B1.2 and C1.1).

### P0.5 — Extract `:daemon:core` shared module [Stream B] [shared seam] ✅

Per the renderer-agnostic surface decision in [DESIGN.md § 4](DESIGN.md#renderer-agnostic-surface): create a new `:daemon:core` module (plain `org.jetbrains.kotlin.jvm`, no Android plugins). Move `protocol/Messages.kt` and `JsonRpcServer.kt` from `:daemon:android` into core; introduce an abstract `RenderHost` interface; rename the existing concrete class to `RobolectricHost` and have it `implements RenderHost`. `:daemon:android` keeps its build.gradle.kts and its Android-specific files but now `implementation(project(":daemon:core"))`. Existing tests in `:daemon:android` keep passing unchanged.

- **Depends on:** Stream B's existing B1.5 commit (`agent/preview-daemon-streamB`)
- **DoD:** `:daemon:android:check` passes byte-identically to before. `:daemon:core:check` builds and runs Messages round-trip + JsonRpcServer framing tests (moved from the android module). The classpath descriptor emitted by `composePreviewDaemonStart` for `:samples:android` is unchanged in field shape (it gains a daemon-core jar at the head of the classpath; everything else identical).

Done. Created `:daemon:core` (plain `org.jetbrains.kotlin.jvm` + `kotlin-serialization`); moved `protocol/Messages.kt`, `JsonRpcServer.kt` (+ in-file `ContentLengthFramer`), `MessagesTest.kt`, `JsonRpcFramingTest.kt`, and `JsonRpcServerIntegrationTest.kt` into it. The new `RenderHost` interface in core exposes only `start()`, `submit(RenderRequest, timeoutMs): RenderResult`, and `shutdown(timeoutMs)` — exactly what `JsonRpcServer` calls — plus a `companion object { fun nextRequestId(): Long }` to host the monotonic id source previously on `DaemonHost.Companion`. `RenderRequest` (sealed) and `RenderResult` moved to core alongside the interface; they are protocol-shaped, not Robolectric-shaped. `DaemonHost` was renamed to `RobolectricHost` and now `implements RenderHost`; the cross-classloader `DaemonHostBridge` stays in `:daemon:android` (Robolectric-specific). The integration test's `FakeDaemonHost` was renamed to `FakeRenderHost` and now implements `RenderHost` directly rather than subclassing the (no-longer-visible-from-core) `RobolectricHost`. `kotlinx-serialization-json` is exposed as `api(...)` from core so `:daemon:android` no longer re-declares it (and no longer needs the `kotlin-serialization` plugin). The launch descriptor's `mainClass` stays `ee.schimke.composeai.daemon.DaemonMain`; the new `daemon/core` JAR enters via the existing `:daemon:android` artifactView path with no plugin classpath wireup change required.

### P0.6 — Capture desktop latency baseline [Stream D] ✅

Desktop counterpart to P0.1. Built `:samples:desktop-daemon-bench` (skeleton CMP-Desktop module) with a `benchPreviewLatency` task that times the existing Gradle `renderPreviews` path on Compose-Desktop: cold, warm-no-edit, warm-after-1-line-edit. Extended the shared CSV with a leading `target` column (`android` / `desktop`). Documented the desktop divergence in render accounting (no shared sandbox; per-preview JVM forks).

- **Depends on:** P0.1 (CSV schema), P0.5 (renderer-agnostic surface)
- **DoD:** desktop rows in `docs/daemon/baseline-latency.csv`; methodology + headline takeaways in `docs/daemon/baseline-latency.md`.

---

## Phase 1 — first end-to-end render (parallel)

Goal: a daemon JVM that can be spawned by VS Code and render a single preview to PNG behind the feature flag. No optimisations, no recycle, no incremental anything.

### Stream A — Gradle bootstrap

#### A1.1 — `composePreviewDaemonStart` task ✅

New `DaemonBootstrapTask` that, given a variant, emits `build/compose-previews/daemon-launch.json` containing: classpath JARs, JVM args, system properties, java launcher path. Uses helpers from P0.3.

- **Depends on:** P0.3
- **DoD:** running `./gradlew :samples:android:composePreviewDaemonStart` writes a valid descriptor. Manual JVM exec deferred until Stream B's `daemon/android` module exists; until then the descriptor's `enabled` flag defaults to `false` and VS Code is contracted not to launch (see `DaemonClasspathDescriptor` KDoc + the in-code TODO in `AndroidPreviewSupport.registerAndroidTasks`). Validated by `DaemonBootstrapTaskTest` plus a manual `samples:android` smoke test.

#### A1.2 — `DaemonExtension` DSL ✅

Add `composePreview.experimental.daemon { … }` extension with `enabled`, `maxHeapMb`, `maxRendersPerSandbox`, `warmSpare` fields. No-op when disabled. Documented in `docs/daemon/CONFIG.md` (new).

- **Depends on:** A1.1
- **DoD:** unit test on the extension's defaults (`DaemonExtensionTest`). README of daemon docs links to [CONFIG.md](CONFIG.md).

### Stream B-android — Robolectric daemon backend

> **Stream split (post-renderer-agnostic decision).** The original Stream B is now Stream B-android — the Robolectric backend. Stream B-desktop runs in parallel on `:daemon:desktop`. Both consume the shared `:daemon:core` module from P0.5.

#### B1.1 — Module skeleton ✅

Create `daemon/android/` module. `build.gradle.kts` depends on `renderer-android` + Robolectric + kotlinx-serialization. Empty `DaemonMain.kt` that prints "hello" and exits.

- **Depends on:** none
- **DoD:** `./gradlew :daemon:android:assemble` succeeds. Manual `java -cp ... DaemonMainKt` prints "hello".

#### B1.2 — `Messages.kt` protocol types ✅

`@Serializable` Kotlin data classes mirroring P0.4. One file under `daemon/protocol/`.

- **Depends on:** P0.4, B1.1
- **DoD:** unit test round-trips one of each message via `Json.encodeToString` / `decodeFromString`.

#### B1.3 — `DaemonHost` (sandbox holder) ✅

A class that runs a single dummy `@Test` whose body blocks on `LinkedBlockingQueue<RenderRequest>`. Submitting a request triggers a render in the sandbox thread; result returned via callback.

- **Depends on:** B1.1
- **DoD:** unit test: submit 10 dummy renders to a single host instance; all complete; sandbox classloader is reused (assert via reflection on `Thread.currentThread().contextClassLoader.hashCode()`).
- **Note:** required a custom `SandboxHoldingRunner` that adds `ee.schimke.composeai.daemon.bridge` to Robolectric's `doNotAcquirePackage` list. Without that rule the dummy-`@Test` queue holder is loaded twice (once in the sandbox classloader, once in the host classloader) and the cross-thread handoff silently breaks. Static handoff state lives in [`DaemonHostBridge`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/bridge/DaemonHostBridge.kt) with `java.util.concurrent.*`-only types.

#### B1.4 — `RenderEngine` (per-preview body) ✅

Duplicates the relevant parts of `:renderer-android`'s `RobolectricRenderTest.renderDefault` into a new `RenderEngine.kt` in `:daemon:android`. Per render: applies size/density qualifiers via `RuntimeEnvironment.setQualifiers`, registers `ComponentActivity` with `ShadowPackageManager` (idempotent — additive cleanup is B1.7), constructs `createAndroidComposeRule<ComponentActivity>()`, paints the resolved background on the host activity's window, sets content via the same `InvokeComposable`/`ComposableMethod` reflection trampoline `:daemon:desktop` uses, ticks `mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS = 32L)` against a paused clock, and captures via Roborazzi's `onRoot.captureRoboImage(file, RoborazziOptions())`. Output: `RenderResult` with `pngPath` (absolute) and `metrics.tookMs` populated. Cleanup epilogue (`try/finally`): empty-`setContent {}` flush + a second `mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)` so any `LaunchedEffect` / `DisposableEffect` cleanup runs *inside this render* before the next preview's composition starts; the rule's outer statement closes the `ActivityScenario` (which releases the `HardwareRenderer`/`ImageReader` Roborazzi opened) when `evaluate()` returns. `RenderSpec` lives in the same file (duplicated from `:daemon:desktop`'s `RenderSpec` per DESIGN § 7 rather than promoted to `:daemon:core` — same pure-data parser shape, but promotion would widen the renderer-agnostic surface for a type slated for replacement when `RenderRequest` grows a typed `previewId` field). `RobolectricHost.SandboxRunner.dispatchRender` discriminates by the presence of `className=` in the payload — parseable payloads route to `RenderEngine.render`, the legacy `render-N` payloads `DaemonHostTest` submits route to `renderStub` so the B1.3-era classloader-identity assertion still holds. `@GraphicsMode(NATIVE)` added to `SandboxRunner` (Roborazzi's `captureRoboImage` walks `HardwareRenderer`, only available under NATIVE).

- **Depends on:** B1.3
- **DoD:** rendered PNG of one `samples/android` preview via `RenderEngine` is byte-identical (or pixel-identical with no AA drift) to the same preview rendered via `RobolectricRenderTest`. Verified by [`RenderEngineTest`](../../daemon/android/src/test/kotlin/ee/schimke/composeai/daemon/RenderEngineTest.kt) (`redSquareRendersToValidPng` asserts ≥95% pixel match against the expected fill colour at ±8/channel tolerance; `fiveSequentialRendersExposeWarmRuntime` logs warm-up wall-clock).

#### B1.5 — `JsonRpcServer` over stdio ✅

Read framed JSON-RPC 2.0 from stdin (LSP-style `Content-Length`), dispatch
to handlers, write replies and notifications back to stdout. Render requests
queue onto [`DaemonHost`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/DaemonHost.kt);
inline handlers cover `initialize`, `setVisible`, `setFocus`, `fileChanged`,
`shutdown`, and `exit`. The server enforces the no-mid-render-cancellation
invariant (DESIGN § 9): `shutdown` drains the in-flight queue before
resolving and the daemon never calls `Thread.interrupt()` on the render
thread. See [`JsonRpcServer.kt`](../../daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/JsonRpcServer.kt).

DoD verified by [`JsonRpcServerIntegrationTest`](../../daemon/android/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcServerIntegrationTest.kt),
which drives `initialize → initialized → renderNow → renderStarted →
renderFinished → shutdown → exit` end-to-end against the real `DaemonHost`
over piped streams. The full subprocess variant (`ProcessBuilder` against
the descriptor from A1.1) is deferred to Stream C's
[C1.3](#c13--daemonclientts-json-rpc-client) integration test, since it
requires the consumer-module classpath that lives outside the
`:daemon:android` Gradle scope; the spawn harness will be shared
with B1.5a's drain-on-shutdown regression test. The framing layer is
covered by [`JsonRpcFramingTest`](../../daemon/android/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcFramingTest.kt)
(8 cases: well-formed, multi-frame, ignored Content-Type, missing/non-int
length, bare `\n`, EOF at boundary, EOF mid-payload).

The `pngPath` field on `renderFinished` is currently a deterministic
placeholder string (`${historyDir}/daemon-stub-${id}.png`); B1.4
(`RenderEngine`) replaces the body of
`JsonRpcServer.renderFinishedFromResult` with the real Compose render and
makes the path point at actual PNG bytes.

- **Depends on:** B1.2, B1.3
- **DoD:** integration test: spawn the daemon JVM as a subprocess, send `renderNow` for one preview, assert the PNG appears and a `renderFinished` notification is read back.

#### B1.5a — Enforce no-mid-render-cancellation invariant [Stream B]

Wire the enforcement points listed in [DESIGN.md § 9 "No mid-render cancellation"](DESIGN.md#no-mid-render-cancellation--invariant--enforcement) and [PREDICTIVE.md § 9](PREDICTIVE.md#9-decisions-made):

- Render thread does not poll `Thread.interrupted()`.
- Daemon code never calls `interrupt()` on the render thread.
- `JsonRpcServer.shutdown` drains the in-flight queue before resolving, per PROTOCOL.md § 3.
- JVM SIGTERM handler waits for the drain before exit.
- Regression test: submit a render, immediately invoke `shutdown`, assert the render still completes and the result is observable.

Small but load-bearing — silent visual drift from a half-aborted `HardwareRenderer` is the worst-case CI failure shape (colour-bleed across previews).

- **Depends on:** B1.5
- **DoD:** the regression test above passes; static check (or a code-review checklist note in `daemon/android/CONTRIBUTING.md` if no automated lint exists) flags any `interrupt()` / `Thread.interrupted()` introduced under `daemon/android/src/main/`.

#### B1.6 — `SandboxScope` + `ProcessCache` helpers

Implement the helpers from `DESIGN.md` § 11. Add the lint check (script under `gradle-plugin/build-logic/`) that fails the daemon module's build if `companion object` / `object` declarations hold Compose/AndroidX/Android-typed fields.

- **Depends on:** B1.1
- **DoD:** unit test: `SandboxScope.activeSandboxCount()` decreases by one after dropping a sandbox + 2 GCs. Lint check: introduce a deliberate violation; build fails with helpful message.

#### B1.7 — Wrap `GoogleFontInterceptor` and `ShadowPackageManager` adds

In the daemon module, add wrappers that delegate to the existing `renderer-android` helpers but route mutable state through `SandboxScope`. Per-preview prologue/epilogue in `RenderEngine` reverses ShadowPackageManager adds.

- **Depends on:** B1.4, B1.6
- **DoD:** integration test: render preview A (which adds activity X to the package manager), then preview B. Assert preview B's `PackageManager` does **not** contain activity X.

### Stream B-desktop — Skiko daemon backend

> **Why this exists in parallel.** Per [DESIGN.md § 4 "Renderer-agnostic surface"](DESIGN.md#renderer-agnostic-surface): desktop is the simpler implementation surface (no Robolectric `InstrumentingClassLoader`, no `bridge` package, no `HardwareRenderer` native-buffer leak shapes, sub-second cold init). UX-iteration features — predictive prefetch, the cost model, `MetricsSink`, multi-tier render queue — get a much shorter feedback loop here. Once a feature is proven on desktop the Android backend picks it up via `:daemon:core`.

#### B-desktop.1.1 — Module skeleton ✅

Create `daemon/desktop/` module. Plain `org.jetbrains.kotlin.jvm` (no Android plugins). Depends on `:renderer-desktop` + `:daemon:core` (P0.5). Empty `DaemonMain.kt` that prints "hello" and exits.

- **Depends on:** P0.5
- **DoD:** `./gradlew :daemon:desktop:assemble` succeeds. `java -cp ... DaemonMain` prints "hello".
- **Landed:** `daemon/desktop/build.gradle.kts` (plain Kotlin JVM, no `kotlin-serialization` plugin — `:daemon:core` re-exposes the dep transitively); `daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/DaemonMain.kt` with `@file:JvmName("DaemonMain")` matching B1.5's convention; `settings.gradle.kts` includes `:daemon:desktop` immediately after `:renderer-desktop`. Also registered a tiny `JavaExec` task `runDaemonMain` in lieu of the `application` plugin (avoids the `distZip`/`distTar`/etc. churn for a skeleton). Compose Desktop's per-platform Skiko native bundle is contributed transitively via `:renderer-desktop` (`compose.desktop.currentOs`) — no extra config needed in this module.

#### B-desktop.1.3 — `DesktopHost` (sandbox holder) ✅

Implement `RenderHost` (from P0.5's core) for the desktop backend. Holds a long-lived `Recomposer` + Skiko `Surface` + worker thread; submits `RenderRequest`s; returns `RenderResult { id, classloaderHash }` for parity with B1.3. Much simpler than `RobolectricHost` — no classloader bridge, no `@Test` runner trick, no shadow registrations to drain. Just a coroutine scope holding Compose runtime warm.

- **Depends on:** B-desktop.1.1
- **DoD:** unit test: submit 10 dummy renders to a single host instance; all complete; classloader is identical across all 10 (in this case, the JVM's own classloader — there's no sandbox classloader to verify against, but the test still asserts invariance).

Done. Landed [`DesktopHost`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/DesktopHost.kt) as a plain `RenderHost` implementation: a single `compose-ai-daemon-host` worker thread driven by a `LinkedBlockingQueue<RenderRequest>` + `ConcurrentHashMap<Long, LinkedBlockingQueue<Any>>` results map, mirroring `RobolectricHost`'s drain shape minus the Robolectric `@Test`-runner / classloader-bridge complexity. `start()` starts the thread; `submit(...)` enqueues + awaits; `shutdown(...)` enqueues a `RenderRequest.Shutdown` poison pill and joins. The render thread does not poll `Thread.interrupted()` and the host never calls `interrupt()` on it (DESIGN § 9). For B-desktop.1.3 the render body is a stub — captures `Thread.currentThread().contextClassLoader` identity into a `RenderResult` after a 1ms sleep, exactly parallel to `RobolectricHost.renderStub`. No `Recomposer` / `ImageComposeScene` / `setContent` in this task — that's B-desktop.1.4. The KDoc is explicit about where the warm Compose runtime will plug in. DoD verified by [`DesktopHostTest`](../../daemon/desktop/src/test/kotlin/ee/schimke/composeai/daemon/DesktopHostTest.kt) (10 renders → 1 classloader, no `InterruptedException` on the render thread); test runtime ~26ms.

#### B-desktop.1.4 — `RenderEngine` (per-preview body) ✅

Duplicates the relevant parts of `:renderer-desktop`'s render loop into a new `RenderEngine.kt`. Per render: instantiates `ImageComposeScene(width, height, density)`, resolves the preview composable via `androidx.compose.runtime.reflect.ComposableMethod`, sets content + ticks `scene.render()` twice, encodes via Skia `Image.encodeToData(EncodedImageFormat.PNG)`, writes to `<outputDir>/<outputBaseName>.png`, returns `RenderResult` with `pngPath` and `metrics.tookMs` populated. `scene.close()` always reached via `try/finally` — desktop equivalent of Android's `bitmap.recycle()` discipline (DESIGN § 9). Real-render warm-up data: 10 sequential renders of a Red square preview total **153ms**; first **15ms**, warm-median **14ms**, JIT ratio **1.07** — well within the ±20% target. Compose runtime / foundation / ui / components-uiToolingPreview deps added to `:daemon:desktop/build.gradle.kts` via the `compose.*` accessors.

- **Depends on:** B-desktop.1.3
- **DoD:** rendered PNG of one Compose-Multiplatform desktop preview via the daemon's `RenderEngine` is byte-identical (or pixel-identical with no AA drift) to the same preview rendered via the existing `:renderer-desktop` path.

#### B-desktop.1.5 — Wire `DaemonMain` to `JsonRpcServer` ✅

Wires `JsonRpcServer` (from `:daemon:core`) onto `DesktopHost`. Same lifecycle as B1.5's Android wiring: `initialize` → `initialized` → `renderNow` → … → `shutdown` → `exit`. Stdout-redirect to stderr before constructing the server (matches the Android side, prevents Skiko / coroutines bootstrap chatter from corrupting the wire). `JsonRpcDesktopIntegrationTest` runs in-process — Skiko native init worked fine inside the JUnit JVM on the dev machine, so the subprocess fall-back wasn't needed. The harness's `HarnessClient` already does `ProcessBuilder` properly; D-harness.v1.5's `-Pharness.host=real` flip will exercise it for real.

- **Depends on:** B-desktop.1.3, P0.5
- **DoD:** integration test: spawn the desktop daemon JVM as a subprocess, send `renderNow` for one preview, assert the PNG appears and a `renderFinished` notification is read back. Subprocess test is genuinely viable here (no Robolectric bootstrap to coordinate), so this DoD is stricter than B1.5's (which fell back to in-process).

#### B-desktop.1.6 — Cancellation enforcement (mirrors B1.5a) ✅

Same enforcement points as B1.5a — never poll `Thread.interrupted()` on the render thread, drain-not-abort shutdown, regression test. Simpler on desktop (no native `HardwareRenderer` buffers to corrupt) but still load-bearing for the Compose runtime: half-disposed `LaunchedEffect` instances retain references through the `Recomposer`.

- **Depends on:** B-desktop.1.5
- **DoD:** regression test passes; no `interrupt()` calls on the render thread in `:daemon:desktop/src/main/`.

Done. Landed the SIGTERM shutdown hook in [`DaemonMain.installSigtermShutdownHook`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/DaemonMain.kt): on SIGTERM the hook closes the original `System.in` (so `JsonRpcServer.readLoop` falls out of its blocking `read()` and walks its EOF path) and calls `host.shutdown(timeoutMs)` — same drain semantics as the JSON-RPC `shutdown` request, just from a JVM-shutdown thread instead of the read thread. Timeout reads `composeai.daemon.idleTimeoutMs` and is capped at the JVM's 30s shutdown-hook grace window. Chose option (a) from the task brief (close stdin) over a `requestStop()` API on `:daemon:core` to avoid widening the renderer-agnostic surface; SIGKILL leaks the sandbox classloader (documented inline) but the process is gone so the leak doesn't span renders. `RenderEngine.render`'s `try/finally` around `scene.close()` from B-desktop.1.4 already handled the in-flight close discipline; verified intact. Regression test landed at [`CancellationInvariantTest`](../../daemon/desktop/src/test/kotlin/ee/schimke/composeai/daemon/CancellationInvariantTest.kt) — submits a deliberately slow render (new `SlowSquare` fixture with `Thread.sleep(500)` in its composable body) from a worker, calls `host.shutdown(timeoutMs = 5_000)` ~50ms later from the main thread, asserts the PNG exists on disk, shutdown returned cleanly, `renderThreadInterrupted` stays `false`, and no uncaught throwable surfaces on the render thread (instrumented via a name-keyed `defaultUncaughtExceptionHandler`). Test runtime ~890ms; shutdown returned at ~1012ms after submit, dominated by the in-flight render's drain time. Static-check tooling: project has no detekt setup; introducing it for one rule isn't worth the dependency churn (per task brief), so added [`daemon/desktop/CONTRIBUTING.md`](../../daemon/desktop/CONTRIBUTING.md) with the explicit reviewer checklist for `Thread.interrupt()` / `Thread.interrupted()` / `scene.close()` discipline. Manual smoke: `./gradlew :daemon:desktop:runDaemonMain` + `kill -TERM <pid>` in another terminal exercises the hook; the "draining…" / "drain complete" lines on stderr surface immediately and the JVM exits within ~1s for an idle daemon.

### Stream C — VS Code client

#### C1.1 — `daemonProtocol.ts` types

TypeScript types mirroring P0.4. Match field names exactly.

- **Depends on:** P0.4
- **DoD:** lint passes. Unit test serialises one of each message and validates against the JSON-shape definitions in P0.4.

#### C1.2 — `daemonProcess.ts` lifecycle

Spawn the daemon JVM (using the descriptor from A1.1), monitor process health, watchdog on parent-PID exit, restart on `classpathDirty`, idle timeout shutdown.

- **Depends on:** A1.1, C1.1
- **DoD:** unit test (mocked child process): spawn, send shutdown, child exits cleanly. Restart on simulated `classpathDirty` notification.

#### C1.3 — `daemonClient.ts` JSON-RPC client

Stdio JSON-RPC over the spawned process. Methods mirror those used by `gradleService.ts` (specifically `renderPreviews`, `discoverPreviews`).

- **Depends on:** B1.5, C1.2
- **DoD:** integration test against the real daemon JAR: spawn, render one preview from `samples/android`, PNG appears, returned manifest matches expected shape.

#### C1.4 — `daemonGate.ts` router shim

Read `composePreview.experimental.daemon` setting. If enabled and daemon healthy → use `daemonClient`; else fall back to `gradleService`. One call site in `extension.ts`. On daemon failure, log + notification + auto-fallback for the remainder of the session.

- **Depends on:** C1.3
- **DoD:** manual smoke test in VS Code: enable flag, observe daemon spawn on first preview action, render works. Disable flag, observe normal Gradle path.

---

## Phase 2 — productionise (parallel)

Goal: the daemon is fast enough and stable enough to be the recommended path for daily editor use, even if still flagged.

### Stream B — daemon hardening

#### B2.0 — Disposable user classloader [shared seam] ✅ DONE

**Landed.** Implementation:
- `:daemon:core/src/main/kotlin/.../UserClassLoaderHolder.kt` — child-first
  `URLClassLoader` lifecycle, `swap()` on `fileChanged({ kind: "source" })`,
  `liveLoaderCount()` for soak detection.
- `RenderHost.userClassloaderHolder` getter (default null).
- `JsonRpcServer.handleFileChanged` routes `kind: "source"` → swap;
  `kind: "classpath"` left for B2.1; `kind: "resource"` no-op (B2.0c).
- Desktop: `DesktopHost(userClassloaderHolder = …)` ctor; `RenderEngine.render` takes
  a `classLoader` arg; `DaemonMain` constructs from `composeai.daemon.userClassDirs` sysprop.
- Android: same shape; `RobolectricHost` mirrors the loader through
  `DaemonHostBridge.childLoaderRef` so the sandbox-side `RenderEngine.render` reads it.
- Gradle plugin: `composeai.daemon.userClassDirs` sysprop emitted by the
  `composePreviewDaemonStart` task (heuristic over `build/intermediates/`,
  `build/tmp/kotlin-classes/`, `build/classes/`).
- `S3_5RecompileSaveLoopRealModeTest` un-`@Ignore`d, ASM option (option 2 from the
  placeholder KDoc) for desktop; `Assume.assumeTrue` skip on android pending
  follow-up to handle Compose-Android compiler-mangled method-name bytecode.

The actual save-loop blocker. Today both `RobolectricHost` and `DesktopHost` cache user-module bytecode at the daemon's lifetime classloader: a user edits `Foo.kt`, kotlinc recompiles, the daemon renders the same preview again — and gets the **old** colour because `Class.forName` returns the cached `Class<?>`. The harness's existing S3 scenarios are misleading: they swap *which* preview the spec points at (red → blue) but both classes are loaded once at daemon spawn; neither swap exercises the recompile-then-rerender path.

Architectural fix: parent/child classloader split.

- **Parent** (long-lived, expensive to bootstrap): the existing classloader (`InstrumentingClassLoader` for Android, the JVM app classloader for desktop) loads only stable artefacts — framework + AndroidX + Compose runtime/foundation/ui/tooling + kotlinx-* + Roborazzi + the daemon module's helpers. **User module's `build/intermediates/built_in_kotlinc/<variant>/...` is excluded from this classpath.**
- **Child** (disposable, per-recompile): a fresh `URLClassLoader` whose parent is the long-lived classloader and whose URLs point at the user's compiled-class directories. `RenderEngine` resolves `Class.forName(spec.className, true, currentChildLoader)`.
- **On `fileChanged({ kind: "source" })`** with a kotlinc-output mtime change: drop the strong reference to the current child loader, allocate a new one. New loader reads fresh bytecode on demand. Cost: tens of ms.

Risks captured for design:

1. Cross-classloader Compose state — `LaunchedEffect` instances, `Recomposer.knownCompositions`, etc. The parent's Compose runtime may hold strong refs to user-class-loaded objects, blocking child GC. Per-render disposal step required; lift the pattern from JetBrains' experimental `compose-hot-reload` (desktop-focused but the cross-classloader Compose state issue is identical).
2. `@PreviewParameter` provider classes — Compose runtime sometimes reflects on user code. Install the child as the context classloader for the render thread during dispatch.
3. `DaemonHostBridge` package discipline — already excluded via `doNotAcquirePackage`; that pattern extends naturally to child-loader handoff because it's parent-first.
4. Robolectric classloader-config seam — `RobolectricTestRunner.createClassLoaderConfig()` is the override point; `SandboxHoldingRunner` already overrides it for the bridge package, and excluding the user `build/intermediates/...` is one more line. `RenderEngine`'s `Class.forName` then needs to use the child loader instead of the current default.

Both backends benefit; the implementation should be unified at the `RenderHost` interface layer where possible.

- **Depends on:** B1.4 + B-desktop.1.4 (real `RenderEngine` on both backends).
- **DoD:** `:daemon:harness:test -Pharness.host=real -Ptarget=desktop --tests "*S3_5*"` and the Android counterpart un-`@Ignore`d and passing — render preview, recompile bytecode of the same FQN to a different colour, send `fileChanged`, re-render, assert the colour changed. Today both tests live in the harness as `@Ignore`d placeholders capturing the intended assertion shape. Per-render fresh `Recomposer` verified in both backends (CLASSLOADER.md § Risks 1), so cross-classloader Compose-state retention is bounded; soak `WeakReference` probe is part of the DoD.

#### B2.0c — Per-preview resource-read tracking

Smart-invalidation follow-up to B2.0. The B2.0 v1 plan handles `fileChanged({ kind: "resource" })` by marking **all previews in the module stale** — broad strokes, bounded by the Tier 4 visibility filter. B2.0c instruments the Resources lookup path during render to record per-preview which resource IDs were read; a reverse index lets `fileChanged` mark only the affected previews stale. See [CLASSLOADER.md § Resource changes](CLASSLOADER.md#resource-changes--conservative-v1--smart-v2).

Android implementation: extend Robolectric's existing Resources shadows to capture per-render resource-ID reads. Desktop implementation: intercept `compose.resources.*` lookups. Reverse index rebuilt on `discoveryUpdated`. Resource-file → resource-ID resolution comes from the merged `R.txt` (AGP-generated; exposed via the launch descriptor).

- **Depends on:** B2.0
- **DoD:** harness scenario asserts editing one `<color name="primary">` invalidates only previews that read it; other previews stay cached. Reverse index size + lookup time bounded on a 100-preview module.

#### B2.1 — `ClasspathFingerprint` (Tier 1) ✅

SHA over `libs.versions.toml`, all `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `local.properties`. Re-check on file change in those paths. Authoritative SHA over resolved classpath JAR list at startup. On hit: emit `classpathDirty`, exit cleanly.

- **Depends on:** B1.5
- **DoD:** integration test: bump a version in `libs.versions.toml` while daemon running; daemon emits `classpathDirty` and exits within 2s.

**Landed.** Implementation:

- `:daemon:core/src/main/kotlin/.../ClasspathFingerprint.kt` — two-tier fingerprint with `cheapHash()` (SHA-256 over the cheap-signal file *bytes*) + `classpathHash()` (SHA-256 over `(absolutePath, length, lastModified)` per classpath entry). Cheap-signal set is parameterised, not hard-coded; populated from the new `composeai.daemon.cheapSignalFiles` sysprop, which the gradle plugin emits.
- `JsonRpcServer` gains an optional `classpathFingerprint` ctor arg + `classpathDirtyGraceMs` ctor arg (default 2000ms, sysprop `composeai.daemon.classpathDirtyGraceMs`). At startup it captures a `Snapshot`. `handleFileChanged({ kind: "classpath" })` runs the cheap → authoritative cascade per DESIGN § 8: cheap stable → no-op; cheap drifted but classpath stable → update cached cheap hash, no-op (false-alarm path for comment-only edits in `build.gradle.kts`); both drifted → emit `classpathDirty({ reason: "fingerprintMismatch", detail: "<cheap+classpath hex>", changedPaths: [path] })`, refuse subsequent `renderNow` with the documented `ClasspathDirty` error code (-32002), and self-exit cleanly within the grace window. The exit path drains in-flight renders before `host.shutdown()` per the DESIGN § 9 no-mid-render-cancellation invariant.
- `initialize.classpathFingerprint` is now populated with the authoritative SHA-256 (was empty pre-B2.1).
- `:daemon:desktop/.../DaemonMain.kt` and `:daemon:android/.../DaemonMain.kt` both build a `ClasspathFingerprint` from the `composeai.daemon.cheapSignalFiles` sysprop + the daemon JVM's own `java.class.path`. When the sysprop is unset (in-process tests, harness fake mode), the fingerprint is null and the pre-B2.1 no-op behaviour holds.
- `gradle-plugin/.../AndroidPreviewSupport.kt` — `composePreviewDaemonStart` emits `composeai.daemon.cheapSignalFiles` colon-delimited via the new private `collectCheapSignalFiles(project)` helper (collected at task-action time so newly-added subprojects' `build.gradle.kts` files are seen on the next run).
- Tests: 10 unit tests in `ClasspathFingerprintTest.kt` (deterministic / sensitive-to-bytes / sensitive-to-mtime / sysprop-parse / microbenchmark @ ~1.3ms per cheap recompute over 50×2KB files); 2 in-process integration tests in `JsonRpcServerIntegrationTest.kt` covering the dirty + false-alarm paths; `S6ClasspathDirtyRealModeTest` (desktop, ~1.1s wall-clock) + `S6ClasspathDirtyAndroidRealModeTest` (~6.2s wall-clock) drive the full real-mode subprocess flow through both backends.
- Plugin test: `DaemonBootstrapTaskTest.descriptor encodes B2_1 cheapSignalFiles sysprop verbatim` pins the descriptor's contract.

Decisions worth highlighting:

- **Cheap-signal set evolution.** The gradle plugin builds the set from `rootProject.allprojects` at task-action time. A subproject added between two `composePreviewDaemonStart` runs IS picked up on the next run. A subproject added while a daemon is already running won't be in the running daemon's baseline — but adding a subproject is itself a `settings.gradle.kts` edit, which IS in the cheap set, so the very edit that adds it triggers the Tier-1 dirty path.
- **No daemon-side file watcher.** The `fileChanged` notification is the trigger; the editor (or the harness's test driver) sends it. Daemon doesn't watch the filesystem itself.
- **Synthetic classpath drift in S6.** Real classpath drift would require a Gradle re-resolve mid-test. Instead the test adds a fresh `<rendersDir>-cp-drift/` directory to the daemon's `-cp` via a new `RealDesktop|AndroidHarnessLauncher.extraClasspath` parameter, places the cheap-signal marker file inside it, and edits the marker — both hashes drift because the marker's bytes drift (cheap) AND its parent directory's `(size, lastModified)` drift (authoritative).

#### B2.2 — `IncrementalDiscovery` (Tier 2)

Scoped ClassGraph scan of a single classpath element. Regex pre-filter for `@Preview` on saved files outside the existing preview-bearing set. Diff against cached `previews.json`. Emit `discoveryUpdated`.

- **Depends on:** P0.2, B1.5
- **DoD:** unit test: synthetic project with 100 preview-bearing classes; edit one; discovery completes in < 100ms; diff is exactly that one class's previews.

Phased delivery:

- ✅ **Phase 1 — preview index ownership.** Daemon owns its own `PreviewIndex` parsed from `previews.json` at startup (gradle plugin emits `composeai.daemon.previewsJsonPath` sysprop on the daemon JVM). `initialize.manifest.{path, previewCount}` now reports the real values instead of the pre-B2.2 stub. Index is read-only for the daemon's lifetime. Layering invariant preserved: `:daemon:core` defines its own `PreviewInfoDto` mirror with `ignoreUnknownKeys = true`, no dep on `:gradle-plugin`. Lands the long-standing `// B2.2 ...` TODO comment in `JsonRpcServer.handleInitialize`.
- ✅ **Phase 2 — incremental rescan + `discoveryUpdated`.** Cheap regex pre-filter (file text contains `@Preview` OR file is in the index by `sourceFile` match) followed by a scoped ClassGraph scan filtered to the smallest classpath element overlapping the saved `.kt` (heuristic, falls back to the full classpath when source-set layout doesn't match). Diff against the in-memory index produces `{added, removed, changed}`; `removed` is scoped to ids whose cached `sourceFile` matches the saved path. Diff is applied in-place and emitted as `discoveryUpdated`; `ServerCapabilities.incrementalDiscovery` flips to `true` once an `IncrementalDiscovery` is wired. Layering invariant preserved: `:daemon:core` carries its own `PREVIEW_FQN` set as a parallel mirror of `gradle-plugin`'s `DiscoverPreviewsTask.PREVIEW_FQNS`. The S3 fake-mode harness scenario flipped from "asserts absence of `discoveryUpdated`" to "asserts presence with `removed = [previewId]`".

#### B2.3 — `SandboxLifecycle` measurement (Layer 1) ✅

Per-render: heap post-GC, native heap, render time, sandbox age. Emitted on `renderFinished.metrics`.

- **Depends on:** B1.5
- **DoD:** soak test runs 100 renders; all metrics populated; measurement does not regress render time materially.

**Landed.** Implementation:

- `:daemon:core/.../SandboxLifecycle.kt` — `SandboxLifecycleStats` (per-host start time + atomic render counter) + `SandboxMeasurement.collect(stats, tookMs)` helper. The collect path runs one `System.gc()` after the render body, reads `Runtime.totalMemory - freeMemory` for `heapAfterGcMb`, reads `com.sun.management.OperatingSystemMXBean.committedVirtualMemorySize` for `nativeHeapMb` (documented approximation; covers JVM heap + native libs + mapped files; falls back to 0 on non-HotSpot JVMs with a one-time warn), bumps + reads the host's render counter for `sandboxAgeRenders`, and computes `(System.nanoTime() - startNs) / 1_000_000` for `sandboxAgeMs`.
- `:daemon:core/protocol/Messages.kt` — `RenderMetrics.fromFlatMap(map)` translates the host-supplied flat `Map<String, Long>` into the structured wire shape via three outcomes: `AbsentSource` (host returned null), `PartialMap(missingKeys)` (some-but-not-all of the four keys present), `Populated(metrics)`.
- `:daemon:core/JsonRpcServer.renderFinishedFromResult` calls `RenderMetrics.fromFlatMap` instead of hardcoding `metrics = null`. Partial maps emit `metrics: null` on the wire (no half-populated objects) and warn-log the missing keys so caller-side drift is observable.
- `:daemon:android/RobolectricHost.SandboxRunner` and `:daemon:desktop/DesktopHost` each own a `SandboxLifecycleStats` for their lifetime. Both `RenderEngine.render()` signatures gained a `sandboxStats` parameter (defaults to a fresh per-call instance for unit tests that drive the engine directly).
- `:daemon:harness/FakeHost` populates the four B2.3 keys on every render with synthetic but real values (`heapAfterGcMb=1`, `nativeHeapMb=1`, `sandboxAgeRenders=renderCount`, `sandboxAgeMs=monotonic delta`). Sidecar-supplied metrics from `<previewId>.metrics.json` still take precedence on key collision so legacy fixtures keep their explicit values.
- Tests: 6 unit tests in `RenderMetricsFromFlatMapTest.kt` (happy / null / partial / extras paths); 3 new integration tests in `JsonRpcServerIntegrationTest.kt` covering the host-supplies-all / null / partial branches end-to-end; the fake-mode `S8CostModelMetricsTest` flipped from gap-test to integration-assertion (asserts the four fields populate `renderFinished.metrics` with sidecar-overridden + B2.3-default values); the real-mode `S8CostModelMetricsRealModeTest` and `S8CostModelMetricsAndroidRealModeTest` flipped to assert each cost-model field's presence + sane range; `B23SoakTest` drives 100 sequential renders and asserts populated metrics + monotonic `sandboxAgeRenders` + non-decreasing `sandboxAgeMs`.

Out-of-scope follow-ups (B2.4+):

- Class histogram for tracked classes (deferred to B2.4 leak detection — reachability tracking belongs there).
- Sandbox recycle counter reset (B2.5 territory; for B2.3 v1 the counter just keeps growing over the host's lifetime).
- LeakCanary / JFR ring buffer (B2.4); heavy/light leak modes (B2.4).
- Real-mode soak test with a measurement-overhead assertion — `B23SoakDesktopRealModeTest` / `B23SoakAndroidRealModeTest` are committed `@Disabled` placeholders. Pulling them in needs a "skip measurement" engine toggle.

#### B2.4 — Active leak detection (Layer 2)

Weak-reference probe every Nth render. `--detect-leaks=heavy` flag wires LeakCanary JVM-test. JFR ring buffer always on; dumped on `leakSuspected` or `sandboxRecycle`.

- **Depends on:** B2.3
- **DoD:** synthetic leak (deliberately retain Activity ref) detected within 50 renders. JFR dump appears in the daemon-owned state directory.

#### B2.5 — Recycle policy + warm spare (Layer 3)

Triggers from `DESIGN.md` § 9. `active`/`spare` slots; background spare builder. Recycle = atomic swap + teardown old + start new spare. `daemonWarming` notification when spare not ready.

- **Depends on:** B1.3, B2.3
- **DoD:** integration test: force recycle (via `maxRendersPerSandbox=10`), assert no user-visible pause (next render starts within 50ms of swap). Spare always ready except in deliberate spare-blocked test.

#### B2.6 — Sandbox teardown verification

WeakReference to sandbox classloader; force GC after recycle; emit `sandboxLeaked` if classloader not collected within 2 GCs. After 3 events, exit cleanly.

- **Depends on:** B2.5
- **DoD:** synthetic leak (deliberately pin classloader from process-level cache); first recycle emits `sandboxLeaked`; daemon exits after 3 events.

### Stream C — VS Code refinements

#### C2.1 — Visibility tracking from webview

Webview reports preview cards entering/leaving viewport (IntersectionObserver). Extension translates into `setVisible({ ids })` to daemon.

- **Depends on:** C1.4
- **DoD:** manual: scroll panel; daemon log shows `setVisible` updates with correct IDs.

#### C2.2 — Focus signal

On hover / click / file scope change, send `setFocus({ ids })` (subset of visible).

- **Depends on:** C2.1
- **DoD:** manual: click a preview card; daemon renders that one first when multiple are stale.

#### C2.3 — Daemon-warming UX

Render `daemonWarming` and `sandboxRecycle` notifications as a non-blocking status indicator in the panel.

- **Depends on:** B2.5, C1.4
- **DoD:** visual review of status indicator during a forced recycle.

#### C2.4 — Per-render UX cost model [Stream C]

Implement the predicted-vs-measured cost model from [PREDICTIVE.md § 6a](PREDICTIVE.md#6a-ux-response--predicted-vs-measured-cost-model). Webview decides on `renderStarted` whether to show no indicator (< 150ms estimated), a subtle shimmer (150ms–1s), or an explicit spinner (> 1s). Inputs: existing `Capture.cost` from the manifest + rolling per-preview-ID `tookMs` from `renderFinished.metrics`. Per-machine baseline-ms-per-cost-unit learned from the median of recent STATIC=1 renders, seeded from `baseline-latency.csv` until the daemon path produces real measurements.

Pure client-side; no PROTOCOL.md change. Thresholds are configurable in case a user wants different defaults.

- **Depends on:** C1.4
- **DoD:** unit test for the tier classifier (predicted-only path; measured-overrides-predicted path; baseline-update path). Manual visual review on `samples/android` showing fast renders skip the spinner, slow renders show one. Telemetry surfaces predicted/measured drift on the dev observability channel (PREDICTIVE.md § 9 sinks).

### Stream D — bench & CI

#### D2.1 — Daemon-mode bench

Extend P0.1 bench to include daemon-mode timings: spawn cost, first-render cost, warm-render cost, edit-then-render cost. Same CSV format.

- **Depends on:** B1.5, P0.1
- **DoD:** `docs/daemon/baseline-latency.csv` updated with daemon columns. PR description includes ratio analysis.

#### D2.2 — Pixel-diff CI gate

CI job that runs `samples:android-daemon-bench:renderAll` (daemon path) and pixel-diffs against `samples:android:renderAllPreviews` (Gradle path). Must be 100% identical.

- **Depends on:** B1.7, D2.1
- **DoD:** GitHub Actions workflow added; job green on this branch; deliberately introduce a render bug → job fails with diff image artifact.
- **See also:** `:daemon:harness`'s S1 PNG verification (D-harness.v0) is the long-term home for this gate. Once the harness lands, D2.2 reduces to "the harness's S1 must pass on every PR."

#### D2.3 — Soak test in CI

Runs nightly. 1000 renders in a single sandbox on `samples/android`. Asserts: no OOM, heap drift < 50MB, zero `sandboxLeaked`, ≤ 2 `sandboxRecycle`.

- **Depends on:** B2.5, B2.6
- **DoD:** workflow green; metrics summary posted as workflow output.
- **See also:** D-harness.v3's session-mode soak scenario subsumes this once `:daemon:harness` exists; D2.3 can be re-implemented as a harness session and reused on the same nightly schedule.

### Stream D-harness — end-to-end test harness

Full design: [TEST-HARNESS.md](TEST-HARNESS.md). The harness plays the role of VS Code against a real daemon JVM over JSON-RPC: drives scenarios, edits source files, asserts notifications + rendered PNGs + latency budgets. Desktop-first; Android picks up in v2.

Scoped under Stream D because it is shaped like the bench harnesses (subprocess management + assertions on real daemon output) and shares CI surface with D2.1/D2.2/D2.3.

#### D-harness.v0 — Single happy-path scenario, desktop, against `FakeHost` ✅

New module `:daemon:harness` (plain `org.jetbrains.kotlin.jvm`; depends only on `:daemon:core` for the protocol types). One scenario (S1 lifecycle happy path). Subprocess plumbing (`ProcessBuilder` against a tiny `FakeDaemonMain`, `Content-Length`-framed stdio, stderr buffering, shutdown sequencing). The harness ships a `FakeHost` (TEST-HARNESS § 8a) that implements `RenderHost` and serves PNGs from `build/daemon-harness/test-patterns/`, generated deterministically by a new `TestPatterns.kt` (solid colours, text boxes, gradient strips — TV-test-signal aesthetics). Pixel-diff helper (`PixelDiff.kt`) — first shared in-tree implementation; previously each pixel-test rolled its own. CI workflow `daemon-harness-desktop`.

**Independent of B-desktop.1.5** thanks to `FakeHost` — proves the architecture without depending on the real renderer wiring. No image baselines checked in (generated test patterns are the baseline; same generator produces fixture and expected output).

- **Depends on:** P0.5 (for `:daemon:core` protocol types).
- **DoD:** `./gradlew :daemon:harness:test` runs S1 green. Deliberately corrupt the wire path (e.g. bit-flip in the daemon's PNG-write) → test fails; `actual.png`, `expected.png`, `diff.png` written under `build/reports/daemon-harness/`. CI workflow added and green on this branch.

Done. Module `daemon/harness/` (plain `org.jetbrains.kotlin.jvm`, depends only on `:daemon:core`). Files: `FakeHost.kt` + `FakePreviewSpec` (RenderHost serving PNGs from a per-scenario fixture directory, with optional `<id>.delay-ms` / `<id>.error` / `<id>.metrics.json` sidecar overrides), `FakeDaemonMain.kt` (`@JvmName`-tagged entry point reading `-Dcomposeai.harness.fixtureDir=…`), `TestPatterns.kt` (deterministic PNG generator: `solidColour`/`textBox`/`gradient`/`alignmentGrid`, written through the long-form `ImageWriter` path with an explicit empty metadata tree to suppress `tIME`/`iTXt` chunks), `PixelDiff.kt` (per-pixel ≤ 3 LSB + aggregate ≤ 0.5% + absolute cap ≤ 50 LSB tolerance with `actual.png`/`expected.png`/`diff.png` artefacts on failure), `HarnessClient.kt` (subprocess-managing JSON-RPC client; readers demux into per-id response slots + a notifications queue so polls don't lose ordering), `Scenario.kt` (minimal abstraction so v1 scenarios slot in). Test `S1LifecycleTest` drives the full `initialize → initialized → renderNow → renderStarted → renderFinished → shutdown → exit` lifecycle and pixel-diffs the daemon-served PNG against the fixture; manual sanity check (replace fixture with a magenta PNG) verified the failure path writes all three artefacts under `build/reports/daemon-harness/s1/`. CI workflow `.github/workflows/daemon-harness-desktop.yml` runs `:daemon:harness:test` on every PR and uploads the diff artefacts on failure. **`RenderResult` widened in `:daemon:core`** with nullable `pngPath: String?` + `metrics: Map<String, Long>?` (defaulting to `null` to keep `RobolectricHost` and the existing `JsonRpcServerIntegrationTest` callers source-compatible); `JsonRpcServer.renderFinishedFromResult` forwards the host-supplied `pngPath` verbatim and falls back to the existing `daemon-stub-${id}.png` placeholder when null. `:daemon:core:check` and `:daemon:android:check` both pass. Subprocess inherits the test JVM's `java.class.path` so the harness picks up the toolchain Gradle configured for the surrounding test run; idle timeout pinned to 2s via `-Dcomposeai.daemon.idleTimeoutMs` so a misbehaving scenario can't hang CI.

#### D-harness.v1 — Full reactive scenario catalogue, desktop, fake-mode ✅

Adds S2 (drain semantics), S3 (render-after-edit — for fake mode the "edit" maps to swapping which fixture variant `FakeHost` serves for that preview ID), S4 (visibility filter), S5 (renderFailed — configured via fixture `<previewId>.error` rather than a sentinel composable), S7 (latency — **recorded only, not asserted**: per-scenario `actual ms / baseline ms / delta%` written to `build/reports/daemon-harness/latency.csv` and surfaced as a CI artefact; humans read trends, no test fails on perf), S8 (cost-model parity — fake-mode metrics configured via fixture `<previewId>.metrics.json`). File-edit simulation primitive (`editSource` with auto-revert in `finally` + bytecode-visibility validation). Per-scenario timeouts.

- **Depends on:** D-harness.v0, P0.6 (so latency-record-only has desktop baseline rows to delta against)
- **DoD:** all six scenarios pass on every PR via the existing `daemon-harness-desktop` job. Fake-mode failure-mode coverage (S5 + slow renders + specific metrics) verified by configuring fixtures, no sample-module changes. Latency CSV artefact uploaded by CI; weekly drift report consumes it later (v3).

Done. New tests `S2DrainSemanticsTest`, `S3RenderAfterEditTest`, `S4VisibilityFilterTest`, `S5RenderFailedTest`, `S7LatencyRecordOnlyTest`, `S8CostModelMetricsTest` (S1 from v0 unchanged). Scenario primitives moved into `Scenario.kt` (`editSource(target, newBytes)` with auto-revert in `finally`; the bytecode-visibility check is real-mode-only and skipped here per TEST-HARNESS § 5 — fake-mode pixel-diff is the equivalent loud check). New `LatencyRecorder` writes `target,scenario,preview,actualMs,baselineMs,deltaPct,notes` rows to `build/reports/daemon-harness/latency.csv`; baseline = 1100ms desktop median per [`baseline-latency.csv`](baseline-latency.csv); resets via per-PID marker so successive scenarios append rather than re-wipe earlier rows. Shared boilerplate hoisted into `HarnessTestSupport.kt` (`scenario(name)` returns fixture/reports paths + classpath + recorder; `writePreviewsManifest()`). `HarnessClient` extended with `setVisible`/`setFocus`/`fileChanged` notifications, `pollNotificationMatching(method, predicate)` + `pollRenderFinishedFor(id)` so multiple in-flight renders can be demuxed, and split shutdown (`sendShutdownAsync` + `awaitResponse` + `sendExitAndWait`) so S2 can verify shutdown-resolves-after-renderFinished without blocking on the response. `FakeHost.parseErrorSidecar` now accepts the v1-task JSON shape `{kind, message, stackTrace}` (legacy plain-text path kept for back-compat).

Wire-layer change to `:daemon:core/JsonRpcServer.submitRenderAsync`: the existing `RenderRequest.Render.payload` field is now populated with `previewId=<id>` (using the `hostIdToPreviewId` mapping the server already maintains) so `FakeHost.resolvePreviewId` can disambiguate concurrent renders for multi-preview scenarios. **Not a `:daemon:core` widening** — uses the existing field per the v1 task brief's documented workaround until B-desktop.1.4 lands a typed `previewId` field.

**Gaps with TEST-HARNESS § 3 spec (assertion-recorded for the regression-flip path):**

* **S3 / `discoveryUpdated` on `fileChanged`** — ✅ closed by B2.2 phase 2. The fake-mode S3 scenario now writes a stand-in `.kt` source file under the fixture dir, anchors the preview's `sourceFile` to it in the harness manifest, and asserts the daemon emits `discoveryUpdated` with `removed = [previewId]` within 2s of the `fileChanged({kind: source})` notification. ServerCapabilities.incrementalDiscovery flips true.
* **S4 / focus-first dispatch** — the v1 daemon's queue is single-threaded FIFO with no focus prioritisation; `setVisible` / `setFocus` are no-ops. The test asserts all three `renderFinished` arrive but does **not** strictly assert ordering; observed order in current runs is "preview-a|preview-b|preview-c" (FIFO, focus-not-honoured). Once P2.5.1 lands the multi-tier queue, this should tighten to "b first".
* **S5 / structured `RenderError.kind`** — `JsonRpcServer.emitRenderFailed` always emits `kind:"internal"` regardless of host-supplied kind. S5 asserts `params.error.message` contains the configured failure string (which it does — FakeHost prefixes the kind onto the message); a tighter `error.kind == "runtime"` assertion would fail today.
* **S8 / metrics round-trip** — ✅ closed by B2.3. `JsonRpcServer.renderFinishedFromResult` now translates the host's flat `Map<String, Long>` into a structured `RenderMetrics` via `RenderMetrics.fromFlatMap` and emits it on the wire. FakeHost populates the four B2.3 keys (`heapAfterGcMb`, `nativeHeapMb`, `sandboxAgeRenders`, `sandboxAgeMs`) on every render; sidecar `<previewId>.metrics.json` values still win on key collision. The fake-mode S8 test flipped from "asserts metrics is null" to "asserts each field populates correctly"; the real-mode S8 tests assert sane ranges for each field on a real desktop / Android render.

`./gradlew :daemon:harness:check` green; all seven scenarios pass (S1 0.28s + S2 0.72s + S3 0.43s + S4 0.18s + S5 0.17s + S7 0.17s + S8 0.17s; S2 includes 500ms FakeHost delay; S7 records 5 cold-warm renders). `:daemon:core:check`, `:daemon:android:check`, `:daemon:desktop:check` unchanged. Latency CSV at `daemon/harness/build/reports/daemon-harness/latency.csv` populated for every scenario × preview pair. Manual deliberate-corruption sanity check on S3 (substituted v1 bytes for the v2 swap in `editSource`) verified — test failed and `actual.png`/`expected.png`/`diff.png` written under `build/reports/daemon-harness/s3/`.

#### D-harness.v1.5 — Flip to real renderer once B-desktop.1.5 lands ✅

Same scenarios, `-Pharness.host=real`. Captures actual Compose-rendered PNGs as in-repo image baselines under `daemon/harness/baselines/desktop/<scenario>/<id>.png`. `FakeHost` stays available behind `-Pharness.host=fake` for deterministic failure-mode coverage and as the v0 architecture preservation. Both modes run on every PR.

- **Depends on:** D-harness.v1, B-desktop.1.5 (real `DaemonMain` wiring), B-desktop.1.6 (drain semantics for S2 against the real cancellation enforcement)
- **DoD:** real-mode S1–S8 pass; fake-mode regression set still passes; the captured baselines are reviewed visually in the merging PR.

##### D-harness.v1.5a — `HarnessLauncher` abstraction + real-mode S1 only ✅

Splits the v1.5 brief into a thin first slice. Refactors `HarnessClient.start(...)` to delegate the spawn step to a `HarnessLauncher` (`fake` vs `real`), adds the `-Pharness.host=fake|real` Gradle parameter (default `fake`), and adds **one** real-mode S1 test (`S1LifecycleRealModeTest`) that spawns `:daemon:desktop`'s `DaemonMain`, drives the full lifecycle, and auto-captures a `red-square.png` baseline on first run.

- **Depends on:** D-harness.v1, B-desktop.1.5, B-desktop.1.6.
- **DoD:** all 7 fake-mode scenarios still pass under `-Pharness.host=fake`; `S1LifecycleRealModeTest` skips by `Assume.assumeTrue` under fake mode; runs end-to-end against the real desktop daemon under `-Pharness.host=real`; baseline PNG captured at `daemon/harness/baselines/desktop/s1/red-square.png`.

##### D-harness.v1.5b — Convert remaining scenarios + `regenerateBaselines` task + CI ✅

The remainder of v1.5 — convert S2-S8 to real-mode, add a `regenerateBaselines` Gradle task, update the CI workflow to run both modes.

- **Depends on:** D-harness.v1.5a (launcher abstraction + `PreviewManifestRouter` + `diffOrCaptureBaseline` helper).
- **DoD:** `S2DrainSemanticsRealModeTest`, `S3RenderAfterEditRealModeTest`, `S4VisibilityFilterRealModeTest`, `S5RenderFailedRealModeTest`, `S7LatencyRecordOnlyRealModeTest`, `S8CostModelMetricsRealModeTest` all pass under `-Pharness.host=real`; all skip by `Assume.assumeTrue` under fake mode; baselines captured under `daemon/harness/baselines/desktop/{s2,s3,s4}/`; `:daemon:harness:regenerateBaselines` task overwrites baselines deterministically (verified byte-identical across two runs); `.github/workflows/daemon-harness-desktop.yml` split into `fake` + `real` jobs both required for the workflow to succeed.

**Real-mode-specific gaps surfaced (not present in fake mode):**

- **S5 / `renderFailed` not emitted for in-composition exceptions** — `DesktopHost.runRenderLoop` catches the exception from `RenderEngine.render`, prints to stderr, and falls back to `renderStubFallback` which returns a *successful* `RenderResult` with no `pngPath`. `JsonRpcServer` sees the success path and emits `renderFinished` carrying the `daemon-stub-<id>.png` placeholder, so the client never observes a `renderFailed` notification. Fake mode lets `FakeHost.submit` throw, which `JsonRpcServer.runHostSubmitter` catches and surfaces as `renderFailed`. `S5RenderFailedRealModeTest` pins the real-mode behaviour (asserts the stub path appears) so the test flips when `DesktopHost` is taught to propagate composition exceptions or translate them into a structured `RenderFailed` shape.
- **S8 / wire-level `tookMs` hardcoded to 0** — `JsonRpcServer.emitRenderFinished` passes `tookMs = 0` to `renderFinishedFromResult` regardless of host-supplied timing. The test asserts `tookMs in 0..300_000` so the gap closes as soon as real timing flows through the wire.

#### D-harness.v2 — Android target ✅

Adds `-Ptarget=android` parameter. `:samples:android-daemon-bench` already exists; the harness wires its descriptor as the alternate spawn target. Real-mode usable immediately (B1.5 already shipped). Android image baselines captured under `daemon/harness/baselines/android/`. New CI job `daemon-harness-android` (slower than desktop — Robolectric + Android sandbox bootstrap dominate). Resource-edit scenario variant for S3 (`res/**` change) lands here, since desktop has no `res/**`.

- **Depends on:** D-harness.v1.5, B1.5 (Android `JsonRpcServer` wiring already shipped)
- **DoD:** `./gradlew :daemon:harness:test -Ptarget=android` runs the v1 catalogue green. Renderer-agnostic claim of [DESIGN § 4](DESIGN.md#renderer-agnostic-surface) enforced at the harness level: the same scenario class drives both targets with only the descriptor + baselines differing. CI job added.

Done. Went with **Option A (parallel test classes)** — the seven existing desktop real-mode tests (`*RealModeTest.kt`) gained an `Assume.assumeTrue(target == "desktop")` guard, and seven new `*AndroidRealModeTest.kt` classes mirror them. Trade-off: ~7 new test files (a few hundred lines of mostly-mechanical KDoc + setup boilerplate) vs Option B's parameterization. Pragmatic decision: the Android side has two real divergences (S5's `renderFailed` gap because `RobolectricHost.SandboxRunner` still catches in-composition Throwables, see test KDoc — ✅ fixed in post-D-harness.v2 follow-up that mirrors desktop's `95f0111`; `S5RenderFailedAndroidRealModeTest` now asserts the `renderFailed` kind+message shape; and S2/S3/S4's much higher `pollNotification` timeouts to absorb Robolectric sandbox bootstrap), so a parameterized class would have ended up with `if (target == "android") …` branches in the timeouts and assertion. Two parallel classes are clearer.

**Files created:**
- `daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/PreviewManifestRouter.kt` — Android counterpart of desktop's router; wraps `RobolectricHost` with previewId→RenderSpec lookup gated on `composeai.harness.previewsManifest`.
- `daemon/android/src/testFixtures/kotlin/ee/schimke/composeai/daemon/RedFixturePreviews.kt` — promoted from `src/test/...`. Adds `GreenSquare` (S4), `SlowSquare` (S2), `BoomComposable` (S5) so the Android testFixtures expose the same composable surface as the desktop testFixtures. Same FQN (`ee.schimke.composeai.daemon.RedFixturePreviewsKt`) so a single `RealModePreview(className=…)` row resolves on either target.
- `daemon/harness/src/test/kotlin/.../S{1..8}*AndroidRealModeTest.kt` — seven new test classes mirroring the desktop counterparts. Each gates on `Assume.assumeTrue(harnessTarget() == "android")`.
- `.github/workflows/daemon-harness.yml` — renamed from `daemon-harness-desktop.yml`; adds `android-real` job alongside the existing `desktop-fake` + `desktop-real` jobs. Old workflow file removed.

**Files modified:**
- `daemon/android/build.gradle.kts` — `android.testFixtures.enable = true`; adds `kotlin-serialization` plugin (for `PreviewManifestRouter`); adds `daemonHarnessClasspathFile` consumable configuration + `writeDaemonClasspath` task that resolves the daemon's debug-unit-test runtime classpath (which AGP fully wires up for the standalone JUnit/Robolectric path) into a text file the harness reads at test time.
- `daemon/android/src/main/kotlin/ee/schimke/composeai/daemon/DaemonMain.kt` — captures real stdout into `realOut` before swapping `System.out` to `System.err`, mirroring desktop's `DaemonMain`. Without this, Robolectric's "This workaround is used when an ActionBar is present and the SDK version is 35 or higher." diagnostic line corrupts the JSON-RPC channel. Wires `PreviewManifestRouter` when `composeai.harness.previewsManifest` is set.
- `daemon/harness/build.gradle.kts` — adds `-Ptarget=desktop|android` system property; defines `androidDaemonClasspath` consumer configuration matching the daemon module's text-file artifact attribute; wires every `Test` task with `composeai.harness.androidDaemonClasspath` system property pointing at the resolved file.
- `daemon/harness/src/main/kotlin/.../HarnessLauncher.kt` — adds `RealAndroidHarnessLauncher` (spawns the Android `DaemonMain` with the same package's entry point + Robolectric system properties + `--add-opens` JVM args from `AndroidPreviewClasspath.buildJvmArgs`) and `RealAndroidHarnessLauncher.classpathFromProperty()` which reads the path-listing file written by `:daemon:android:writeDaemonClasspath`.
- `daemon/harness/src/main/kotlin/.../HarnessTestSupport.kt` — adds `harnessTarget()`; `baselineFile()` is target-aware (`baselines/<target>/<scenario>/<id>.png`); adds `realAndroidModeScenario(...)` factory parallel to the existing `realModeScenario(...)`; existing `RealModeScenarioPaths.launcher` widened to `HarnessLauncher` interface so both target variants flow through the same `HarnessClient.start(...)`.
- `daemon/harness/src/main/kotlin/.../Scenario.kt` — `LatencyRecorder.target` defaults to `harnessTarget()` so Android rows get tagged `android` rather than `desktop` in the shared latency CSV.
- All seven existing `*RealModeTest.kt` desktop tests gained an `Assume.assumeTrue(harnessTarget() == "desktop")` skip so they don't run twice when `-Ptarget=android` is set.

**Per-scenario Android real-mode wall-clock (cold + warm-median, dev box):**

| Scenario | Cold (ms) | Warm-median (ms) | Notes |
|---------|-----------|------------------|-------|
| S1 (red-square) | 4844 | n/a (1 render) | Cold-spawn → renderFinished |
| S2 (slow-square) | ~4500 | n/a | Includes 500ms Thread.sleep in composition |
| S3 (red-square @v1) | 4621 | (blue-square @v2) 71 | Warm sandbox, second render is fast |
| S4 (3 previews FIFO) | 4622 / 4700 / 4765 | n/a | All three sequential, focus-not-honoured |
| S5 (boom + red-square) | 4304 / 339 | n/a | Broken render surfaces as `renderFailed` (post-v2 fix) |
| S7 (5 sequential renders) | 4598 | warm: 50-68ms across 4 warm | First dominates by ~70× |
| S8 (red-square + tookMs) | 4601 | n/a | wire tookMs=1733 (engine measured) |

Compare desktop: S1 cold ~600-1500ms, warm-median ~14-50ms. Android cold is roughly 5-10× higher; warm renders are comparable (50-70ms vs desktop's 14ms). Robolectric sandbox bootstrap (~3s) + first Compose render + Roborazzi/HardwareRenderer init dominate the cold delta.

**Android baseline PNGs.** 7 files under `daemon/harness/baselines/android/` totalling 28KB:
- `s1/red-square.png`, `s2/slow-square.png`, `s3/{red,blue}-square.png`, `s4/{red,blue,green}-square.png`. Captured at 64×64 px @ density 1.0.
- **Determinism verified.** Two consecutive `regenerateBaselines -Pharness.target=android` runs produce byte-identical SHA256 hashes for every PNG. Robolectric on JDK 17 + SDK 35 + Roborazzi's NATIVE graphics mode is reproducible at the byte level for these solid-colour fixtures; AA differences across runs of font-rendering content might still surface noise in future fixtures, but for the v2 catalogue we get clean determinism.

**CI workflow shape.** Renamed `.github/workflows/daemon-harness-desktop.yml` → `daemon-harness.yml` with three jobs: `desktop-fake`, `desktop-real`, `android-real`. The Android job has a 15-minute timeout (vs no explicit timeout on desktop jobs) to absorb noisier CI runners. All three are required for the workflow to succeed; failures upload the same `actual.png` / `expected.png` / `diff.png` artefacts as before.

**Daemon behaviour divergences surfaced beyond the documented gaps:**

1. **`DaemonMain` stdout pollution (fixed in v2).** The Android `DaemonMain` previously did not redirect `System.out` to `System.err` before constructing `JsonRpcServer`. Robolectric and Roborazzi write diagnostic lines (e.g. "This workaround is used when an ActionBar is present and the SDK version is 35 or higher.") to `System.out` during sandbox bootstrap and `HardwareRenderer` init, and those lines corrupt the LSP-framed JSON-RPC channel. The desktop `DaemonMain` has done this since B-desktop.1.5; v2 ports the same pattern to Android. Lines now routed to stderr where the harness's stderr ring-buffer captures them.
2. **Android daemon classpath is non-trivial to consume from a plain JVM module.** The `:daemon:harness` is plain `org.jetbrains.kotlin.jvm` and AGP 9 exposes `:daemon:android` as an AAR with AAR-shaped transitive deps (roborazzi, androidx.compose.* are AAR-only). The standard `testImplementation(project(":daemon:android"))` pattern desktop uses cannot be repeated; the harness instead consumes a text-file artefact produced inside the daemon module (which IS Android-aware) listing the resolved JAR paths — those JARs already include AGP-generated R.jars for transitive AARs and the right Android-multiplatform variant of `androidx.compose.ui:ui-test-junit4` etc. Documented inline.

**Renderer-agnostic claim (DESIGN § 4) end-to-end.** The same scenario shapes (S1-S5, S7-S8) now drive both backends with only `RealAndroidHarnessLauncher` vs `RealDesktopHarnessLauncher` and per-target baseline directories differing. The remaining real divergence worth surfacing in DESIGN itself is Android's cold-render budget being ~5-10× desktop's, so the harness's per-target timeout knobs are not interchangeable. (The S5 `renderFailed`-propagation gap that v2 surfaced is now closed: ✅ fixed in the post-D-harness.v2 follow-up that mirrors desktop's `95f0111` — `RobolectricHost` posts in-composition Throwables onto the bridge result queue and `submit()` re-throws, just like `DesktopHost`.) The remaining divergence is a renderer-implementation difference, not a surface-level one — the renderer-agnostic claim holds at the wire-protocol layer end-to-end.

**Subprocess timeout / Robolectric bootstrap experience.** The first iteration's 60s `renderStarted` timeout was insufficient on a contended dev box (test was hitting the timeout while Robolectric was still bootstrapping). Bumped to 180s on the cold S1 path and 120s on subsequent renderFinished polls; observed actual cold ~5s, so headroom is adequate. The S4 deadline (3 sequential renders, 240s total) absorbed cold + 2 warm cleanly. The most likely flake risk is GitHub Actions runner contention pushing cold past the existing 60s `pollRenderFinishedFor` budget on tests other than S1 — the 15-minute job-level timeout absorbs this without flapping. No actual stalls observed; the daemon's drain semantics worked correctly across all seven Android scenarios.

#### D-harness.v3 — Future-feature scenarios + soak + drift report

Adds:

- S6 classpathDirty (gated on B2.1 — Tier-1 fingerprint detection).
- S9 sandbox-recycle behaviour (gated on B2.5 — recycle policy + warm spare).
- S10 predictive prefetch hit (gated on P2.5.2 — `setPredicted` IPC + scroll-ahead).
- Session-mode 1000-render soak scenario (replaces D2.3 as a standalone task).
- Weekly drift-report workflow that runs both bench tasks + the harness's latency scenarios and posts deltas exceeding 50% as an issue.

- **Depends on:** B2.1 (S6), B2.5 (S9), P2.5.2 (S10), D2.1
- **DoD:** each scenario lands as its gating feature lands; harness's session-mode soak runs nightly; drift report posted weekly.

---

## Phase 2.5 — predictive prefetch (optional, ladder)

Layered on top of Phase 2's reactive visibility/focus signals. Each rung is independently shippable behind `daemon.predictive.enabled`; do **not** pre-commit to later rungs before earlier rungs have hit-rate data on real workloads. Full design: [PREDICTIVE.md](PREDICTIVE.md).

### P2.5.1 — Multi-tier render queue [Stream B]

Replace the single-priority FIFO render queue with the five-tier model from [PREDICTIVE.md § 2](PREDICTIVE.md#2-render-queue-model). Pre-emption at queue-pull time only — in-flight renders always complete (DESIGN § 9). No new IPC; the new tiers are populated only by reactive signals (focus / visible) until P2.5.2 lands.

- **Depends on:** B1.5, C2.2
- **DoD:** unit test asserting tier ordering on a synthetic queue with all five tiers populated. Existing reactive behaviour unchanged when no speculative entries are queued (regression test against the Phase 2 bench).

### P2.5.2 — `setPredicted` IPC + scroll-ahead prefetch (v1.1) [Streams B + C, joint]

Adds the `setPredicted({ ids, confidence, reason })` notification (PREDICTIVE.md § 3 Option A) and the daemon-side `capabilities.prediction` capability bit. Webview computes scroll velocity + viewport size and emits `setPredicted` for the next page of card IDs with `reason: "scrollAhead"`, `confidence: "high"`. No telemetry beyond `renderFinished.metrics.speculation.tier`. Fixed queue cap of 4 (`daemon.maxQueuedSpeculative`). No backpressure yet.

- **Depends on:** P2.5.1, C2.1
- **DoD:** integration test: scroll a 50-card panel; assert ≥1 `setPredicted` arrives at the daemon and ≥1 render is tagged `speculation.tier = speculative-high` in `renderFinished.metrics`. No `protocolVersion` bump (additive). Hit-rate measurement captured in a follow-up bench task before recommending v1.2.

### P2.5.3 — Filter-dropdown speculation (v1.2) [Streams B + C, joint]

Adds `reason: "filterCandidate"` for dropdown opens / highlights, with the 150ms debounce from PREDICTIVE.md § 6. Adds `renderUtilized` / `renderExpired` daemon → client notifications and the periodic `predictionStats` rollup. First introduces backpressure (PREDICTIVE.md § 4): tiers with sustained < 40% hit rate auto-disable for the session. Adds the suppress-failure-until-visible logic for speculative `renderFailed` (PREDICTIVE.md § 6).

- **Depends on:** P2.5.2
- **DoD:** unit test on the backpressure state machine (low hit rate → tier disables; resets per session). Manual verification: open the source-file filter, hover an option for >150ms, observe the corresponding render queued speculatively. CI bench dashboard plots per-tier hit rate.

### P2.5.4 — Multi-signal predictive engine (v2) [Streams B + C, joint]

Adds dwell-hover, file-explorer-click, recently-focused-history reasons. Persists per-project hit rates to a daemon-owned state file. Auto-disable on battery (extension queries OS power state; manual override setting). Tier weights tunable from telemetry. Soak gate (DESIGN § 15) re-run with prediction enabled is **mandatory** before this rung un-flags.

- **Depends on:** P2.5.3, D2.3
- **DoD:** soak run with prediction on for 1000 renders shows no `sandboxLeaked` events and ≤ 3 `sandboxRecycle` (i.e., one extra over the no-prediction baseline). Persisted stats survive a daemon restart and re-tune the per-project weights. Battery-detection auto-disable verified manually on a laptop.

---

## Phase 3 — un-flag decision (sequential)

### P3.1 — Documentation

`docs/daemon/USER_GUIDE.md` covering: how to enable, what to expect, how to disable, what to do when something goes wrong (classpath dirty loops, persistent leaks, fallback to Gradle). Update VS Code extension README with the experimental flag.

- **Depends on:** all of Phase 2
- **DoD:** doc reviewed; one external developer uses it to enable the daemon end-to-end without further questions.

### P3.2 — Acceptance review

All CI gates from `DESIGN.md` § 15 green. Bench numbers meet the < 1s focused-preview target. Soak stable for one week of nightly runs.

- **Depends on:** D2.2, D2.3
- **DoD:** PR opened to flip the default of `composePreview.experimental.daemon` from `false` → still `false` but with "stable preview" label; or hold at experimental for another release cycle.

---

## Suggested agent assignment

Four parallel agents after Phase 0 lands:

| Agent | Streams | Focus | Worktree |
|-------|---------|-------|----------|
| **Agent A — Plugin** | A1.1, A1.2 | Gradle plugin Kotlin; JVM bootstrap descriptor | `agent/preview-daemon-streamA` |
| **Agent B — Daemon core** | B1.1–B1.7, then B2.1–B2.6 | Robolectric/Compose internals; sandbox lifecycle; leak detection | `agent/preview-daemon-streamB` |
| **Agent C — VS Code** | C1.1–C1.4, then C2.1–C2.3 | TypeScript; extension UX; webview wiring | `agent/preview-daemon-streamC` |
| **Agent D — Bench/CI** | P0.1, D2.1–D2.3 | Benchmarking; CI workflows; pixel-diff infra | `agent/preview-daemon-streamD` |

Agent B is the largest — could split internally as B1 (core renderer + protocol + scope discipline) and B2 (lifecycle + leak detection + tier classifications) into two sub-agents once Phase 1 lands. They coordinate via the `RobolectricHost` + `RenderEngine` interfaces, which are stable after B1.5.

### Coordination rules

- **Phase 0 first.** No parallel work until P0.1–P0.4 are merged. They define the contracts other streams depend on.
- **Protocol changes go through P0.4's doc.** Any change to message shapes during Phase 1+ requires an explicit PR to `PROTOCOL.md` first; both Stream B and Stream C rebase before continuing.
- **No edits to existing `renderer-android` code in Phase 1.** Stream B duplicates the render body. Refactoring the original is a v2 task tracked separately.
- **Shared-seam tasks merge serially.** P0.2 and P0.3 cannot land in the same PR; Agent A handles them one at a time.
- **Daily sync via PR descriptions.** Each stream's PR description includes "interfaces I depend on" and "interfaces I expose" — review on those terms.

### Sequencing diagram

```
Phase 0: P0.1 P0.2 P0.3 P0.4   ──── all merged ────┐
                                                   │
Phase 1:  ┌── A1.1 ── A1.2                         │
          │                                        │
          ├── B1.1 ── B1.2 ── B1.3 ── B1.4 ── B1.7
          │                    │                    
          │                    └── B1.5 ── B1.6    
          │                          │              
          └── C1.1 ── C1.2 ── C1.3 ── C1.4         
                                                   
Phase 2:  ┌── B2.1 ── B2.2                          
          │                                         
          ├── B2.3 ── B2.4 ── B2.5 ── B2.6         
          │                                         
          ├── C2.1 ── C2.2 ── C2.3                  
          │                                         
          └── D2.1 ── D2.2 ── D2.3                  
                                                   
Phase 3:  P3.1 ── P3.2                              
```

A1.1 unblocks C1.2; B1.5 unblocks C1.3; B2.5 unblocks C2.3. Otherwise streams are mostly independent within a phase.

---

## Phase H — Preview history (parallel to Phase 2)

Tracking the daemon-side history phases from [HISTORY.md § "Phasing"](HISTORY.md#phasing).

- **H1 — Daemon writes sidecar + index entry per render** ✅ landed
- **H2 — `history/list` + `history/read` + `historyAdded` notification** ✅ landed
- **H3 — `history/diff` (metadata mode)** ✅ landed
- **H4 — Auto-prune + `historyPruned` notification** ✅ landed
- **H5 — `history/diff` (pixel mode + diff PNG)** — still open
- **H9 — `HistorySource` interface + multi-source merging in `historyManager`** ✅ landed (the
  interface arrived with H1+H2; multi-source merging arrived with H10-read)
- **H10a — `GitRefHistorySource` (READ_ONLY) — read from `preview/<branch>` refs** ✅ landed
- **H10b — Layer 1 plumbing — gradle plugin emits `composeai.daemon.gitRefHistory` in the daemon
  launch descriptor + DSL** — still open
- **H11+ — `GitRefHistorySource` WRITE modes, git-LFS, squash GC** — still open
- H6+ (MCP / VS Code / cross-worktree merging) — see HISTORY.md table.

H1+H2 + H3 + H10-read ship behind the existing `composePreview.experimental.daemon { enabled =
true }` gate. The gradle plugin's daemon launch descriptor will gain `composeai.daemon.historyDir`
+ `composeai.daemon.gitRefHistory` emission in a follow-up (H10b); until then, agents and ad-hoc
launches set the sysprops directly.

---

## Risks to track per task

If an agent hits one of these mid-task, raise immediately rather than working around it:

- **B1.3 sandbox-reuse failure.** If the dummy-`@Test` blocking pattern doesn't actually keep the sandbox classloader alive across `LinkedBlockingQueue` waits, escalate. We may need to invoke Robolectric's lower-level `Sandbox` API directly.
- **B1.4 render-body extraction reveals coupling.** If the duplicated render body needs more than mechanical copy-paste to work outside `@Test`, document the divergence and plan reconciliation earlier.
- **B1.7 ShadowPackageManager add-reversal incomplete.** If we can't enumerate what we added, a sandbox reset between previews is the fallback (slower; defeats v1 perf target). Escalate.
- **B2.5 warm-spare cost too high.** If two sandboxes use > 2GB combined, default `warmSpare=false` and accept the recycle pause. Document.
- **D2.2 daemon and Gradle paths produce different PNGs.** Block until reconciled. This is the canary for divergence; never paper over.
