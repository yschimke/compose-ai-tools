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

**Cross-repo.** Sequence after the schema is frozen.

## 5b — browser engine (#3810)

Apply acceptances in `format-compare.js`, reporting `raw` / `accepted` / `unaccepted` separately,
**including status evaluation**.

**Status evaluation belongs here, not in batch 06.** The conformance fixtures this work must pass
carry a per-acceptance `statuses` map with a required fixed-candidate `resolved` case, so an engine
that defers resolution cannot satisfy its own contract.

**The raw number will move once, deliberately.** Any kernel that is not `drawImage` produces different
pixels. What invariant I10 buys is that the *geometry* stays one resample from source to score plane
at the candidate box's dimensions, so the number does not move a **second** time. The move is a
versioned rebaseline — see [D3](00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when).

**Element gates must not be enabled without the tag index**, and the index's bounds are render-pixel
(D1) — the transform into the canonical plane happens here, in the comparison.

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
- **Publishing must not require a re-render** — the same property `parity/issues.json` relies on.
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
- The mask-edge case is pinned: an accepted difference adjacent to an opposite unaccepted regression
  is reported as both, not cancelled.
- A `resolved` acceptance's mask demonstrably does **not** suppress its neighbours.
- The rebaseline lands in its own change with regenerated baselines and a release note, touching no
  acceptance semantics.
- A real published catalog carries acceptances and the served comparison shows accepted vs unaccepted.

## Visual evidence

Mandatory — the comparison page gains score splits. Before/after through the harness, both themes.
