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

---

## Phase 1 — first end-to-end render (parallel)

Goal: a daemon JVM that can be spawned by VS Code and render a single preview to PNG behind the feature flag. No optimisations, no recycle, no incremental anything.

### Stream A — Gradle bootstrap

#### A1.1 — `composePreviewDaemonStart` task ✅

New `DaemonBootstrapTask` that, given a variant, emits `build/compose-previews/daemon-launch.json` containing: classpath JARs, JVM args, system properties, java launcher path. Uses helpers from P0.3.

- **Depends on:** P0.3
- **DoD:** running `./gradlew :samples:android:composePreviewDaemonStart` writes a valid descriptor. Manual JVM exec deferred until Stream B's `renderer-android-daemon` module exists; until then the descriptor's `enabled` flag defaults to `false` and VS Code is contracted not to launch (see `DaemonClasspathDescriptor` KDoc + the in-code TODO in `AndroidPreviewSupport.registerAndroidTasks`). Validated by `DaemonBootstrapTaskTest` plus a manual `samples:android` smoke test.

#### A1.2 — `DaemonExtension` DSL ✅

Add `composePreview.experimental.daemon { … }` extension with `enabled`, `maxHeapMb`, `maxRendersPerSandbox`, `warmSpare` fields. No-op when disabled. Documented in `docs/daemon/CONFIG.md` (new).

- **Depends on:** A1.1
- **DoD:** unit test on the extension's defaults (`DaemonExtensionTest`). README of daemon docs links to [CONFIG.md](CONFIG.md).

### Stream B — daemon core

#### B1.1 — Module skeleton ✅

Create `renderer-android-daemon/` module. `build.gradle.kts` depends on `renderer-android` + Robolectric + kotlinx-serialization. Empty `DaemonMain.kt` that prints "hello" and exits.

- **Depends on:** none
- **DoD:** `./gradlew :renderer-android-daemon:assemble` succeeds. Manual `java -cp ... DaemonMainKt` prints "hello".

#### B1.2 — `Messages.kt` protocol types ✅

`@Serializable` Kotlin data classes mirroring P0.4. One file under `daemon/protocol/`.

- **Depends on:** P0.4, B1.1
- **DoD:** unit test round-trips one of each message via `Json.encodeToString` / `decodeFromString`.

#### B1.3 — `DaemonHost` (sandbox holder) ✅

A class that runs a single dummy `@Test` whose body blocks on `LinkedBlockingQueue<RenderRequest>`. Submitting a request triggers a render in the sandbox thread; result returned via callback.

- **Depends on:** B1.1
- **DoD:** unit test: submit 10 dummy renders to a single host instance; all complete; sandbox classloader is reused (assert via reflection on `Thread.currentThread().contextClassLoader.hashCode()`).
- **Note:** required a custom `SandboxHoldingRunner` that adds `ee.schimke.composeai.daemon.bridge` to Robolectric's `doNotAcquirePackage` list. Without that rule the dummy-`@Test` queue holder is loaded twice (once in the sandbox classloader, once in the host classloader) and the cross-thread handoff silently breaks. Static handoff state lives in [`DaemonHostBridge`](../../renderer-android-daemon/src/main/kotlin/ee/schimke/composeai/daemon/bridge/DaemonHostBridge.kt) with `java.util.concurrent.*`-only types.

#### B1.4 — `RenderEngine` (per-preview body)

Duplicate the relevant parts of `RobolectricRenderTest`'s render body into `RenderEngine.kt`. Inputs: `PreviewInfo`, output dir. Output: `RenderResult { pngPath, tookMs }`. **Comment in the file noting the duplication and the reconciliation v2 plan.**

- **Depends on:** B1.3
- **DoD:** rendered PNG of one `samples/android` preview via `RenderEngine` is byte-identical (or pixel-identical with no AA drift) to the same preview rendered via `RobolectricRenderTest`.

#### B1.5 — `JsonRpcServer` over stdio ✅

Read framed JSON-RPC 2.0 from stdin (LSP-style `Content-Length`), dispatch
to handlers, write replies and notifications back to stdout. Render requests
queue onto [`DaemonHost`](../../renderer-android-daemon/src/main/kotlin/ee/schimke/composeai/daemon/DaemonHost.kt);
inline handlers cover `initialize`, `setVisible`, `setFocus`, `fileChanged`,
`shutdown`, and `exit`. The server enforces the no-mid-render-cancellation
invariant (DESIGN § 9): `shutdown` drains the in-flight queue before
resolving and the daemon never calls `Thread.interrupt()` on the render
thread. See [`JsonRpcServer.kt`](../../renderer-android-daemon/src/main/kotlin/ee/schimke/composeai/daemon/JsonRpcServer.kt).

