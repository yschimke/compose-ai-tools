# Preview daemon — end-to-end test harness

The `:daemon:harness` module is a **VS-Code-shaped driver** that
exercises a real daemon JVM over JSON-RPC. Scenarios S1–S10 ship across
both desktop and android targets in fake and real modes; CI runs
`desktop-fake`, `desktop-real`, and `android-real` jobs in
`.github/workflows/daemon-harness.yml`.

The harness is the authoritative end-to-end test bed for daemon
features — protocol, render correctness, lifecycle invariants,
predictive prefetch, the cost model, and the sandbox-recycle dance.

## 2. Architecture

A new module `:daemon:harness` under `tools/` applying
`org.jetbrains.kotlin.jvm` only (no Android, no Compose plugins).
JUnit test source set runs the scenario catalogue against a fresh
daemon per scenario; a `main()` entry point drives a single scenario
interactively for debugging.

Depends on `:daemon:core` for
[`Messages.kt`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt)
and `ContentLengthFramer`. Type-level drift between harness and daemon
is impossible — they share the data classes.

```
[harness JVM] ── ProcessBuilder(java, -cp …, DaemonMainKt) ──► [daemon JVM]
              ◄── stdin/stdout (LSP-framed JSON-RPC) ──►
              ◄── stderr (ring-buffered, dumped on failure)
              ── timeout supervisor: SIGTERM on deadline
              ── shutdown sequence: shutdown→exit, await natural exit
```

One daemon process per scenario by default (clean state). Opt-in
"session" mode (§ 6) reuses one daemon across scenarios for
long-running behaviour checks.

## 3. Scenarios catalogue

| # | Name | Gating | What it proves |
|---|------|--------|---------------|
| S1 | Lifecycle happy path | none | protocol round-trip works |
| S2 | Lifecycle drain semantics | B-desktop.1.6 | no-mid-render-cancellation invariant |
| S3 | Render-after-edit | B2.2 (Tier-2) | stale detection + re-render |
| S4 | Visibility filter | reactive only | tier-based render ordering |
| S5 | renderFailed surfacing | none | error path doesn't crash daemon |
| S6 | classpathDirty + restart | B2.1 | Tier-1 fingerprint detection |
| S7 | Latency budget | P0.6 baseline | daemon's claimed wins are real |
| S8 | Cost-model parity | none | cost catalogue matches measured wall-time |
| S9 | Sandbox-recycle behaviour | B2.5 | recycle invariant (DESIGN § 9) |
| S10 | Predictive prefetch hit | P2.5.2 | speculative renders surface as `tier=speculative-high` |

Per-scenario flow:

- **S1.** `initialize` → `initialized` → `renderNow` → `renderFinished`
  → `shutdown` → `exit` → exit code 0. Asserts PNG exists and
  pixel-diffs against baseline.
