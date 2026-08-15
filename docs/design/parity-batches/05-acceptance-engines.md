# Batch 05 — both acceptance engines, and the publish path

**Issues:** [#3808](https://github.com/yschimke/compose-ai-tools/issues/3808) (offline engine),
[#3810](https://github.com/yschimke/compose-ai-tools/issues/3810) (browser engine),
[#3809](https://github.com/yschimke/compose-ai-tools/issues/3809) (publish).
**Depends on:** [04](04-acceptance-schema.md) (schema + fixtures — freeze it first, both sides build
against one definition), [03](03-element-selection.md) (element gates must not be enabled without a
selection path), [00](00-decisions.md) D1 and D3.
**Ships:** **yes.** Accepted and unaccepted scores reported separately, on a real catalog.

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §4 — the ten
invariants **and** the open-problems list; `format-compare.js`'s `scorePlanes` **in full** before
touching a line of it.

The three issues are one batch because the whole point is that the two engines agree bit for bit, and
"agree" is not testable one engine at a time. Publish rides along because an engine nobody's catalog
can feed is not finished.

---

## The document deliberately does not specify a pixel algorithm

Earlier revisions carried a numbered pipeline and **every version of it had a real defect** — a gate
placed before the data it reads existed, a resample that mixed what a previous step had separated, a
delta computed in the wrong direction. What §4 gives instead is ten invariants that are not
negotiable, plus an explicit list of what this batch has to **decide**:

1. the portable pixel path — which resampler, since `drawImage`'s filter is not reproducible
   off-browser;
2. whether mask pixels participate in `edgeMask`;
3. the masked pass's denominator;
4. what "accepted contribution" means, given it can legitimately go **negative**;
5. sub-pixel rounding;
6. the match metric shared by the candidate gate and the resolution test, and whether it is aggregate
   or per-pixel.

Record each decision in the design doc as it is made. A decision made in code and not written down is
how the previous three pipelines went wrong.

## Facts about the existing scorer that constrain all of it

`scorePlanes` is more particular than it looks; this was misread three times before the whole function
was read.

1. A **per-plane edge mask** — a pixel is an edge when its 4-neighbour luma gradient reaches
   `EDGE_GRADIENT_THRESHOLD = 12`.
2. Each directed pass starts from the difference at the **same coordinate**.
3. It widens to the `EDGE_SEARCH_RADIUS = 5` displaced search **only** when the source pixel is an edge
   **and** the same-coordinate difference already exceeds `LUMA_TOLERANCE = 16`.
4. A candidate target counts **only if it is itself an edge**.
5. Each displaced match is penalised by `√(ox² + oy²) × EDGE_POSITION_COST` (= 10).
6. The per-pixel charge is `max(0, best − LUMA_TOLERANCE) / (255 − LUMA_TOLERANCE)`, averaged over
   `width × height`, both directions averaged into `mismatch`.
7. The returned score is `max(0, min(100, (1 − mismatch) × 100))` — **a clamped percentage**, so a
   port that stops at step 6 publishes every number inverted.

`ssim` / `globalSsim` are still in the file and **have no callers** — see
[loose ends](00-decisions.md#loose-ends-not-blocking-file-or-fix-opportunistically).

## 5a — offline engine (#3808)

`design-parity` reads `known-differences.json`, applies the same semantics the browser does, and
reports `raw`, `accepted` and `unaccepted` separately.

**The raw finding is always preserved** — epic requirement, and the reason acceptance is not an ignore
rectangle. An acceptance never removes a difference from the report; it moves it into `accepted` while
`unaccepted` keeps everything else visible.

- **Separation precedes resampling.** The masked and unmasked regions of *both* inputs are split in
  their own pixel space, per acceptance, before any resample — never a pre-averaged composite.
  `drawImage` averages **signed** luma, so on a footprint straddling a mask edge an accepted
  difference can cancel an opposite unaccepted regression before scoring ever sees the pixel.
- **Only `valid` acceptances contribute a mask to the scoring union.** `resolved`, `invalidated` and
  `refused` suppress nothing. Keeping a `resolved` mask would remove its pixels as neighbourhood
  candidates for the pixels *around* it, which can hide a regression sitting next to the thing just
  fixed.
- **The union cannot be formed before the gates have run**, and the gates need canonical pixels — so
  the surviving-union split is a distinct, later stage, not the same pass.
- **Status precedence is strict**: `refused` → any non-`candidate-changed` invalidation → `resolved` →
  `candidate-changed` → `valid`. `resolved` is guarded on the candidate gate having actually **fired**,
  otherwise a tolerant metric marks an unchanged candidate resolved the moment it is authored.
- **Open question: an acceptance that accepts nothing.** If `accepted-candidate.png` already agrees
  with the reference inside the mask, the record encodes no actual difference. The candidate gate
  compares candidate against `accepted-candidate.png`, so it does not fire, and precedence falls
  through to `valid` — the mask joins the suppression union having never represented an accepted
  difference. Note the harm is **not** "a later regression is hidden": a regression inside the mask
  changes the candidate, fires the gate, and is reported. The real costs are that a *sub-tolerance*
  regression is suppressed in a region that never differed, and that the mask removes its pixels from
  the neighbourhood search of everything around it — the same objection the doc already makes to
  keeping a `resolved` mask. `mask-empty` is already refused for a comparable reason. Decide whether
  this earns an `acceptance-is-noop` refusal after the fingerprint gate; if it does, it needs a
  conformance fixture and a doc amendment, so decide it **before** the fixtures are frozen in batch 04.

**Cross-repo.** Sequence after the schema is frozen.

## 5b — browser engine (#3810)

Apply acceptances in `format-compare.js`, reporting `raw` / `accepted` / `unaccepted` separately,
**including status evaluation**.

**Status evaluation belongs here, not in batch 06.** The conformance fixtures this work must pass
carry a per-acceptance `statuses` map with a required fixed-candidate `resolved` case, so an engine
that defers resolution cannot satisfy its own contract.

**Per-comparison evaluation is not the whole job — the browser needs a catalog-wide walk too.**
An acceptance naming a removed or renamed preview, reference, component or variant is never scoped
into *any* focused comparison, so an engine that only evaluates inside `format-compare.js` leaves it
permanently absent from the browser result and the dashboard while `design-parity` reports
`orphaned-target` for the same record. That is the "invisible forever" failure the `orphaned-target`
rule exists to prevent, reintroduced by the *shape* of the evaluation rather than by the rule. Add
the host-level walk over the complete acceptance set in this batch.

**The raw number will move once, deliberately.** Any kernel that is not `drawImage` produces different
pixels. What invariant I10 buys is that the *geometry* stays one resample from source to score plane
at the candidate box's dimensions, so the number does not move a **second** time. The move is a
versioned rebaseline — see [D3](00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when).

**Element gates must not be enabled without the tag index**, and the index's bounds are render-pixel
(D1) — the transform into the canonical plane happens here, in the comparison.

**And "no index" must mean "no suppression", not "geometric suppression".** `ServeTagIndex.kt`'s KDoc
currently says an acceptance that finds no tag entry "degrades to no element gate, which is the safe
direction". That is only safe if the acceptance also stops suppressing: an **element-scoped**
acceptance whose gate cannot run and whose mask still joins the valid union has silently become a
plain ignore rectangle, and a tagged element that has disappeared or moved goes on being hidden —
precisely the failure the element gate was added to catch. Define the unavailable-index outcome as
**every element-scoped acceptance suppresses nothing**, pin it in both engines, and correct that KDoc
sentence in the same change.

## 5c — publish (#3809)

Carry `.design-parity/known-differences.json` and its artifact directories from the source repo
through `@design-parity/catalog-export` into the published catalog, so a serving host reads the same
acceptances the offline run does.

- **The artifacts are fetched, not just referenced.** Once a mask and an accepted-candidate PNG are
  published catalog assets they go through `ServeCatalogStore`'s ordinary staging, which applies
  `MAX_FETCH_BYTES` (25 MB per asset) — one reason the schema caps artifacts at 8 MiB, comfortably
  below it, so the two engines disagree nowhere near where a fetch would fail.
- **Path containment is checked at staging**, on a host that fetches third-party catalogs, so a
  traversal here is an escape from the artifact tree rather than a typo. The schema's portable-path
  grammar exists so the fetching host and the offline reader resolve the same file.
- **Publishing must not require a re-render — and routing acceptances through `catalog-export` does
  not deliver that.** `design-artifacts.yml` is `bundle pack → catalog-export → publish out/`, so any
  path that goes through it waits on a full render (8–29 min scoped, 31–38 min full), and adding a
  path to `scope-systems.sh` only *selects* that render, it does not bypass it. The issue index gets
  the property because §3 gives it a **separate one-file committer** — the doc says outright that the
  render workflow is "the wrong granularity for 'someone relabelled an issue'". An acceptance edit is
  the same granularity as an issue relabel. So this batch needs its own index-style publisher that
  commits just the acceptance paths onto the delivery branch, sharing the carry-forward behaviour
  batch 02 works out, or it must drop the no-rerender claim and say acceptances land on the next
  render. **Pick one explicitly** — the claim as written is currently unsupported.
- **Check `scripts/design-artifacts/scope-systems.sh` maps the new published path.** A wrong mapping
  silently strands a catalog on stale artifacts. It has a self-test (`test-scope-systems.sh`); change
  it only with that passing. Note also that catalog and export-driver merges regenerate the delivery
  branches automatically while **renderer/plugin/CLI changes do not** and need a manual
  `design-artifacts.yml` dispatch.

**Cross-repo.**

## Done when

- **Both engines pass the same batch-04 conformance fixtures, byte for byte on which mask survived.**
  A single aggregate status cannot express a mixed-validity case, so two engines can emit the same
  summary while disagreeing about the details — the per-acceptance `statuses` map is what makes the
  disagreement visible.
- **…and the fixtures pin the intermediate planes, not only the surviving mask.** Two engines can
  agree on which mask survived while differing in the tag projection, the resolved selector, the
  resampled planes or the boundary pixels — and then disagree on the next catalog. §4 requires three
  runners, not two: the JS suite consumes an already-built index so it never exercises the **Kotlin
  projector**, which must consume the same payload fixtures and produce the expected index; and the
  selector stage must be resolved both from the index *and* separately through **design-parity's own
  production resolver**, since that is where the offline verdict actually comes from. Pinning an
  artifact without running the code that produces it proves nothing about that code.
- The mask-edge case is pinned: an accepted difference adjacent to an opposite unaccepted regression
  is reported as both, not cancelled.
- A `resolved` acceptance's mask demonstrably does **not** suppress its neighbours.
- The rebaseline lands in its own change with regenerated baselines and a release note, touching no
  acceptance semantics.
- A real published catalog carries acceptances and the served comparison shows accepted vs unaccepted.

## Visual evidence

Mandatory — the comparison page gains score splits. Before/after through the harness, both themes.
