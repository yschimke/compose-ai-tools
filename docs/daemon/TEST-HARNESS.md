# Preview daemon — end-to-end test harness

> **Status:** implemented as `:daemon:harness`. Scenarios S1–S10 ship
> across both desktop and android targets in fake and real modes (per
> [TODO.md § Phase D-harness](TODO.md)); CI runs `desktop-fake`,
> `desktop-real`, and `android-real` jobs in
> `.github/workflows/daemon-harness.yml`. Outstanding work tracked in
> the v3 ladder (§ 9): session-mode soak, weekly drift-report workflow,
> S6/S9/S10 lifting once their gating features (B2.1 ✅, B2.5, P2.5.2)
> ship. This doc is the design + scenario reference; the implementation
> matches it.

This document specifies a **VS-Code-shaped driver** that exercises a real
daemon JVM over JSON-RPC the way the editor will, but without any editor in
the loop. It is the authoritative end-to-end test bed for every daemon
feature — protocol changes, render correctness, lifecycle invariants,
predictive prefetch, the cost model, and the sandbox-recycle dance — before
that feature reaches a real editor session.

Desktop-first. `:daemon:desktop` (hello-world skeleton today;
real `DesktopHost` in B-desktop.1.5) is the simpler surface — no
Robolectric `InstrumentingClassLoader`, no `bridge` package, no
`HardwareRenderer` native-buffer leaks, sub-second cold init — so a
harness there exercises wire protocol + render lifecycle + image
verification without Android complexity. Android comes later (§ 7).

## 1. Goals & non-goals

### Goals

- Drive a **real daemon subprocess** (not in-process) over the
  [v1 JSON-RPC protocol](PROTOCOL.md) — same launcher descriptor VS Code
  uses, same stdio framing, same message types.
- **Verify rendered PNGs** against checked-in baselines with bounded
  pixel-diff tolerance.
- **Assert lifecycle invariants** end-to-end: `initialize`/`initialized`
  handshake, `shutdown` drain, no-mid-render-cancellation invariant,
  `classpathDirty` exit, `sandboxRecycle` warm-spare swap.
- **Assert latency budgets** anchored to
  [`baseline-latency.csv`](baseline-latency.csv) so daemon perf wins are
  regression-tested on every PR.
