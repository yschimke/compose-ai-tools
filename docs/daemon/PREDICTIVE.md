# Preview daemon — predictive prefetch

> **Status:** design proposal. Not in the v1 protocol. Layered on top of
> [PROTOCOL.md](PROTOCOL.md) v1; concrete IPC additions ship as v1.x or v2 deltas
> only when one of the phases below gets implemented. Cross-referenced from
> [DESIGN.md § 8 Tier 4](DESIGN.md#tier-4--is-the-user-looking-at-this).

## Why

The v1 daemon is reactive: a render only starts after `setVisible` / `setFocus`
arrives. Per [DESIGN.md § 13](DESIGN.md#13-latency-budget) the per-preview floor
is **~1.1s** of pure render time on the bench workload. That floor is structural
— shaving Gradle/fork/init won't drop it. The remaining lever is to start the
render *before* the user looks, so that by the time focus lands the PNG is
already on disk.

The webview has signals the daemon doesn't: dropdowns opening, scroll velocity,
file-explorer clicks, save events on a file the user is currently scoped to.
Each is a probability distribution over "what will the user focus next?". If the
distribution is sharp enough and the cost of being wrong is bounded, speculative
rendering pays for itself.

This doc is the policy proposal. It is deliberately optional in three layers
(see § 7) so we can land the smallest valuable slice first and stop there if the
data doesn't justify going further.

## 1. Signals catalogue

| Signal | Producer | Confidence | Horizon | Cost-if-wrong |
|--------|----------|------------|---------|---------------|
| **Scroll velocity → next-page IDs** | webview | high (geometry is deterministic; only "user reverses scroll" defeats it) | 200–800ms | one wasted render per card the user never reaches |
| **Filter dropdown opened** (source-file / group / label) | webview | medium (user might cancel; might pick a different value) | 1–5s while menu is open | one wasted render per option highlighted; queue thrash if many options |
| **Filter dropdown highlight / hover on item** | webview | high (active intent narrowed to one option) | 100–500ms | one wasted render |
| **File explorer click on preview-bearing `.kt`** | extension | high (file scope is about to change) | 100–300ms | wasted render of the new scope's previews if user clicks elsewhere |
| **Save event on the currently-scoped file** | extension | very high (user is editing X and looking at X's previews) | 0ms — fire as soon as kotlinc completes | none — these would be rendered reactively anyway; the win is queue ordering, not extra speculation |
| **Save event on a non-scoped file** | extension | low (user might not switch back) | seconds | wasted render; potentially many if save-bursts hit unrelated files |
| **Recently-focused preview history** | webview | low–medium | session | mild — likely useful as tiebreaker, not as primary signal |
| **Hover dwell on a card not yet focused** | webview | medium | 200–800ms | one wasted render |
| **CodeLens / hover on a `@Preview` declaration in editor** | extension | medium | seconds | one wasted render of that preview |

The strongest two are **scroll velocity** and **filter-dropdown highlight**:
both are cheap to measure, sharp probability distributions, and the user
already-implicitly-asked-for-this. The weakest are dwell-hover and recently-focused
history; treat those as v2 territory.

What this catalogue is *not*: a list of things to wire up. It's the menu —
§ 7 picks one item at a time off it.

## 2. Render queue model

Today the queue is single-priority FIFO with focus-first dedup. Replace with a
multi-tier queue, pull order strictly by tier:

| Tier | Source | Pull priority | Pre-emption |
|------|--------|---------------|-------------|
| `reactive-focus` | `setFocus` | 1 (highest) | next-pull only |
| `reactive-visible` | `setVisible` ∖ `setFocus` | 2 | next-pull only |
| `speculative-high` | scroll-velocity prefetch, filter-dropdown highlight | 3 | next-pull only; dropped on invalidation (§ 5) |
| `speculative-low` | dwell-hover, file-explorer clicks, history | 4 | next-pull only; dropped aggressively |
| `background` | stale-but-not-visible (existing behaviour) | 5 | next-pull only |

**Pre-emption rule.** Per [DESIGN.md § 9](DESIGN.md#9-sandbox-lifecycle), the
"no mid-render cancellation" decision stands — Robolectric mid-render
cancellation is a documented sandbox-leak source. Therefore an in-flight render
**always completes** regardless of what arrived in the queue while it ran.
Pre-emption only happens at queue-pull time: when the render loop next asks
"what's next?", it picks the highest tier present, dropping any
speculative-tier entries whose invalidation conditions (§ 5) have fired since
they were queued.

**Dedup rule.** A preview ID present in tier N is removed from any tier > N
when the higher-tier entry is added. Speculative renders that "graduate" to
reactive (the user actually focused them) don't re-render — the cached PNG is
served and the entry is just dequeued.

**Single-render-thread invariant** is unchanged. Concurrency on the sandbox
side is not introduced by this proposal.

## 3. IPC additions

Three options weighed:

**Option A — new `setPredicted` notification.**
```ts
{
  ids: string[];
  confidence: "high" | "low";
  reason?: "scrollAhead" | "filterCandidate" | "fileClick" | "hoverDwell" | "history";
}
```
Clean separation. Daemon's queue model maps directly onto tiers. Confidence
field is enum, not numeric — keeps the policy in the daemon.

**Option B — extend `setVisible` with a `predicted` field.** Fewer message
types. Couples "what the user can see" with "what we think the user will see"
— two semantically different things sharing one wire format. Fragile when one
changes faster than the other.

**Option C — let the client send tier hints in `renderNow`.** Daemon stays
ignorant of prediction; just does what it's told. Pushes all the prediction
state into the webview/extension. Loses the ability for the daemon to expose
prediction-aware metrics back to the client (§ 4 telemetry).

**Recommendation: Option A.** Smallest surface area, clean separation from
the locked v1 reactive contract, makes the "speculative" tiers visible in
the daemon's logs and metrics. Additive notification → **no `protocolVersion`
bump** under the rules in [PROTOCOL.md § 7](PROTOCOL.md#7-versioning).

When implemented, a daemon advertising `capabilities.prediction = true` in
the `initialize` response opts in; clients without the capability ignore it
(and the daemon falls back to reactive-only). Old clients talking to a new
daemon are unaffected because the notification is client → daemon only.

`renderFinished.metrics` gains an optional `speculation` field:
```ts
{ tier: "reactive-focus" | "reactive-visible" | "speculative-high" | ... ,
  utilized?: boolean }    // set later when we know if user looked
```
`utilized` is filled in by a follow-up `renderUtilized({ id, tier })`
notification when the user eventually focuses (or `setVisible`s) the
preview within the configured horizon, or by a `renderExpired` after the
horizon passes. Both are daemon-side state derived from the existing
`setFocus` / `setVisible` stream — no new client → daemon traffic.

## 4. Cost model & accuracy budget

Cost per speculative render = **~1.1s CPU** (the bench floor) + heap pressure
that brings the sandbox closer to recycle (DESIGN § 9 thresholds: heap drift
30% over rolling 50, render-time drift 50%, render count 1000).

**Hit rate target — opening proposal:** ≥ **50%** of speculative renders
should be `utilized` within their tier-specific horizon. If sustained hit
rate drops below that, the daemon backs off (see backpressure below).

Horizons (configurable; these are the proposed defaults):

| Tier | Horizon | Rationale |
|------|---------|-----------|
| `speculative-high` (scroll-ahead) | 5s | scroll bursts settle within seconds |
| `speculative-high` (filter-candidate) | 3s | dropdown is open or just closed |
| `speculative-low` (dwell, file-click, history) | 10s | weaker signals, longer tail |

**Concurrency budget.** At any moment, at most:
- 1 in-flight render (unchanged — single render thread).
- **N queued speculative renders.** Default `daemon.maxQueuedSpeculative = 4`.
  Beyond this, new speculative requests evict the oldest same-tier entry
  (LRU within tier). Speculative entries never evict reactive entries.
- This caps worst case: even if the user scrolls past 50 cards instantly,
  only 4 speculative renders queue up; the rest are dropped on the floor and
  re-requested if/when the user actually settles.

**Backpressure.** Rolling-window hit rate (per tier, per session) drives a
multiplier on the queue depth:
- ≥ 70% → allow `maxQueuedSpeculative` (full speed).
- 40–70% → halve the budget.
- < 40% → disable that tier for the rest of the session, log it. User can
  re-enable via setting.

**Telemetry.** `renderFinished.metrics.speculation = { tier, utilized }`
plus a periodic `predictionStats` notification (rolled-up per-tier hit rate
over the last N renders). Stream D bench harness consumes both. CI dashboard
plots hit rate over time per tier.

## 5. Cancellation & invalidation

A queued-but-not-yet-started speculative render is dropped at queue-pull
time when any of:

- **Focus moved to a preview not in the speculation window.** The user
  picked a different card than we predicted; predictions for the old window
  are dead. Drop them and let the new reactive request win.
- **Filter selection changed away.** The dropdown is what produced the
  predictions; if the user picked a different option, the prediction set is
  invalid. Drop all `speculative-high` entries tagged `filterCandidate`
  whose `id` isn't in the new filter result.
- **Scroll predicted-viewport moved past.** If we predicted the next page
  based on velocity V at time T, and the user has now scrolled past those
  IDs without pausing, the prediction was wrong. Drop them.
- **Source file changed since queueing.** If `fileChanged` arrived for the
  source file of a queued speculative render, the cached prediction is
  stale; drop it. (The reactive path will re-queue the render at its
  appropriate tier when the user actually looks.)

An **in-flight** speculative render is **never cancelled.** It runs to
completion, the PNG is cached, the result emits `renderFinished` with
`speculation.utilized = false` initially. If the user ends up looking at
the preview within the horizon, the cached PNG is served immediately and a
delayed `renderUtilized` notification flips the bit. This costs us nothing
beyond the wasted CPU we already spent — and gains us a "free" cached PNG
that might still be useful later in the session.

Reasoning: per DESIGN § 9 mid-render cancellation is unsafe. Aborting an
in-flight render to start a different one would risk leaving the sandbox
in a half-disposed state — the exact failure shape the recycle policy is
trying to avoid. Cheaper to let the wrong render finish.

## 6. Risks

**Battery / CPU on dev laptops.** A prefetcher with 50% hit rate doubles
CPU draw with at best a 2× perceived-speedup win, and on battery the user
notices the fan before they notice the speed. Mitigations:
- `daemon.predictive.enabled = false` by default. Opt-in setting.
- Auto-disable when on battery (extension queries
  `vscode.env.appHost`-derived heuristics; fallback to manual override).
- Backpressure (§ 4) shuts off poorly-performing tiers automatically.

**Misleading the user with errors.** If preview X renders speculatively and
fails, do we show the error before the user has focused X? **No.**
`renderFailed` for a speculative render must be **suppressed in the UI**
until the user actually focuses X — at which point the cached failure is
surfaced. Otherwise the panel decorates cards the user wasn't even looking
at with red badges, training them to ignore failure indicators.
- Daemon emits `renderFailed` with `speculation.tier` set. Client routes:
  if the ID is currently in `setVisible`, surface immediately; if not,
  buffer and surface when next visible.
- Caveat: a true "this preview is broken right now" error and a
  "speculative render that failed because the kotlinc output was momentarily
  inconsistent" look identical at the wire. The client must accept that
  some buffered failures are stale and re-issue rendering when focused.

**Heap pressure → premature recycle.** Speculative renders consume the
same heap budget as reactive ones. Tracked classes (per DESIGN § 10) climb
faster, recycle thresholds trip more often. Mitigations:
- Speculative budget (§ 4) bounds the worst-case render rate.
- DESIGN § 9's recycle thresholds are not changed — instead, the recycle
  policy treats a recycle triggered during a speculative-heavy session as
  evidence the budget should shrink. Concretely:
  `daemon.maxQueuedSpeculative` halves for the rest of the session after
  the first recycle attributable to speculative load (i.e., where >50% of
  recent renders were speculative).
- Soak gate (DESIGN § 15) re-runs with prediction enabled before un-flagging
  the feature.

**Filter dropdown opens / closes rapidly.** A user clicking through a
dropdown, hovering each option in turn, generates a burst of
`filterCandidate` predictions. Without debounce we'd queue a render for
every option highlighted in 100ms. Mitigations:
- Webview debounces dropdown-highlight predictions at **150ms**: only emit
  `setPredicted` when the highlighted option has been steady for that long.
- Daemon coalesces back-to-back `setPredicted` calls within a 100ms window
  — last-wins for the same `reason`.

## 6a. UX response — predicted vs measured cost model

Independent of *when* a render is queued, the panel needs a policy for
*how* a pending render is surfaced. The pathological cases:

- **Spinner thrash on cheap renders.** A 50ms render that flashes a
  spinner before swapping the image is worse than no indicator at all
  — the spinner appears, the eye registers it, then it's gone. Reads
  as "something glitched."
- **No feedback on slow renders.** A 3s render with no indicator reads
  as "panel is broken" or "the click didn't register."

The threshold between these two failure modes isn't fixed. It depends on
the specific preview (an animation GIF is slow by design; a static
preview should be near-instant), and it drifts with sandbox warmth and
machine load.

### Two inputs

- **Predicted cost** — the existing `Capture.cost` field on every
  preview manifest entry (see [`PreviewData.kt`](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/PreviewData.kt#L102),
  cost catalogue: STATIC=1, SCROLL_END=3, SCROLL_LONG=20, SCROLL_GIF=40,
  ANIMATION=50). Available before any render — the manifest already
  carries it. Multiplied by a per-machine baseline (median wall-time of
  a STATIC=1 render, learned over the session) gives a reasonable
  predicted ms.
- **Measured wall time** — `renderFinished.metrics.tookMs` per preview
  ID, smoothed over the last N renders for that ID (small N — 3–5 —
  so the model adapts when sandbox state changes). Falls back to the
  predicted-cost model when no measurement exists yet.

The "predicted cost × baseline" gives us a first guess on cold previews;
once we have any measurement for a preview ID, that wins. Cost catalogue
mismatches surface as predicted/measured drift on the existing dev
observability channel (PREDICTIVE.md § 9 telemetry).

### Three indicator tiers

| Estimated ms | Indicator | Rationale |
|--------------|-----------|-----------|
| **< 150ms**  | None — swap when ready | Below the threshold the eye registers as "instant"; spinner would flash and disappear before the user reads it. |
| **150ms–1s** | Subtle — faded card / shimmer overlay, no spinner | Enough feedback that the user sees something is happening; not so much that fast renders feel slow. |
| **> 1s**     | Explicit — spinner + label ("rendering…") | User needs to know the click registered and the wait is normal, not broken. |

The thresholds are starting values, not gospel — they're tunable from
the same telemetry channel as everything else. A user staring at a
panel of GIFs all day might want the > 1s threshold pushed up so the
spinner only fires for genuinely slow renders.

### Loop

- Webview reads `Capture.cost` from the manifest at panel-load time.
- On `renderStarted`, webview decides the indicator tier from
  `cost × baseline-ms-per-cost-unit`.
- On `renderFinished`, webview swaps the image (regardless of indicator
  state) and updates the rolling per-ID measurement; baseline-ms-per-cost
  is recomputed from the median of recent STATIC=1 renders.
- Initial baseline (no measurements yet): use the bench's measured
  per-preview floor (~1100ms per render, see baseline-latency.csv). The
  daemon path's measured baseline replaces this within the first
  handful of renders.

### Why this isn't its own protocol message

All of the inputs already exist on PROTOCOL.md v1: `Capture.cost` in
the manifest, `tookMs` on `renderFinished`. The cost-model policy is
purely client-side; no daemon-side state. If the daemon ever wants to
weigh in (e.g. "I think this render will be slow because the sandbox
just recycled"), that's an additive `renderStarted.metrics.estimatedMs`
field — not a new message.

### Race with predictive prefetch

When v1.1 lands (scroll-ahead speculation, § 7), the cost-model
threshold also feeds the speculation budget heuristic: speculatively
pre-warming a STATIC=1 preview is cheap enough that high-budget makes
sense; speculatively pre-warming an ANIMATION=50 preview probably
isn't worth the heap pressure on the warm sandbox unless the user is
genuinely about to focus it. Concretely: cap speculative renders at
`cost ≤ HEAVY_COST_THRESHOLD` (existing constant from PreviewData.kt,
currently 5) until v1.2's backpressure has data to override.

## 7. Phasing — what lands when

Three phases. Each is independently shippable; each is gated on the
previous phase's hit-rate data.

### v1.1 — scroll-ahead prefetch + observability (one signal, smallest action)

- Webview computes scroll velocity + viewport size, projects the next
  page of card IDs, sends them as a `speculative-high` prediction.
- Daemon adds the speculative tier (§ 2) and `setPredicted` notification
  (§ 3).
- **Telemetry is in scope from v1.1** (resolves § 9 hit-rate +
  persistence decisions):
  - `renderFinished.metrics.speculation = { tier, utilized? }` populated
    on every render.
  - `renderUtilized({ id, tier })` / `renderExpired({ id, tier })`
    notifications flip the `utilized` bit when the user focuses the
    speculatively-rendered preview within its horizon (or the horizon
    expires).
  - Periodic `predictionStats` notification (rolled-up per-tier hit rate
    over the last N renders).
  - In-memory rolling stats only (`InMemoryRingSink`, the default). A
    `MetricsSink` interface lives alongside, so later versions can plug
    in `JsonlFileSink` / `OpenTelemetrySink` / `PrometheusSink` without
    touching the emit path. Compose-ai-tools developers observing hit
    rates use the daemon log channel + `predictionStats` notifications
    in v1.1; persistent disk telemetry is opt-in and ships when needed.
- No backpressure yet — fixed queue cap of 4. The point of v1.1 is
  *measure*, not auto-tune; backpressure is v1.2.
- Goal: prove the architecture, expose hit rates to compose-ai-tools
  developers, and gather a session of real data before deciding v1.2.

**Why this first?** Scroll is the highest-bandwidth interaction in the
panel. Even a modest hit rate translates directly to "the next card the
user pages to is already there." And without telemetry shipping
alongside the feature, there's no honest way to decide whether v1.2 /
v2 are worth building.

### v1.2 — filter-dropdown speculation + automated backpressure

- Adds `filterCandidate` reason: when a filter dropdown opens, the
  webview computes the candidate set per option and pre-warms the most
  likely candidate (or, with budget, the top K candidates).
- Introduces backpressure (§ 4): tiers with sustained < 40% hit rate
  auto-disable for the session. Now that v1.1 has been gathering data,
  the thresholds are anchored to observed numbers, not guesses.
- Adds the suppress-failure-until-visible logic (§ 6).

### v2 — full multi-signal predictive engine

- Adds dwell-hover, file-explorer-click, recently-focused-history.
- All five tier weights become tunable from telemetry.
- Per-user / per-project hit-rate persistence (`.compose-preview-history/predictive-stats.json`).
- Auto-disable on battery; opt-out per signal.
- This is the version where § 6's risks need real validation, not just
  mitigation by paper. Soak gate re-run is mandatory before un-flagging.

The recommendation is to land **v1.1 only** behind
`daemon.predictive.enabled = false`, collect a week's hit-rate data on
real use, and decide v1.2 based on whether scroll-ahead actually helps.
Don't pre-commit to v1.2 / v2 until v1.1 has data.

## 8. Decisions still open

None as of this writing. New questions surfaced during implementation
move here first; they migrate to § 9 once resolved.

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
  next preview — which would surface as a pixel-diff CI failure with no
  obvious memory leak to point at. The alternative ("let speculative
  renders complete; the PNG is cached and might still be useful later")
  is free and structurally clean. Revisit only if v1.2 telemetry shows
  speculative-render waste is the dominant CPU cost in real sessions.

  **Enforcement (so accidental cancellation can't sneak in):**
  - Render thread does not poll `Thread.interrupted()` and the daemon's
    own code never calls `interrupt()` on it.
  - Shutdown is a poison-pill on `DaemonHost`'s queue, not a thread
    abort; the in-flight render finishes before the sandbox tears down
    (B1.3's `RenderRequest.Shutdown` already follows this shape).
  - `JsonRpcServer.shutdown` (B1.5) drains the in-flight queue and
    blocks the response until drain completes — per PROTOCOL.md § 3.
  - JVM SIGTERM handler waits for in-flight drain before exit; SIGKILL
    is the only way to force termination mid-render (nothing we can
    defend against, but it leaks the sandbox classloader, not the JVM).
  - Regression test: submit a render, immediately invoke shutdown,
    assert the render still completes and the result is observable. See
    TODO.md `B1.5-followup` for the test wiring.
- **Hit-rate target (50%) accepted as a starting threshold, but
  observability is non-negotiable.** v1.1 ships `predictionStats`,
  `renderUtilized`/`renderExpired`, and a `MetricsSink` API (see below)
  so compose-ai-tools developers can observe what's working and tune
  thresholds against real data. Backpressure auto-tuning waits for v1.2
  (anchored to observed numbers). See § 4 and § 7.
- **Default state of `daemon.predictive.enabled`: off initially, on
  once proven.** v1.1 ships off-by-default to keep the battery / CPU
  footprint opt-in while we collect data. Once metrics show the feature
  behaves well across a representative sample of real-use sessions, the
  default flips to on. Concrete bar for the flip is captured later
  (likely tied to the soak gate and a "≥ 60% median utilisation across
  N sessions" rule), but the actual number waits for v1.1's data.
- **Speculative-render failures are buffered until the preview becomes
  visible.** A speculative render that fails does *not* surface a badge
  on a card the user isn't looking at. The cached failure waits in the
  client; if the underlying file changes before the user gets there, the
  reactive path re-renders and the failure is replaced — letting the
  user fix the issue without ever seeing a stale red badge. Daemon emits
  `renderFailed` with `speculation.tier` set; client routes per § 6.
- **Telemetry: in-memory by default, behind a pluggable `MetricsSink`
  API.** v1.1 keeps `predictionStats` rollups in a per-session
  in-memory ring buffer (last N renders, surfaced via the existing
  `predictionStats` notification and the daemon log channel). A
  `MetricsSink` interface lets later versions add concrete sinks —
  e.g. a `JsonlFileSink` writing to
  `.compose-preview-history/predictive-stats.jsonl`, an
  `OpenTelemetrySink`, or a `PrometheusSink` — without rewriting the
  emit path. The default registration is `InMemoryRingSink` only; other
  sinks are opt-in via the experimental DSL. No persistence in v1.1; no
  PII concerns until a non-default sink is wired.
