# Preview daemon — predictive prefetch

## Why

The v1 daemon is reactive: a render only starts after `setVisible` /
`setFocus` arrives. Per
[DESIGN.md § 13](DESIGN.md#13-latency-budget) the per-preview floor is
**~1.1s** of pure render time. That floor is structural — shaving
Gradle/fork/init won't drop it. The remaining lever is to start the
render *before* the user looks, so that by the time focus lands the
PNG is already on disk. The webview has signals the daemon doesn't:
dropdowns opening, scroll velocity, file-explorer clicks, save events
on a file the user is currently scoped to.

## 9. Decisions made

- **Per-preview render cancellation stays off the table for v1.x —
  and the invariant is enforced in code, not just doc.** The Robolectric
  sandbox loop has no clean cancellation points except between Compose
  recompose ticks, and most of the wall time (`captureRoboImage` /
  `HardwareRenderer.syncAndDraw`) is uncancellable without leaking
  native graphics buffers, half-disposed `LaunchedEffect` /
  `DisposableEffect` instances, or `ShadowPackageManager` /
  `ActivityScenario` state that the next render then trips over. The
  worst failure shape is silent visual drift — colour-bleed when a
  prematurely-recycled `Bitmap`'s `GraphicBuffer` is reused for the
  next preview. The alternative ("let speculative renders complete; the
  PNG is cached and might still be useful later") is free and
  structurally clean.

  **Enforcement (so accidental cancellation can't sneak in):**
  - Render thread does not poll `Thread.interrupted()` and the daemon's
    own code never calls `interrupt()` on it.
  - Shutdown is a poison-pill on `DaemonHost`'s queue, not a thread
    abort; the in-flight render finishes before the sandbox tears down.
  - `JsonRpcServer.shutdown` drains the in-flight queue and blocks the
    response until drain completes — per PROTOCOL.md § 3.
  - JVM SIGTERM handler waits for in-flight drain before exit; SIGKILL
    is the only way to force termination mid-render.
  - Regression test: submit a render, immediately invoke shutdown,
    assert the render still completes and the result is observable.
    End-to-end variant against a real daemon subprocess: scenario S2 in
    [TEST-HARNESS.md § 3](TEST-HARNESS.md#3-scenarios-catalogue).
- **Hit-rate target (50%) accepted as a starting threshold, but
  observability is non-negotiable.** v1.1 ships `predictionStats`,
  `renderUtilized`/`renderExpired`, and a `MetricsSink` API so
  developers can observe what's working and tune thresholds against
  real data. Backpressure auto-tuning waits for v1.2.
- **Default state of `daemon.predictive.enabled`: off initially, on
  once proven.** v1.1 ships off-by-default to keep the battery / CPU
  footprint opt-in while we collect data. Default flips to on once
  metrics show good behaviour across a representative sample of
  real-use sessions.
- **Speculative-render failures are buffered until the preview becomes
  visible.** A speculative render that fails does *not* surface a badge
  on a card the user isn't looking at. The cached failure waits in the
  client; if the underlying file changes before the user gets there,
  the reactive path re-renders and the failure is replaced. Daemon
  emits `renderFailed` with `speculation.tier` set; client routes
  accordingly.
- **Telemetry: in-memory by default, behind a pluggable `MetricsSink`
  API.** v1.1 keeps `predictionStats` rollups in a per-session in-memory
  ring buffer. A `MetricsSink` interface lets later versions add
  concrete sinks (`JsonlFileSink`, `OpenTelemetrySink`,
  `PrometheusSink`) without rewriting the emit path. Default
  registration is `InMemoryRingSink`; other sinks are opt-in via the
  experimental DSL. No persistence in v1.1.