- **Verify the cost model** from
  [PREDICTIVE.md § 6a](PREDICTIVE.md#6a-ux-response--predicted-vs-measured-cost-model)
  by measuring renders at multiple `Capture.cost` values and asserting
  ratios.
- **Be reusable across renderers.** Same scenarios, same assertions; only
  the descriptor module and the baseline PNG set differ between desktop
  and Android.

### Non-goals

- **Not a replacement for `:daemon:core`'s in-process unit tests.**
  [`JsonRpcServerIntegrationTest`](../../daemon/core/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcServerIntegrationTest.kt)
  remains the fast, hermetic, every-PR check on framing + dispatch. The
  harness adds the subprocess + real-host + image dimensions on top.
- **Not a replacement for the VS Code extension's tests.**
  [`vscode-extension/src/test/daemon/daemonClient.test.ts`](../../vscode-extension/src/test/daemon/daemonClient.test.ts)
  exercises the editor side of the wire. The harness exercises the daemon
  side.
- **Not a benchmark.** Stream D's existing
  [`:samples:android-daemon-bench`](../../samples/android-daemon-bench/) and
  the planned `:samples:desktop-daemon-bench` (P0.6) own perf numbers. The
  harness asserts latency *bands* against those baselines, but the
  authoritative numbers come from the bench tasks.
- **Not a compatibility matrix.** Cross-version churn (AGP, Robolectric,
  Compose BOM) is out of scope here; CI exercises that separately on bumps.
- **No new IPC.** The harness consumes [PROTOCOL.md](PROTOCOL.md) v1 as-is.
  Any new wire shape lands in the protocol doc first; the harness adopts it
  in a follow-up PR.

## 2. Architecture

### Recommended shape

A **new module `:daemon:harness`** under a fresh `tools/` directory
applying `org.jetbrains.kotlin.jvm` only (no Android, no Compose plugins).
JUnit test source set (`./gradlew :daemon:harness:test`) runs the
scenario catalogue against a fresh daemon per scenario; a `main()` entry
point (`./gradlew :daemon:harness:run`) drives a single scenario
interactively for debugging.

Depends on `:daemon:core` for the
[`Messages.kt`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt)
serialisation types and the `ContentLengthFramer` already used by
`JsonRpcServerIntegrationTest`. Type-level drift between harness and
daemon is impossible — they share the data classes.

Does **not** depend on any per-target backend. The harness reads the
`daemon-launch.json` descriptor from the target's bench module and
`exec`s `java` with the descriptor's classpath / JVM args — making it a
*pure client* of the same artefact VS Code consumes, which proves by
construction the descriptor is sufficient to launch a working daemon.

### Why a new module rather than co-locating

Three options weighed:

- **`:daemon:desktop` integrationTest source set.** Co-located
  with code under test, but cross-target reuse is awkward (same scenarios
  must run against `:daemon:android`); per-target plugin classpath
  pollution; hides that the harness is *playing the editor*, not testing
  daemon internals.
- **Extend `:samples:android-daemon-bench`.** Reuses bench subprocess
  plumbing, but muddles bench (perf numbers) with harness (correctness),
  and bench is Android-specific.
- **New `:daemon:harness`.** Renderer-agnostic by construction; only
  depends on `:daemon:core`; doubles as a reference client for
  anyone porting the daemon to a new editor; CLI mode for debugging
  without VS Code. Cost: another module.

The harness is conceptually a **fourth client of the protocol** alongside
VS Code, the in-process unit tests, and the bench harness — encoding that
role as a top-level module makes the boundary clear.

### Module classpath wiring

`implementation(project(":daemon:core"))`,
`implementation(libs.kotlinx.serialization.json)`,
`testImplementation(libs.junit)` + `truth`. The harness reads
`<bench>/build/compose-previews/daemon-launch.json` at scenario start;
its `test` task `dependsOn` the target's `composePreviewDaemonStart` so
the descriptor is fresh. Path parameterised per target (§ 7).

### Subprocess shape

```
[harness JVM] ── ProcessBuilder(java, -cp …, DaemonMainKt) ──► [daemon JVM]
              ◄── stdin/stdout (LSP-framed JSON-RPC) ──►
              ◄── stderr (ring-buffered, dumped on failure)
              ── timeout supervisor: SIGTERM on deadline
              ── shutdown sequence: shutdown→exit, await natural exit
```

One daemon process per scenario by default (clean state). Opt-in "session"
mode (§ 6) reuses one daemon across scenarios for long-running behaviour
checks.

## 3. Scenarios catalogue

Each scenario is one self-contained test. Setup, signal sequence, file
edits, expected notifications, expected PNGs, and assertions are all
declarative — no helpers buried in test bodies.

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

Detailed flow per scenario:

### S1 — Lifecycle happy path

`initialize` → `initialized` → `renderNow({previews:["A"], tier:"fast"})`
→ harness reads `renderStarted`, `renderFinished` → `shutdown` → `exit` →
process exits with code 0 within timeout.

Assertions: PNG file at `renderFinished.pngPath` exists, is non-empty,
pixel-diffs against the baseline within tolerance. Daemon exit code 0.

### S2 — Lifecycle drain semantics (no-mid-render-cancellation)

`initialize` → `initialized` → `renderNow` → (no wait) `shutdown` → assert
`shutdown` response arrives **after** the corresponding `renderFinished`
notification. Process exits 0.

Assertions: ordering of `renderFinished` and the `shutdown` response —
`renderFinished` arrives first. PNG exists. Regression-tests
[DESIGN § 9](DESIGN.md#no-mid-render-cancellation--invariant--enforcement)
end-to-end (the in-process version is in B1.5a's regression test).

### S3 — Render-after-edit

`renderNow(["A"])` → `renderFinished` (v1) → `editSource(BenchPreviews.kt,
"\"hello\"" → "\"world\"")` → `fileChanged({kind:"source"})` →
`discoveryUpdated` (once Tier-2 lands) → `renderNow(["A"])` →
`renderFinished` (v2). Pixel-diff *expects* a difference between v1 and
v2; v2 matches "baseline-edited" PNG. File reverted in `finally`.

### S4 — Visibility filter

`setVisible(["A","B","C"])` → `setFocus(["A"])` →
`renderNow(["A","B","C"])`. Asserts `renderStarted[0].id == "A"` (focus
tier renders first per
[DESIGN § 8 Tier 4](DESIGN.md#tier-4--is-the-user-looking-at-this)) and
that all three `renderFinished` eventually arrive.

### S5 — renderFailed surfacing

A dedicated source variant contains a `@Composable fun Boom() { error("boom") }`
preview. `renderNow(["Boom"])` → `renderFailed.error.kind == "runtime"` and
stackTrace contains `"boom"`. Daemon stays responsive — a follow-up
`renderNow(["A"])` for a healthy preview succeeds.

### S6 — classpathDirty + restart

`renderNow(["A"])` → success → `editSource(libs.versions.toml)` → harness
sends `fileChanged({kind:"classpath"})` → expects `classpathDirty.reason
== "fingerprintMismatch"` then process exit (code 0) within
`classpathDirtyGraceMs + 5s` slack. A subsequent `renderNow` (before
exit) returns the `ClasspathDirty` JSON-RPC error.

Gated on B2.1; placeholder hook today, skipped via a test annotation
referencing the gating task.

### S7 — Latency budget

Spawn → time `initialize` round-trip (cold). Submit one `renderNow` for
a STATIC=1 preview, time until `renderFinished` (cold first render).
Repeat 5×. Cold first render ≤ baseline + 25%; warm renders ≤
warm-baseline + 25%. Per-target rows read from
`baseline-latency.csv`. Generous band because CI runners are noisier
than the reference machine; this catches order-of-magnitude regressions,
not routine variance.

### S8 — Cost-model parity

`renderNow` once for a STATIC=1 preview, once for a SCROLL_END=3 preview,
once for an ANIMATION=50 preview. Median 3 runs each.

Assertions: measured ratio of wall-times within ±50% of cost-catalogue
ratios. Documents drift on the dev observability channel
(PREDICTIVE.md § 9). Catches "we changed the cost catalogue but the model
no longer reflects reality."

### S9 — Sandbox-recycle behaviour

Launch with `composeai.daemon.maxRendersPerSandbox=2`. Submit 5 sequential
`renderNow`s for the same preview. Asserts ≥ 2 `sandboxRecycle`
notifications (after #2 and #4); all 5 `renderFinished` arrive; all 5
PNGs pixel-identical (recycle doesn't perturb output); no user-visible
`daemonWarming` once warm-spare lands (B2.5) — placeholder passes if
`daemonWarming` is followed by `daemonReady`.

Gated on B2.5; placeholder hook today.

### S10 — Predictive prefetch hit

`setPredicted({ids:["B"], confidence:"high", reason:"scrollAhead"})`
shortly before `setFocus(["B"])`. Asserts the `renderFinished` for B
carries `metrics.speculation.tier == "speculative-high"` and that
`renderUtilized({id:"B"})` arrives within the `speculative-high`
horizon. Gated on P2.5.2; placeholder hook today.

## 4. Image verification

### Approach

**Pixel-diff with tolerance.** Byte-exact match is too brittle (AA / font
hinting / Skiko version drift); structural / perceptual hashing is too
coarse and hides real regressions. Per-pixel RGB delta with both a
per-pixel *and* an aggregate threshold is the sweet spot:

- **Per-pixel delta:** maximum allowed `|Δr| + |Δg| + |Δb|` per pixel.
  Default 3 (i.e. ~1 LSB on each channel — accommodates JPEG-style
  rounding without letting a deliberate colour change slip through).
- **Aggregate fraction:** maximum fraction of pixels exceeding the
  per-pixel threshold. Default 0.5% — accommodates AA-edge noise around
  text without letting an entire rendered region drift unnoticed.
- **Both must hold:** any single pixel may differ by ≤ 3 LSB; up to 0.5%
  of pixels may differ by more, but no single pixel may exceed an
  absolute "egregious" cap (default 50 LSB total — catches whole-region
  colour bleed even within the aggregate budget).

When verification fails, the harness writes three artefacts under
`build/reports/daemon-harness/<scenario>/`:

- `actual.png` — what the daemon produced.
- `expected.png` — the baseline.
- `diff.png` — per-pixel delta visualisation (failed pixels highlighted).

These are surfaced as CI artefacts on failure (§ 8).

### Reusing existing infrastructure

There is **no shared pixel-diff helper in the repo today** — every test
that needs one rolls its own
([`ScrollPreviewPixelTest`](../../samples/android/src/test/kotlin/com/example/sampleandroid/ScrollPreviewPixelTest.kt),
`LongScrollPreviewPixelTest`), each fine for asserting colour dominance
but not a general-purpose diff. The harness ships a small in-tree
`PixelDiff.kt` under `:daemon:harness/src/main/kotlin/...` (no
third-party dependency) that the D2.2 pixel-diff CI gate also consumes.
Consolidation is a side-effect of this work.

### Image baselines

Distinct from the **latency baselines** in
[`baseline-latency.csv`](baseline-latency.csv) (timing data, P0.1/P0.6).
This section is about the per-scenario *expected* PNGs the harness
pixel-diffs against; "image baseline" is used unambiguously below.

Two storage strategies, picked per scenario based on what's actually
being rendered:

- **Generated test patterns** — for fake-mode scenarios (§ 8a) the
  harness's "renders" are PNGs the harness itself produced from a small
  `TestPatterns.kt` generator. Solid-colour boxes, text-on-box, gradient
  strips, alignment grids — old TV test-signal aesthetics, plus a tiny
  amount of UTF-8 text so the comparison covers font rendering. These
  live in `build/daemon-harness/test-patterns/` and are regenerated
  deterministically on every run; nothing baseline-related is checked in
  for fake-mode scenarios. Same generator produces the fixture (what
  `FakeHost` serves) and the expected baseline (what the harness diffs
  against), so the diff is a wire-layer sanity check (no
  corruption-in-flight) plus a regression test on `PixelDiff.kt` itself.
  This is what removes the in-repo / LFS / artefact-store decision for
  v0+v1 entirely.
- **Captured real-render baselines** — for real-mode scenarios (v1.5+)
  the baseline is whatever the real Compose renderer produces for a
  given `@Preview`. These *do* go in-repo at
  `daemon/harness/baselines/<target>/<scenario>/<id>.png`. The
  desktop set is small (~10 PNGs at ~100KB total); Android may
  eventually grow. Git LFS stays an escape hatch only if on-disk volume
  becomes painful — not pre-emptively wired. Decision deferred until
  v1.5 actually has captured PNGs to size up.

The pixel-diff thresholds, regeneration task, and per-scenario tolerance
overrides apply to both storage strategies — the only thing that
differs is where the bytes live.

### Regenerating baselines

`:daemon:harness:regenerateBaselines` re-runs every scenario in
capture mode — no assertion; PNG written to the baseline location,
overwriting. The PR diff of the changed PNGs is the visual-review surface
(same pattern as Roborazzi).

### Per-scenario tolerance overrides

S8 (cost-model) and S10 (prefetch) assert metrics, not PNGs. S5
(renderFailed) compares a placeholder PNG which can vary more. Tolerance
is declared per-scenario; defaults apply when absent.

## 5. File-edit simulation

The bench harness already solves this via `BenchPreviewLatencyTask`'s
`withPreviewEdit { … }` — string-literal swap, revert in `finally`. The
daemon harness adopts the same pattern as a scenario primitive
`editSource(file, from, to)`. Edits are recorded in a stack and reversed
on test exit (success *or* failure) so a crashed scenario can't leave
the working tree dirty.

**Edits must be bytecode-visible.** kotlinc strips comments, so
`edit("// foo" → "// bar")` produces identical bytecode and
`discoveryUpdated` never fires. The harness validates after each edit by
SHA'ing the bench module's `build/.../classes` directory; unchanged →
fail fast with "non-effective edit".

**Multi-file edits** are applied transactionally (all applied, one
`fileChanged` per file, all reverted in `finally`).

**Resource edits** (`res/**`, Android only) use
`fileChanged({kind:"resource"})`. Desktop has no `res/**`; resource
scenarios ship in v2.

**Classpath edits** (S6) come in two variants: a *safe no-op churn*
(comment/whitespace tweak that changes the file SHA but not the resolved
classpath — proves the fingerprint hashes file content) and a *real
version literal change* (canary for noticing a real drift).

**No `git stash` fallback.** The harness reverts its own edits; a
crashed test leaves a `daemon/harness/build/PENDING_REVERTS.json`
marker that the next run reverts or fails fast on. `git stash` would
risk eating unrelated developer work; never invoke it.

## 6. Subprocess management

**Spawn:** `:daemon:harness:test` `dependsOn` the target's
`composePreviewDaemonStart` so the descriptor is fresh; harness loads
`<bench>/build/compose-previews/daemon-launch.json`, validates
`enabled == true` (bench modules used by the harness set this), builds
the command from the descriptor's `javaLauncher`, `jvmArgs`, `classpath`,
`mainClass`, and runs `ProcessBuilder` with `redirectError(PIPE)` +
`redirectOutput(PIPE)`.

**Stream handling:**
- **stdout** is the JSON-RPC channel; reader thread parses
  Content-Length frames into `JsonObject`s, dispatches notifications to
  scenario expectations, resolves response futures by `id` — mirrors
  `JsonRpcServerIntegrationTest`'s loop.
- **stderr** is ring-buffered (last 64KB). Dumped on failure only —
  green CI output stays quiet.
- **stdin** is fed from a `LinkedBlockingQueue<String>` by one writer
  thread per daemon.

**Timeouts:** per-scenario default 30s (cold may set 60s; soak 5min). On
expiry: dump stderr, `SIGTERM`, wait 5s, `SIGKILL`. Test fails with
"daemon hang" tag.

**Shutdown sequencing on success:** send `shutdown` request → await
response (asserts the drain happened) → send `exit` notification →
`Process.waitFor(10s)` and assert exit code 0. If the process doesn't
exit in 10s, `SIGTERM`+`SIGKILL` ladder and fail — that's a regression in
the shutdown plumbing.

**Session mode (opt-in):** one daemon JVM hosts a sequence of scenarios.
Used for soak-shaped tests ("100 renders should not recycle the sandbox
more than twice"). Session-mode scenarios share state intentionally;
failures abort the whole session and dump full stderr.

## 7. Reuse across desktop and Android

The harness is renderer-agnostic. The only target-specific bits are:

| Concern | Desktop | Android |
|---------|---------|---------|
| Descriptor | `:samples:desktop-daemon-bench:composePreviewDaemonStart` (planned by P0.6) | `:samples:android-daemon-bench:composePreviewDaemonStart` (exists) |
| Baselines | `daemon/harness/baselines/desktop/` | `daemon/harness/baselines/android/` |
| `PreviewInfo.id` shape | `BenchPreviews.kt#FooPreview` | `com.example.daemonbench.BenchPreviewsKt#FooPreview_…` |

Selection by Gradle property: `./gradlew :daemon:harness:test
-Ptarget=desktop|android` (default `desktop`). The harness resolves the
descriptor path, baseline directory, and per-target preview-ID aliases
from a `daemon/harness/scenarios.toml` map. Adding a third
renderer (e.g. iOS CMP) is "a new target row + a baseline directory."

## 8. CI integration

**Per-PR jobs:**
- `daemon-harness-desktop` (always-on): `:daemon:harness:test
  -Ptarget=desktop`. Fast — desktop daemon is sub-second cold; full v1
  catalogue ~60s warm.
- `daemon-harness-android` (always-on once stable; opt-in initially):
  `-Ptarget=android`. Slower — Robolectric + Android sandbox dominate;
  budget ~5min.

**Failure surfacing:**
- Pixel-diff: `actual.png`, `expected.png`, `diff.png` as workflow
  artefacts; PR comment includes a thumbnail of `diff.png`.
- Latency band: PR comment shows measured ms / baseline ms / computed
  band.
- Lifecycle: daemon stderr dumped verbatim; harness logs the full
  notification trace it received before the failure.

**Latency tolerance:** ±25% of the `baseline-latency.csv` median per
target. CI runners are noisier than the reference machine; the band
catches order-of-magnitude regressions without flapping on routine
variance.

**Baseline drift:** a separate **weekly** workflow re-runs both bench
tasks plus the harness's latency scenarios and posts deltas as a status
comment (opens an issue when delta > 50%). Long-horizon canary for the
daemon getting slower over multiple PRs none of which individually
breached the per-PR band.

**Existing Stream D cross-references:** D2.2 (pixel-diff gate) reduces
to "the harness's S1 must pass" — the harness *is* the pixel-diff CI
gate. D2.3 (1000-render soak) belongs in the harness as a session-mode
scenario; port it once the harness exists.

## 8a. The `FakeHost` test fixture

The harness ships a `FakeHost` that implements [`RenderHost`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/RenderHost.kt). It runs in the same JVM as the real daemon (it *is* the daemon's `RenderHost` for fake-mode runs), serves PNGs from a local fixture directory keyed by preview ID, and reads pre-cooked metadata from a small JSON manifest.

Lives under `daemon/harness/src/main/kotlin/.../FakeHost.kt` with fixtures in `daemon/harness/fixtures/<scenario>/`. Each scenario's fixture directory contains:

- `previews.json` — the same shape as a real `composePreviewDaemonStart` manifest, listing the previews this scenario expects to render.
- `<previewId>.png` — the PNG the fake "renders" when asked for that preview.
- Optional per-preview overrides: `<previewId>.delay-ms` to simulate a slow render, `<previewId>.error` to simulate `renderFailed`, `<previewId>.metrics.json` to override `renderFinished.metrics`.

### Why this matters

- **Decouples harness work from real-renderer work.** The harness can land its full v1 scenario catalogue without B-desktop.1.5 / B1.4 ever being merged. Once those land, the harness picks up real-renderer mode behind `-Pharness.host=real|fake` (default `real` when a working renderer exists for the target).
- **Deterministic failure-mode coverage.** S5 (`renderFailed` surfacing) becomes "configure the fake to throw on this preview"; no need to maintain an `error("boom")` composable in a sample module. Same for slow renders (latency-band tests), specific metric values (cost-model parity), recycle-trigger sequences (S9).
- **Permanent fixture, not throwaway scaffolding.** Unlike the agent's original "stub `DesktopHost`" suggestion, `FakeHost` doesn't get deleted once the real renderer ships. It stays as the way harness scenarios drive specific behaviours that would be impractical to coax out of a real renderer (e.g. "render took exactly 2.7 seconds and reported metrics X").
- **Renderer-agnostic by construction.** Same `FakeHost` serves desktop and Android scenarios — it never touches Skiko, Robolectric, or Compose. Strengthens the [DESIGN § 4](DESIGN.md#renderer-agnostic-surface) invariant: nothing in the protocol or the harness depends on which renderer produced the PNG.

### Wiring

The harness's launch path is parameterised:

- `-Pharness.host=fake` — harness spawns a daemon JVM whose entry point is a tiny `FakeDaemonMain` that wires `JsonRpcServer` → `FakeHost`. No `:daemon:android` or `:daemon:desktop` on the classpath at all.
- `-Pharness.host=real` — harness spawns the real `composePreviewDaemonStart` descriptor, same as a VS Code launch.

Default is `fake` until the matching real renderer's Stream B / B-desktop work has landed for the chosen `target`; flips to `real` once it has.

### What's *not* in `FakeHost`

It is **not** a substitute for the real-renderer integration tests (B1.5's `JsonRpcServerIntegrationTest`, B-desktop.1.5's equivalent). Those exercise the actual sandbox lifecycle, classloader behaviour, and Compose render path. `FakeHost` exercises everything *above* the host: protocol dispatch, lifecycle, scenario sequencing, image diff, file-edit primitives, latency recording, CI wiring. The two layers complement each other.

## 9. Phasing

Each rung independently shippable; each gated on the previous rung
working in CI for ~a week.

**v0 — single happy-path scenario, desktop only, against `FakeHost`.**
Module `:daemon:harness` exists. S1 only. `PixelDiff.kt` shipped;
one baseline PNG; one fixture directory under `daemon/harness/fixtures/s1/`.
Subprocess plumbing works end-to-end against a `FakeDaemonMain`. CI
workflow runs S1 on every PR. **Independent of B-desktop.1.5** thanks to
`FakeHost` (§ 8a) — proves the architecture without depending on the
real renderer wiring.

**v1 — full reactive scenario catalogue, desktop only, against `FakeHost`.**
S2 (drain), S3 (render-after-edit; for fake mode the "edit" maps to
swapping which fixture the host serves for that preview ID), S4
(visibility), S5 (renderFailed; configured via fixture
`<previewId>.error`), S7 (latency; **recorded only, not asserted** —
see § 10 Q4), S8 (cost-model parity; configured via fixture
`<previewId>.metrics.json`). File-edit primitive with auto-revert +
bytecode-visibility check. Per-scenario timeouts, stderr buffering,
baseline regeneration task. Latency CSV deltas surfaced as CI
artefacts; humans read trends, no test fails on perf.

**v1.5 — flip to real renderer once B-desktop.1.5 lands.** Same
scenarios, `-Pharness.host=real`. The same scenario classes run with
zero source change. Existing baselines re-captured against the real
renderer (tightens the visual contract); FakeHost stays available for
deterministic failure-mode coverage.

**v2 — Android target.** `-Ptarget=android` wired in. Android baselines
captured (real renderer mode immediately viable since B1.5 is already
shipped). Same scenarios run against `:samples:android-daemon-bench`.
New CI job `daemon-harness-android`. First time the renderer-agnostic
claim in [DESIGN § 4](DESIGN.md#renderer-agnostic-surface) is *enforced*
at the harness level.

**v3 — predictive prefetch + recycle + soak.** S6 (classpathDirty,
gated on B2.1; for fake mode, the harness sends a synthetic
`classpathDirty` notification directly), S9 (sandbox recycle, gated on
B2.5), S10 (predictive prefetch, gated on P2.5.2), session-mode
1000-render soak (replaces D2.3), weekly drift-report workflow. Every
daemon feature has an end-to-end harness scenario before un-flag review.

## 10. Decisions still open

None as of this writing. New questions surfaced during implementation
move here first; they migrate to § 11 once resolved.

## 11. Decisions made

- **Module location:** new top-level module `:daemon:harness`
  (plain `org.jetbrains.kotlin.jvm`, depends only on
  `:daemon:core`). Co-locating under `:daemon:desktop`'s
  integrationTest source set or extending
  `:samples:android-daemon-bench` were both ruled out because they
  couple the harness to a per-target module and pollute the classpath.
  The harness is conceptually a fourth client of the protocol
  (alongside VS Code, in-process unit tests, and the bench harness)
  and that role is clearest at the top level.
- **Pixel-diff defaults:** per-pixel ≤ 3 LSB (sum of channel deltas),
  aggregate ≤ 0.5% pixels exceeding, absolute cap ≤ 50 LSB. Adjustable
  per-scenario; tighten or loosen against real PRs once we have data.
- **Image baselines:** two-strategy split. **Fake-mode scenarios use
  deterministically-generated test patterns** (`TestPatterns.kt`
  → `build/daemon-harness/test-patterns/`) — solid colours, text
  boxes, gradients; same generator produces both fixture and baseline
  so nothing is checked in for v0+v1. **Real-mode scenarios** (v1.5+)
  capture actual Compose-rendered PNGs to in-repo
  `daemon/harness/baselines/<target>/<scenario>/<id>.png`. Git
  LFS stays an escape hatch only if real-mode on-disk volume becomes
  painful; not pre-emptively wired. Always disambiguated from the
  latency baselines in `baseline-latency.csv`.
- **Latency assertions: record-only, not gating.** v1 captures
  per-scenario latency, writes a delta-vs-baseline row to a CSV
  artefact, and surfaces trends; the harness does *not* fail a CI run
  on a latency miss. Reasoning: CI machine noise across hosts and
  cold-vs-warm states would flap perf gates without telling us
  anything actionable; we want the data more than we want a red
  build. A weekly drift-report workflow (v3) reads the artefacts and
  posts deltas exceeding 50% as an issue.
- **v0 before B-desktop.1.5: yes, via `FakeHost` (§ 8a).** Instead of
  a throwaway stub `DesktopHost`, the harness ships a permanent
  `FakeHost` that implements `RenderHost` and serves PNGs from local
  fixture directories with optional per-preview metadata overrides.
  Decouples harness work from real-renderer work entirely; stays
  useful afterwards as the way harness scenarios drive deterministic
  failure modes (slow render, render error, specific metrics).
  `-Pharness.host=fake|real` switches between the two; default flips
  to `real` per target once that target's real renderer wiring has
  landed.