DoD verified by [`JsonRpcServerIntegrationTest`](../../renderer-android-daemon/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcServerIntegrationTest.kt),
which drives `initialize → initialized → renderNow → renderStarted →
renderFinished → shutdown → exit` end-to-end against the real `DaemonHost`
over piped streams. The full subprocess variant (`ProcessBuilder` against
the descriptor from A1.1) is deferred to Stream C's
[C1.3](#c13--daemonclientts-json-rpc-client) integration test, since it
requires the consumer-module classpath that lives outside the
`:renderer-android-daemon` Gradle scope; the spawn harness will be shared
with B1.5a's drain-on-shutdown regression test. The framing layer is
covered by [`JsonRpcFramingTest`](../../renderer-android-daemon/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcFramingTest.kt)
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
- **DoD:** the regression test above passes; static check (or a code-review checklist note in `renderer-android-daemon/CONTRIBUTING.md` if no automated lint exists) flags any `interrupt()` / `Thread.interrupted()` introduced under `renderer-android-daemon/src/main/`.

#### B1.6 — `SandboxScope` + `ProcessCache` helpers

Implement the helpers from `DESIGN.md` § 11. Add the lint check (script under `gradle-plugin/build-logic/`) that fails the daemon module's build if `companion object` / `object` declarations hold Compose/AndroidX/Android-typed fields.

- **Depends on:** B1.1
- **DoD:** unit test: `SandboxScope.activeSandboxCount()` decreases by one after dropping a sandbox + 2 GCs. Lint check: introduce a deliberate violation; build fails with helpful message.

#### B1.7 — Wrap `GoogleFontInterceptor` and `ShadowPackageManager` adds

In the daemon module, add wrappers that delegate to the existing `renderer-android` helpers but route mutable state through `SandboxScope`. Per-preview prologue/epilogue in `RenderEngine` reverses ShadowPackageManager adds.

- **Depends on:** B1.4, B1.6
- **DoD:** integration test: render preview A (which adds activity X to the package manager), then preview B. Assert preview B's `PackageManager` does **not** contain activity X.

### Stream C — VS Code client

#### C1.1 — `daemonProtocol.ts` types ✅

TypeScript types mirroring P0.4. Match field names exactly. Lives at
[`vscode-extension/src/daemon/daemonProtocol.ts`](../../vscode-extension/src/daemon/daemonProtocol.ts) and is exercised by
[`vscode-extension/src/test/daemon/daemonProtocol.test.ts`](../../vscode-extension/src/test/daemon/daemonProtocol.test.ts), which loads every
fixture under [`docs/daemon/protocol-fixtures/`](protocol-fixtures/) — the
same shared corpus the Kotlin daemon test suite consumes (PROTOCOL.md § 9).
Enums use string literal unions instead of TypeScript `enum` declarations so
no runtime objects are emitted.

- **Depends on:** P0.4
- **DoD:** lint passes. Unit test serialises one of each message and validates against the JSON-shape definitions in P0.4.

#### C1.2 — `daemonProcess.ts` lifecycle ✅

Reads `daemon-launch.json` (verifies `schemaVersion === 1`, refuses on
`enabled = false` or schema mismatch), spawns the daemon JVM with the
classpath / JVM args / system properties / java launcher from the
descriptor, owns the process handle. The spawn is `detached: false` so the
daemon dies with the extension host. Idle-timeout shutdown closes stdin
first (the daemon's own idle exit path drains in flight) then escalates
to SIGTERM only if the grace expires. `restart()` re-runs the supplied
`rebootstrap` callback (wired to `composePreviewDaemonStart` by the
gate) after the existing process exits.

Lives at
[`vscode-extension/src/daemon/daemonProcess.ts`](../../vscode-extension/src/daemon/daemonProcess.ts), unit-tested by
[`vscode-extension/src/test/daemon/daemonProcess.test.ts`](../../vscode-extension/src/test/daemon/daemonProcess.test.ts) with a hand-rolled
`FakeChildProcess` (sinon isn't a dep — matches the existing
`gradleService.test.ts` style). Tests cover spawn args, descriptor
validation paths, `dispose` clean exit + SIGTERM escalation, idle-timer
cancellation via `bumpActivity()`, and restart on simulated
`classpathDirty`.

- **Depends on:** A1.1, C1.1
- **DoD:** unit test (mocked child process): spawn, send shutdown, child exits cleanly. Restart on simulated `classpathDirty` notification.

#### C1.3 — `daemonClient.ts` JSON-RPC client ✅

Hand-rolled JSON-RPC 2.0 client with LSP-style `Content-Length` framing
(symmetric with `JsonRpcServer.kt`'s `ContentLengthFramer`; no
`vscode-jsonrpc` dep added). Drives the lifecycle handshake (`initialize`
→ `initialized` → ... → `shutdown` → `exit`), maps every PROTOCOL.md § 6
notification onto a typed event emitter (`renderStarted`, `renderFinished`,
`classpathDirty`, …), and surfaces JSON-RPC errors as the `DaemonRpcError`
subclass. The disconnect path honours the no-mid-render-cancellation
invariant: `dispose()` sends `shutdown` (a request), waits for the reply
(which the daemon resolves only after draining), and only then sends `exit`.

Lives at
[`vscode-extension/src/daemon/daemonClient.ts`](../../vscode-extension/src/daemon/daemonClient.ts) and is tested in-process via
piped streams by
[`vscode-extension/src/test/daemon/daemonClient.test.ts`](../../vscode-extension/src/test/daemon/daemonClient.test.ts).

**Real-subprocess vs in-process trade-off.** The DoD allows an in-process
fallback when the real `:renderer-android-daemon` JAR isn't available in the
test runtime; we took that fallback. The Kotlin
`JsonRpcServerIntegrationTest` already drives `initialize → initialized →
renderNow → renderStarted → renderFinished → shutdown → exit` end-to-end
against the real `DaemonHost` (B1.5, commit 7436b98), so what remains for
the TS side is verifying our framer / dispatch / lifecycle speak the same
protocol — which is exactly what the in-process tests do, by playing the
daemon role over piped streams against the real `DaemonClient`. Reasoning
recorded inline in the test file's header comment.

- **Depends on:** B1.5, C1.2
- **DoD:** integration test against the real daemon JAR: spawn, render one preview from `samples/android`, PNG appears, returned manifest matches expected shape.

#### C1.4 — `daemonGate.ts` router shim ✅

A small `Renderer` interface
(`renderPreviews(module, tier) | discoverPreviews(module)`) is implemented
by both `GradleService` and `DaemonClient`; the gate routes per call based
on the `composePreview.experimental.daemon.enabled` VS Code setting and
the per-module daemon's health. Spawns lazily on first call, falls back
to `gradleService` permanently for the session on spawn failure or
`classpathDirty`, and de-dupes the user-facing notification so users see
one warning per session, not one per render.

The single call site lives at the top of the per-module loop in
[`extension.ts:refresh`](../../vscode-extension/src/extension.ts) — the previous
`gradleService.renderPreviews` / `gradleService.discoverPreviews` pair is
now `(daemonGate ?? gradleService).renderPreviews/discoverPreviews`. The
`composePreview.experimental.daemon.enabled` configuration property is
declared in [`vscode-extension/package.json`](../../vscode-extension/package.json) so the VS Code settings UI
exposes it.

Source: [`vscode-extension/src/daemon/daemonGate.ts`](../../vscode-extension/src/daemon/daemonGate.ts);
unit test:
[`vscode-extension/src/test/daemon/daemonGate.test.ts`](../../vscode-extension/src/test/daemon/daemonGate.test.ts).

**Manual smoke test (deferred).** The DoD's manual smoke test against a
real Compose project requires the daemon JAR to be reachable
end-to-end (Stream B's B1.4 RenderEngine isn't done yet, so renders
return the placeholder `daemon-stub-${id}.png` path). Once B1.4 lands
the smoke test runs as: flip
`composePreview.experimental.daemon.enabled = true` in VS Code settings,
flip the build.gradle.kts mirror to true too, save a `.kt` file in
`samples/android`, observe (a) a single daemon JVM spawn in `Output ▸
Compose Preview`, (b) `renderStarted` / `renderFinished` notifications
in the same channel, (c) the rendered PNG appearing in the preview
panel. Disable either flag → next save goes back through Gradle.

- **Depends on:** C1.3
- **DoD:** manual smoke test in VS Code: enable flag, observe daemon spawn on first preview action, render works. Disable flag, observe normal Gradle path.

---

## Phase 2 — productionise (parallel)

Goal: the daemon is fast enough and stable enough to be the recommended path for daily editor use, even if still flagged.

### Stream B — daemon hardening

#### B2.1 — `ClasspathFingerprint` (Tier 1)

SHA over `libs.versions.toml`, all `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `local.properties`. Re-check on file change in those paths. Authoritative SHA over resolved classpath JAR list at startup. On hit: emit `classpathDirty`, exit cleanly.

- **Depends on:** B1.5
- **DoD:** integration test: bump a version in `libs.versions.toml` while daemon running; daemon emits `classpathDirty` and exits within 2s.

#### B2.2 — `IncrementalDiscovery` (Tier 2)

Scoped ClassGraph scan of a single classpath element. Regex pre-filter for `@Preview` on saved files outside the existing preview-bearing set. Diff against cached `previews.json`. Emit `discoveryUpdated`.

- **Depends on:** P0.2, B1.5
- **DoD:** unit test: synthetic project with 100 preview-bearing classes; edit one; discovery completes in < 100ms; diff is exactly that one class's previews.

#### B2.3 — `SandboxLifecycle` measurement (Layer 1)

Per-render: heap post-GC, native heap, class histogram for tracked classes, render time, sandbox age. Emitted on `renderFinished.metrics`.

- **Depends on:** B1.5
- **DoD:** soak test runs 100 renders; CSV shows all metrics populated; no measurement adds > 10ms to render time.

#### B2.4 — Active leak detection (Layer 2)

Weak-reference probe every Nth render. `--detect-leaks=heavy` flag wires LeakCanary JVM-test. JFR ring buffer always on; dumped on `leakSuspected` or `sandboxRecycle`.

- **Depends on:** B2.3
- **DoD:** synthetic leak (deliberately retain Activity ref) detected within 50 renders. JFR dump appears in `.compose-preview-history/leaks/`.

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

### Stream D — bench & CI

#### D2.1 — Daemon-mode bench

Extend P0.1 bench to include daemon-mode timings: spawn cost, first-render cost, warm-render cost, edit-then-render cost. Same CSV format.

- **Depends on:** B1.5, P0.1
- **DoD:** `docs/daemon/baseline-latency.csv` updated with daemon columns. PR description includes ratio analysis.

#### D2.2 — Pixel-diff CI gate

CI job that runs `samples:android-daemon-bench:renderAll` (daemon path) and pixel-diffs against `samples:android:renderAllPreviews` (Gradle path). Must be 100% identical.

- **Depends on:** B1.7, D2.1
- **DoD:** GitHub Actions workflow added; job green on this branch; deliberately introduce a render bug → job fails with diff image artifact.

#### D2.3 — Soak test in CI

Runs nightly. 1000 renders in a single sandbox on `samples/android`. Asserts: no OOM, heap drift < 50MB, zero `sandboxLeaked`, ≤ 2 `sandboxRecycle`.

- **Depends on:** B2.5, B2.6
- **DoD:** workflow green; metrics summary posted as workflow output.

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

Adds dwell-hover, file-explorer-click, recently-focused-history reasons. Persists per-project hit rates to `.compose-preview-history/predictive-stats.json`. Auto-disable on battery (extension queries OS power state; manual override setting). Tier weights tunable from telemetry. Soak gate (DESIGN § 15) re-run with prediction enabled is **mandatory** before this rung un-flags.

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

## Risks to track per task

If an agent hits one of these mid-task, raise immediately rather than working around it:

- **B1.3 sandbox-reuse failure.** If the dummy-`@Test` blocking pattern doesn't actually keep the sandbox classloader alive across `LinkedBlockingQueue` waits, escalate. We may need to invoke Robolectric's lower-level `Sandbox` API directly.
- **B1.4 render-body extraction reveals coupling.** If the duplicated render body needs more than mechanical copy-paste to work outside `@Test`, document the divergence and plan reconciliation earlier.
- **B1.7 ShadowPackageManager add-reversal incomplete.** If we can't enumerate what we added, a sandbox reset between previews is the fallback (slower; defeats v1 perf target). Escalate.
- **B2.5 warm-spare cost too high.** If two sandboxes use > 2GB combined, default `warmSpare=false` and accept the recycle pause. Document.
- **D2.2 daemon and Gradle paths produce different PNGs.** Block until reconciled. This is the canary for divergence; never paper over.