- **S2.** `renderNow` → (no wait) `shutdown` → assert `shutdown` response
  arrives **after** `renderFinished`. Regression-tests
  [DESIGN § 9](DESIGN.md#9-no-mid-render-cancellation--invariant--enforcement).
- **S3.** Render → `editSource` (string-literal swap) →
  `fileChanged({kind:"source"})` → `discoveryUpdated` → re-render.
  Pixel-diff expects difference between v1 and v2.
- **S4.** `setVisible(["A","B","C"])` → `setFocus(["A"])` → assert
  `renderStarted[0].id == "A"` (focus first); all three
  `renderFinished` arrive.
- **S5.** Dedicated source variant with `error("boom")` preview;
  `renderFailed.error.kind == "runtime"`; daemon stays responsive for
  follow-up `renderNow`.
- **S6.** `editSource(libs.versions.toml)` →
  `fileChanged({kind:"classpath"})` → `classpathDirty.reason ==
  "fingerprintMismatch"` → process exit. Subsequent `renderNow` returns
  `ClasspathDirty` JSON-RPC error.
- **S7.** Cold first render ≤ baseline + 25%; warm renders ≤
  warm-baseline + 25%. Per-target rows from `baseline-latency.csv`.
- **S8.** Render STATIC=1, SCROLL_END=3, ANIMATION=50; assert measured
  ratios within ±50% of cost-catalogue ratios.
- **S9.** Launch with `composeai.daemon.maxRendersPerSandbox=2`. Submit
  5 sequential renders. Asserts ≥ 2 `sandboxRecycle` notifications; all
  5 `renderFinished` arrive; all 5 PNGs pixel-identical.
- **S10.** `setPredicted({ids:["B"], confidence:"high"})` →
  `setFocus(["B"])`. Asserts `metrics.speculation.tier ==
  "speculative-high"` and `renderUtilized` arrives within horizon.

## 4. Image verification

**Pixel-diff with tolerance.**

- **Per-pixel delta:** maximum allowed `|Δr| + |Δg| + |Δb|` per pixel.
  Default 3.
- **Aggregate fraction:** maximum fraction of pixels exceeding the
  per-pixel threshold. Default 0.5%.
- **Absolute cap:** no single pixel exceeds 50 LSB total.

When verification fails, the harness writes three artefacts under
`build/reports/daemon-harness/<scenario>/`: `actual.png`,
`expected.png`, `diff.png`. Surfaced as CI artefacts.

The harness ships `PixelDiff.kt` under `:daemon:harness` (no
third-party dependency). The D2.2 pixel-diff CI gate also consumes it.

**Image baselines.** Two storage strategies:

- **Generated test patterns** — for fake-mode scenarios, the harness's
  fixtures and expected baselines come from a `TestPatterns.kt`
  generator (solid-colour boxes, text-on-box, gradient strips). Live in
  `build/daemon-harness/test-patterns/`; nothing checked in.
- **Captured real-render baselines** — for real-mode scenarios, baseline
  is whatever the real Compose renderer produces. Stored in-repo at
  `daemon/harness/baselines/<target>/<scenario>/<id>.png`.

`:daemon:harness:regenerateBaselines` re-runs every scenario in capture
mode; overwrites baselines.

## 6. Subprocess management

**Spawn:** `:daemon:harness:test` `dependsOn` the target's
`composePreviewDaemonStart` so the descriptor is fresh; harness loads
`<bench>/build/compose-previews/daemon-launch.json`, validates
`enabled == true`, builds the command from the descriptor's
`javaLauncher`, `jvmArgs`, `classpath`, `mainClass`, and runs
`ProcessBuilder` with `redirectError(PIPE)` + `redirectOutput(PIPE)`.

**Stream handling:**
- **stdout** is the JSON-RPC channel; reader thread parses
  Content-Length frames.
- **stderr** is ring-buffered (last 64KB). Dumped on failure only.
- **stdin** fed from `LinkedBlockingQueue<String>` by one writer thread
  per daemon.

**Timeouts:** per-scenario default 30s (cold may set 60s; soak 5min).
On expiry: dump stderr, `SIGTERM`, wait 5s, `SIGKILL`. Test fails with
"daemon hang" tag.

**Shutdown sequencing on success:** send `shutdown` request → await
response → send `exit` notification → `Process.waitFor(10s)` and assert
exit code 0. If the process doesn't exit in 10s, `SIGTERM`+`SIGKILL`
ladder and fail.

**Session mode (opt-in):** one daemon JVM hosts a sequence of scenarios
for soak-shaped tests.

## 8a. The `FakeHost` test fixture

The harness ships a `FakeHost` that implements
[`RenderHost`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/RenderHost.kt).
It runs in the same JVM as the real daemon (it *is* the daemon's
`RenderHost` for fake-mode runs), serves PNGs from a local fixture
directory keyed by preview ID, and reads pre-cooked metadata from a
small JSON manifest.

Lives under `daemon/harness/src/main/kotlin/.../FakeHost.kt` with
fixtures in `daemon/harness/fixtures/<scenario>/`. Each scenario's
fixture directory contains:

- `previews.json` — same shape as a real `composePreviewDaemonStart`
  manifest.
- `<previewId>.png` — the PNG the fake "renders" for that preview.
- Optional per-preview overrides: `<previewId>.delay-ms` to simulate a
  slow render, `<previewId>.error` to simulate `renderFailed`,
  `<previewId>.metrics.json` to override `renderFinished.metrics`.

Wiring:

- `-Pharness.host=fake` — harness spawns a daemon JVM whose entry point
  is `FakeDaemonMain` that wires `JsonRpcServer` → `FakeHost`. No
  `:daemon:android` or `:daemon:desktop` on the classpath.
- `-Pharness.host=real` — harness spawns the real
  `composePreviewDaemonStart` descriptor.

`FakeHost` is **not** a substitute for real-renderer integration tests
(B1.5's `JsonRpcServerIntegrationTest`, B-desktop.1.5's equivalent).
Those exercise the actual sandbox lifecycle, classloader behaviour, and
Compose render path. `FakeHost` exercises everything *above* the host:
protocol dispatch, lifecycle, scenario sequencing, image diff,
file-edit primitives, latency recording, CI wiring.

## 9. Phasing

**v0 — single happy-path scenario, desktop only, against `FakeHost`.**
Module `:daemon:harness` exists. S1 only. `PixelDiff.kt` shipped; one
baseline PNG; one fixture directory under `daemon/harness/fixtures/s1/`.
Subprocess plumbing works end-to-end against a `FakeDaemonMain`. CI
workflow runs S1 on every PR. Independent of B-desktop.1.5 thanks to
`FakeHost`.

**v1 — full reactive scenario catalogue, desktop only, against
`FakeHost`.** S2 (drain), S3 (render-after-edit; for fake mode the
"edit" maps to swapping which fixture the host serves), S4
(visibility), S5 (renderFailed; configured via fixture
`<previewId>.error`), S7 (latency; **recorded only, not asserted**), S8
(cost-model parity; configured via fixture `<previewId>.metrics.json`).
File-edit primitive with auto-revert + bytecode-visibility check.
Per-scenario timeouts, stderr buffering, baseline regeneration task.
Latency CSV deltas surfaced as CI artefacts; humans read trends, no
test fails on perf.

## 11. Decisions made

- **Module location:** new top-level module `:daemon:harness` (plain
  `org.jetbrains.kotlin.jvm`, depends only on `:daemon:core`). The
  harness is a fourth client of the protocol (alongside VS Code,
  in-process unit tests, and the bench harness).
- **Pixel-diff defaults:** per-pixel ≤ 3 LSB, aggregate ≤ 0.5% pixels
  exceeding, absolute cap ≤ 50 LSB. Adjustable per-scenario.
- **Image baselines:** two-strategy split. Fake-mode uses
  deterministically-generated test patterns (`TestPatterns.kt`);
  nothing checked in. Real-mode captures actual Compose-rendered PNGs
  to in-repo `daemon/harness/baselines/<target>/<scenario>/<id>.png`.
- **Latency assertions: record-only, not gating.** v1 captures
  per-scenario latency, writes a delta-vs-baseline row to a CSV
  artefact, and surfaces trends; the harness does *not* fail a CI run
  on a latency miss. A weekly drift-report workflow reads the artefacts
  and posts deltas exceeding 50% as an issue.
- **v0 before B-desktop.1.5: yes, via `FakeHost`.** Decouples harness
  work from real-renderer work entirely; stays useful afterwards as the
  way harness scenarios drive deterministic failure modes (slow render,
  render error, specific metrics). `-Pharness.host=fake|real` switches
  between the two.
