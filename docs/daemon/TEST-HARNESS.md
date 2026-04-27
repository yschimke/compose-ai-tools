# Preview daemon — end-to-end test harness

> **Status:** design proposal. No code yet. Ladder ships in v0..v3 (§ 9). Not
> in scope for any current Stream B/C task; lives under Stream D alongside
> the bench harnesses (P0.1 / P0.6 / D2.1 / D2.2 / D2.3).

This document specifies a **VS-Code-shaped driver** that exercises a real
daemon JVM over JSON-RPC the way the editor will, but without any editor in
the loop. It is the authoritative end-to-end test bed for every daemon
feature — protocol changes, render correctness, lifecycle invariants,
predictive prefetch, the cost model, and the sandbox-recycle dance — before
that feature reaches a real editor session.

Desktop-first. `:renderer-desktop-daemon` (hello-world skeleton today;
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

- **Not a replacement for `:renderer-daemon-core`'s in-process unit tests.**
  [`JsonRpcServerIntegrationTest`](../../renderer-daemon-core/src/test/kotlin/ee/schimke/composeai/daemon/JsonRpcServerIntegrationTest.kt)
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

A **new module `:tools:daemon-harness`** under a fresh `tools/` directory
applying `org.jetbrains.kotlin.jvm` only (no Android, no Compose plugins).
JUnit test source set (`./gradlew :tools:daemon-harness:test`) runs the
scenario catalogue against a fresh daemon per scenario; a `main()` entry
point (`./gradlew :tools:daemon-harness:run`) drives a single scenario
interactively for debugging.

Depends on `:renderer-daemon-core` for the
[`Messages.kt`](../../renderer-daemon-core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt)
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

- **`:renderer-desktop-daemon` integrationTest source set.** Co-located
  with code under test, but cross-target reuse is awkward (same scenarios
  must run against `:renderer-android-daemon`); per-target plugin classpath
  pollution; hides that the harness is *playing the editor*, not testing
  daemon internals.
- **Extend `:samples:android-daemon-bench`.** Reuses bench subprocess
  plumbing, but muddles bench (perf numbers) with harness (correctness),
  and bench is Android-specific.
- **New `:tools:daemon-harness`.** Renderer-agnostic by construction; only
  depends on `:renderer-daemon-core`; doubles as a reference client for
  anyone porting the daemon to a new editor; CLI mode for debugging
  without VS Code. Cost: another module.

The harness is conceptually a **fourth client of the protocol** alongside
VS Code, the in-process unit tests, and the bench harness — encoding that
role as a top-level module makes the boundary clear.

### Module classpath wiring

`implementation(project(":renderer-daemon-core"))`,
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
`PixelDiff.kt` under `:tools:daemon-harness/src/main/kotlin/...` (no
third-party dependency) that the D2.2 pixel-diff CI gate also consumes.
Consolidation is a side-effect of this work.

### Baselines

Live in-repo at `tools/daemon-harness/baselines/<target>/<scenario>/<id>.png`.
Desktop set is small (~10 PNGs at ~100KB) and updates are rare. If volume
becomes painful (Android adds device qualifiers), git LFS is the escape
hatch — but **not** a remote artefact store, because "fresh checkout
works" is load-bearing for the contributor experience.

### Regenerating baselines

`:tools:daemon-harness:regenerateBaselines` re-runs every scenario in
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
crashed test leaves a `tools/daemon-harness/build/PENDING_REVERTS.json`
marker that the next run reverts or fails fast on. `git stash` would
risk eating unrelated developer work; never invoke it.

## 6. Subprocess management

**Spawn:** `:tools:daemon-harness:test` `dependsOn` the target's
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
| Baselines | `tools/daemon-harness/baselines/desktop/` | `tools/daemon-harness/baselines/android/` |
| `PreviewInfo.id` shape | `BenchPreviews.kt#FooPreview` | `com.example.daemonbench.BenchPreviewsKt#FooPreview_…` |

Selection by Gradle property: `./gradlew :tools:daemon-harness:test
-Ptarget=desktop|android` (default `desktop`). The harness resolves the
descriptor path, baseline directory, and per-target preview-ID aliases
from a `tools/daemon-harness/scenarios.toml` map. Adding a third
renderer (e.g. iOS CMP) is "a new target row + a baseline directory."

## 8. CI integration

**Per-PR jobs:**
- `daemon-harness-desktop` (always-on): `:tools:daemon-harness:test
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

## 9. Phasing

Each rung independently shippable; each gated on the previous rung
working in CI for ~a week.

**v0 — single happy-path scenario, desktop only.** Module
`:tools:daemon-harness` exists. S1 only. `PixelDiff.kt` shipped; one
baseline PNG. Subprocess plumbing works end-to-end. CI workflow runs S1
on every PR. **May ship before B-desktop.1.5** against a stub
`DesktopHost` returning a fixture PNG — proves the architecture early
while Stream B-desktop is still wiring `DaemonMain` →`JsonRpcServer` →
`DesktopHost`. Decision in § 10 Q5.

**v1 — full reactive scenario catalogue, desktop only.** S2 (drain),
S3 (render-after-edit, gated on B2.2), S4 (visibility), S5
(renderFailed), S7 (latency), S8 (cost-model parity). File-edit
primitive with auto-revert + bytecode-visibility check. Per-scenario
timeouts, stderr buffering, baseline regeneration task. Latency
assertions against desktop rows in `baseline-latency.csv`.

**v2 — Android target.** `-Ptarget=android` wired in. Android baselines
captured. Same scenarios run against `:samples:android-daemon-bench`.
New CI job `daemon-harness-android`. First time the renderer-agnostic
claim in [DESIGN § 4](DESIGN.md#renderer-agnostic-surface) is *enforced*
at the harness level.

**v3 — predictive prefetch + recycle + soak.** S6 (classpathDirty,
gated on B2.1), S9 (sandbox recycle, gated on B2.5), S10 (predictive
prefetch, gated on P2.5.2), session-mode 1000-render soak (replaces
D2.3), weekly drift-report workflow. Every daemon feature has an
end-to-end harness scenario before un-flag review.

## 10. Decisions required

These need a human answer before any of v0 ships.

1. **Module location.** Recommend `:tools:daemon-harness` (new
   top-level `tools/` directory). Acceptable, or prefer co-locating
   under an existing module (e.g. extending `:samples:android-daemon-bench`
   or adding an `integrationTest` source set on
   `:renderer-desktop-daemon`)?
2. **Pixel-diff defaults.** Recommend per-pixel ≤ 3 LSB (sum of channel
   deltas), aggregate ≤ 0.5% pixels exceeding, absolute cap ≤ 50 LSB.
   Aligned with what feels right, or do we want a stricter / looser
   starting point?
3. **Baselines in repo vs LFS vs separate artefact store.** Recommend
   in-repo until it hurts (desktop set is small). Acceptable, or pre-emptively
   wire LFS / a separate fixtures repo?
4. **Latency-assertion tolerance.** Recommend ±25% of the
   `baseline-latency.csv` median. Tighter (catches regressions earlier
   but flaps on noisy CI) or looser (less flap, catches less)?
5. **v0 before B-desktop.1.5.** Recommend yes — ship v0 against a stub
   `DesktopHost` that returns a fixture PNG, so the harness architecture
   is proven before the real renderer wiring lands. Trade-off: ~50 LOC
   of throwaway scaffolding (the stub). Worth it, or wait for
   B-desktop.1.5?

