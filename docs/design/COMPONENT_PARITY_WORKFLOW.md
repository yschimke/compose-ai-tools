# Component parity: issues and scoped acceptance

> **Status: proposal.** Investigation + phased plan for
> [#3680](https://github.com/yschimke/compose-ai-tools/issues/3680). No code yet. This document
> settles the contracts (locator, issue index, known-difference schema) and the delivery order; each
> phase below is meant to become its own PR against an existing surface, not a new subsystem.

The preview server can already tell you that a component's render and its design reference disagree.
It cannot tell you whether anyone *knows*. Every comparison is scored from scratch on every page
load, so a difference someone triaged, filed and deliberately parked looks exactly like a regression
that landed this morning. This is what turns parity from a workflow into a one-way report: there is
nowhere to write down "we know about the glyph colour, issue #40, don't tell me again — but do tell
me if the glyph disappears."

The worked example the epic names is
[m3-catalog#40](https://github.com/yschimke/m3-catalog/issues/40): a tonal `IconButton` whose
container matches the Material 3 kit exactly and whose glyph uses a different colour token. We want
to accept **that glyph's colour, on that variant, against that reference** — and keep detecting a
changed container, a shifted geometry, a missing glyph, or the same component's other variants.

---

## 1. What exists today

Everything below is already shipped; the plan is mostly *joining* it rather than building new
machinery.

| Surface | Where | What it gives us |
| --- | --- | --- |
| Prefilled issue report | [`ServeIssueReport.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeIssueReport.kt) | GET-form `issues/new` link carrying system, preview id, source, catalog provenance, tool version, viewer URL, embedded render |
| Design references | [`ServeDesignReferences.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeDesignReferences.kt) | `compose-preview-references/v1` — one reference per exact preview id, canonical PNG, **optional `sha256`**, provider/revision provenance |
| Focused comparison | `ServeWeb.referenceComparisonPage`, route `GET /{system}/compare/{previewId}?reference=<id>` | Reference / Diff / Actual triptych, opacity overlay, annotation layers |
| Annotations | [`ServeAnnotations.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeAnnotations.kt), [`ServeDesignAnnotations.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeDesignAnnotations.kt) | Numbered boxes with `bounds` in each panel's own pixel space, `role`, structured `detail`. Reference side authored by the producer; render side derivable from the `compose/semantics` tree |
| Scoring | [`format-compare.js`](../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js) | `scorePlanes` — a **bidirectional edge-tolerant nearest-neighbour search** over content-box-normalised gray planes — plus a magenta delta map, **entirely in the visitor's browser** |
| Parity dashboard | [`ServeParityDashboard.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeParityDashboard.kt), route `/{system}/parity(.json)` | Coverage (live), drift correlation, merged activity feed, mapping gaps |
| Published snapshot precedent | [`ServeParityActivity.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeParityActivity.kt) + [`parity-activity.mjs`](../../scripts/design-artifacts/parity-activity.mjs) | The exact pattern the issue index should copy — see §3 |
| Catalog refresh | [`ServeCatalogRefresher.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeCatalogRefresher.kt) | Polls each `design-artifacts/<system>` branch head and re-fetches on **any** new commit |

Four findings from reading this that shape everything downstream:

**1. There is no component page.** The epic's "component page" does not exist as a route. The
surfaces are the catalog landing grid (component *cards*), the viewer `/{system}/p/{id}`, the
focused comparison `/{system}/compare/{previewId}`, and the parity dashboard. The comparison page is
already keyed by preview id with the reference chosen by `?reference=`, which makes it the natural
home for per-component issue display — and it is where a reporter already is when they see the
difference. **Recommendation: do not invent a component page.** Put issues on the viewer, the
focused comparison, the grid cards, and the dashboard, which is what the epic's presentation section
actually asks for once "component page" is read as "the page you are on when you see the problem".

**2. Scoring is client-side, in a candidate-sized normalised space — and it is not SSIM.** Two
details matter and both were easy to get wrong:

- **The active scorer is `scorePlanes`**, a bidirectional nearest-neighbour search within
  `EDGE_SEARCH_RADIUS = 5` px with a `LUMA_TOLERANCE = 16` floor, scored symmetrically in both
  directions. `ssim` / `globalSsim` are still in the file but **have no callers**. Anything that
  claims to reproduce the server's verdict — the mask algorithm, the cross-engine conformance
  fixtures in §4 — has to reproduce *that* edge-search behaviour, not an SSIM window.
- **There are already two planes, both keyed off the candidate.** The score runs on a plane capped
  at `MAX_SIDE = 192` px on its longest side; the diff map and triptych run on the uncapped
  candidate content box. Both take their dimensions from `boxes.candidate`, which moves with device
  size, density and content — so neither is a stable place to *store* anything. At 192 px an
  `EDGE_SEARCH_RADIUS` of 5 is also a very coarse neighbourhood for a glyph-sized region.

This is the single largest implementation constraint, and it is why §4 gives acceptance its own
canonical plane rather than reusing either of these.

**3. The reference already carries a fingerprint.** `DesignReferenceRaster.sha256` is optional today
and verified at ingestion when present. The epic's "require the reference fingerprint to match"
requirement is therefore nearly free: make it *required for any reference an acceptance targets*,
and the invalidation rule is a string comparison rather than new hashing infrastructure.

**4. A file-only commit propagates without a render.** `ServeCatalogRefresher` re-fetches on any
branch head move, and `ServeCatalogStore.load` re-stages the whole tree. So a workflow that commits
*only* `parity/issues.json` to `design-artifacts/<system>` reaches every serving host within one
refresh interval, with no catalog regeneration. The epic's "updating this index must not require
rerendering the catalog" is satisfiable exactly as written.

---

## 2. The locator contract

The identity is `repository + system + componentId + previewId + referenceId + variant`, and every
part of it is already computable on the serve host:

| Field | Source today |
| --- | --- |
| `repository` | `ServeIssueReport.repoFor(source, provenance)` — catalog source repo, then delivery repo, then `yschimke/compose-ai-tools` |
| `system` | the served catalog id (`m3`, `wear-m3`, …) |
| `componentId` | `ServePreview.componentId` from `catalog.json`; falls back to `ServeParityDashboard.componentKey`'s derivation when the catalog names none |
| `previewId` | the route-safe served id (`iconbutton-tonal__ideal__default__light`) — **not** the daemon discovery id |
| `referenceId` | `DesignReference.id` |
| `variant` | the axis segments already inside the preview id, plus any live overrides in force |
| `revision` | `repo@branch` provenance + the compose-ai-tools version that rendered it |

Two rules worth writing into the schema doc so they survive contact with a second implementer:

- **The preview-server URL is a reproduction link, never the identity.** Hosts, tokens, branches and
  URL shapes change; `ServeIssueReport.withoutToken` already strips the capability out of anything
  that reaches an issue body, which is a second reason the URL cannot be an identity.
- **`previewId` is the served id, not the discovery id.** This mistake has already been made and
  documented once — see the `previewIds` note in
  [`public-preview-server.md`](../public-preview-server.md#the-wire-format). Publishing a discovery
  id fails *silently*: the page still renders and every row just quietly loses its link.

The locator is emitted into the issue body as a fenced, versioned block so the indexer can parse it
back out without GitHub Projects or per-component labels:

    ```compose-parity-locator/v1
    repository: yschimke/m3-catalog
    system: m3
    component: IconButton/Tonal
    preview: iconbutton-tonal__ideal__default__light
    reference: iconbutton-tonal-figma
    variant: ideal/default/light
    revision: yschimke/m3-catalog@main
    ```

Fenced rather than an HTML comment: a comment is invisible in the rendered issue, and a reporter
editing the body has no way to see they have broken it. A fenced block is visible, copy-pasteable,
survives edits, and is trivially recoverable by the indexer. The prose table `ServeIssueReport.body`
already writes stays as-is — the block is *additional*, and the two are generated from one `Context`
so they cannot disagree.

**Labels stay low-cardinality**, exactly as the epic specifies: `area:{spec,component,preview,
renderer,comparison}` and `parity:{regression,known-difference,verification-needed}`. No label per
component — component identity lives in the locator block.

---

## 3. The published issue index (`parity/issues.json`)

Copy `parity/activity.json` wholesale. That pattern is already load-bearing, already documented, and
already has a trust boundary with tests:

- **Wire format** `compose-preview-issues/v1` at `parity/issues.json`, shape as in the epic.
- **Reader** a `ServeParityIssues.kt` mirroring `ServeParityActivityStore`: schema token check,
  per-record validation, caps (`MAX_ISSUES`), free-text clamping, and **URL reassembly from
  validated parts** — an issue's `url` is rebuilt from a validated `owner/repo` + integer number
  against a literal `https://github.com/` origin, never taken from the file. A catalog is
  third-party data carrying titles other people wrote.
- **Staging** `ServeCatalogStore.writeParityIssues`, beside `writeParityActivity`, validating before
  it writes. A file nobody stages is invisible to the host however faithfully it was published.
- **Producer** a pure half `scripts/design-artifacts/parity-issues.mjs` (no I/O, no network, unit
  tests without `npm ci`) driven by an I/O half `emit-parity-issues.mjs`, with the output committed
  as `scripts/design-artifacts/fixtures/parity-issues.json` and loaded by the Kotlin reader's own
  test — so the two languages cannot drift apart silently. This is exactly how `parity-activity.mjs`
  is arranged and it has already paid for itself.
- **Failure posture** fail-soft. A missing file, a wrong schema token, a malformed record: drop that
  record or the whole index and serve the catalog normally. Issue badges are an enhancement; they
  must never cost a catalog its grid.

### Where the regeneration workflow lives

**Not in `design-artifacts-reusable.yml`.** That workflow renders the catalog (8–29 min scoped,
31–38 min full) and is the wrong granularity for "someone relabelled an issue". Instead: a small
workflow **in the catalog repo** (`m3-catalog`), triggered on `issues:
[opened, edited, closed, reopened, labeled, unlabeled]`, that queries its own issues, emits
`parity/issues.json`, and commits **that one file** onto `design-artifacts/<system>`. Serving hosts
pick it up on their next refresh tick with no render.

Two consequences to design for, both sharper than they first look:

**The two publishers race, and the existing push helper resolves that race by discarding one side.**
`design-artifacts-reusable.yml` publishes through
[`push-branch.sh`](../../.github/actions/apply/lib/push-branch.sh), which computes `TREE=$(git
write-tree)` **once, before** its retry loop, and on a non-fast-forward re-fetches the tip and
re-parents *that same tree* onto it. It never merges files from the newly fetched parent. So
"preserve an existing `parity/issues.json` when re-publishing" is not sufficient: the preservation
would be a copy step that runs before the race is discovered, and an index committed during a render
publish is dropped wholesale by the reparent — leaving badges stale until the next issue event
happens to fire, which could be days.

**The race is asymmetric, and the fix has to be too.** The two publishers are not peers: the render
publisher's tree is authoritative for the whole bundle *except* the index, and the index publisher's
tree is authoritative for *only* the index. A single symmetric "carry these paths forward" rule
breaks in one of the two orderings — an index publisher enabling carry-forward on
`parity/issues.json` would replace its own freshly-generated blob with the stale copy from the
fetched parent and never publish anything, while an index publisher *without* it reparents its stale
render tree and rolls back whatever renders just landed. So each side gets the rule that matches
what it actually owns:

- **Render publisher — carry forward.** On each fetch inside the retry loop, take
  `parity/issues.json` from the fetched parent, restage it, recompute the tree, then `commit-tree`.
  Its own tree wins everywhere else.
- **Index publisher — one-path delta.** Never build a tree from a working directory at all. On each
  fetched tip, start from the **parent's** tree and replace exactly one path with its own new index
  blob. Everything else comes from the parent by construction, so a render that landed mid-flight is
  preserved whichever order the two pushes arrive in.

Both are changes to `push-branch.sh`, which is shared with other publishers, so both must be opt-in
(one env var naming paths to carry forward, one selecting delta-on-tip mode) and covered by tests
that exercise **both orderings** — index-wins-then-render-retries and render-wins-then-index-retries.
A test for only the first ordering would pass against the broken design.

**And index-vs-index is a third ordering, which delta-on-tip makes *worse*.** Two issue-triggered
jobs can overlap: an older job queries the issue list, a close or relabel lands, a newer job queries
and pushes first, and the older job then loses its race — at which point delta-on-tip does exactly
the wrong thing, faithfully reapplying its own **stale** blob onto the new tip. The older snapshot
wins and the badges stay wrong until the next issue event or the daily reconciliation. Delta-on-tip
is the right rule against the render publisher (whose tree never contains a competing index) and the
wrong one against another index publisher (whose tree contains a *fresher* one).

Fix it at the job level rather than in the helper: give the index workflow a **`concurrency` group
per system with `cancel-in-progress: true`**, so a superseded query never reaches the push at all.
That is the natural shape for an event-triggered regeneration — the newest event's snapshot is the
only one anyone wants — and it needs no cross-repo coordination. If a job must be allowed to finish,
the alternative is to **re-query on each retry** rather than reusing the blob it computed before the
race. Either way, add **index-vs-index** to the race tests alongside the two above.

Two alternatives, if that turns out to be more surgery on a shared helper than is wanted:

- **Serialize the two publishers** with a shared `concurrency` group per system, so an index commit
  and a render publish never overlap. Cheapest to build, but couples two workflows across two repos
  and turns a fast index update into something that can queue behind a 30-minute render.
- **Keep the index off the delivery branch entirely** — its own branch or a release asset that
  `ServeCatalogStore` fetches separately. Cleanest isolation, most new plumbing, and it gives up the
  "one branch is the whole catalog" property the rest of the design leans on.

**Recommended: the two-sided rule above.** It fixes the race for any future file with the same
"written by a different job" shape rather than only this one. Settle it before Phase 1 step 3 ships —
the failure is silent and looks like "the index is just a bit behind", or worse, like renders
mysteriously reverting.

**Cross-repository triggers.** One delivery branch can carry issues from several repositories (a
catalog whose components are implemented elsewhere). `repository` is per-issue in the schema, so the
*format* handles it — but a workflow triggered only by `issues:` events in the catalog repo never
wakes when someone closes or relabels an issue in one of the other scanned repos, so those rows go
stale indefinitely. Telling the emitter which repos to scan is necessary and not sufficient. Every
configured source repo needs either a `repository_dispatch` into the catalog repo from its own
`issues:` workflow, or the whole thing needs a scheduled reconciliation pass as a backstop.
**Recommended: both** — dispatch for latency, a daily cron for the repos nobody remembered to wire
up. A reconciliation pass is cheap here precisely because regenerating the index costs no render.

Both the serve host and the design-parity CI run read the same file. Neither ever calls the GitHub
API at page-render time — same rule that keeps the host away from Figma.

---

## 4. Scoped acceptance

### The artifact

Committed in the **source** repo, beside `design-map.json`:

    .design-parity/
      known-differences.json                       # compose-preview-known-differences/v1
      known-differences/
        m3-iconbutton-tonal-glyph/
          mask.png                                 # binary mask, canonical (reference) plane
          accepted-candidate.png                   # the accepted crop, same plane

Each acceptance record carries: a stable `id`; a **mandatory** `issue` URL; the locator scope
(`system` / `component` / `previewId` / `referenceId` / `variant`); an optional `element` selector
(see the annotation-contract prerequisite in §5, when the region came from an annotated element
rather than a drag); `mask` and `acceptedCandidate` paths; **three** hashes — `referenceSha256`,
`acceptedCandidateSha256` **and `maskSha256`**; the **canonical plane the mask was authored
against** — a `plane: "content-box" | "full-canvas"` discriminant plus the resolved
`{x, y, width, height}` box, without which the plane gate (step 2 below) cannot be evaluated at
all; an optional structured `finding` matcher (e.g. `{ kind: "color", token: …, expected: …,
actual: … }`) for the checks design-parity runs that are not pixel comparisons; and free-text
`note` + `acceptedAt`.

**The mask must be hashed, not just referenced by path.** The mask is the thing that decides *what
gets suppressed*, so an edited or swapped `mask.png` with an unchanged JSON record silently widens
the accepted region — hiding regressions the record still claims are in scope, with no invalidation
anywhere. That is a direct breach of the safety requirement the whole model exists for, and it is
the one tampering path the other two hashes do not cover. A mask whose bytes don't match its
`maskSha256` is a **hard validation failure** (the acceptance is refused outright and reported),
not an invalidation that degrades to "compare normally" — a mask we cannot trust is a broken
artifact, not a stale one.

The schema is defined **here**, in this repo, because `serve` is a consumer and this is where the
other wire contracts (`compose-preview-references/v1`, `compose-preview-annotations/v1`,
`compose-preview-activity/v1`) already live. `design-parity` and `@design-parity/catalog-export` are
the second consumer and the publisher respectively; that is cross-repo coordination and should be
sequenced as such (§6).

### Coordinate space — the real problem

The mask has to be authored somewhere stable, and the accepted pixels have to be *stored* somewhere
stable. Neither of the planes `format-compare.js` already builds qualifies, because both are sized
from `boxes.candidate` (finding 2 in §1): the same component re-rendered at a different device size
or density produces a different plane, so a stored crop would mismatch on dimensions alone and every
acceptance would false-invalidate as `candidate-changed` — or would need a resample whose resampling
error immediately swamps the tight per-pixel tolerance §7 calls for.

So acceptance gets **its own canonical plane, defined by the reference**:

- **The canonical plane is the reference's content box, at the reference raster's own resolution.**
  The reference is a published PNG with fixed dimensions and a `sha256` that the acceptance already
  pins, so this plane is byte-stable by construction — it cannot move unless the reference changes,
  and a changed reference is already gate 1.
- **`mask.png` is authored in it directly** — but *nothing else is already in it*, and that is the
  easy mistake. See the translation rules below.
- **`accepted-candidate.png` is stored in it**, cropped to the mask's bounding box. Evaluating an
  acceptance therefore means resampling the *current* candidate's content box into the canonical
  plane — one resample of the live frame, against a stored crop that never moves — rather than
  storing a crop in a plane that moves under it.
- **Suppression is then mapped into whichever plane is being reported.** The canonical plane, the
  score plane and the diff plane are all content-box crops related by an affine scale, so the mask's
  coverage maps into each with the same arithmetic `boxCanvas` already does. Do the *comparison* at
  canonical resolution and the *reporting* wherever the surface lives; do not do the comparison at
  the 192 px score plane, where a glyph is a handful of pixels and a 5 px edge search covers most of
  it.

This costs one extra resample per acceptance per comparison, on a page that is already decoding two
PNGs and walking them pixel by pixel. That is the right trade for making "did this exact accepted
region change?" a question with a stable answer.

#### Translating a selection into the canonical plane

The canonical plane is a **crop**, so its origin is `(referenceBox.x, referenceBox.y)` in the
reference raster — generally non-zero. Nothing a human or the UI hands us starts there, and treating
any of these as already-canonical shifts the mask by the content-box origin, which silently targets
the wrong pixels. Every source needs an explicit transform, and every result needs clipping to the
plane:

| Source | Native space | Transform into the canonical plane |
| --- | --- | --- |
| Reference annotation `bounds` | the **full reference raster** (`DesignAnnotation` KDoc: "the annotated image's own pixel space") | subtract `(referenceBox.x, referenceBox.y)`; scale 1; clip |
| Drag rectangle on the reference panel | CSS/display pixels of the `<img>` | scale by `rasterWidth / displayWidth` and `rasterHeight / displayHeight`, then subtract the box origin; clip |
| Render-side annotation `bounds` | **render** pixel space | subtract `(candidateBox.x, candidateBox.y)`, then scale **x and y independently** — `referenceBox.width / candidateBox.width` for x, `referenceBox.height / candidateBox.height` for y; clip |

**The two axes scale independently, and that is not a rounding detail.** `boxCanvas` stretches each
source box onto the target width and height separately, and the comparison explicitly *supports*
the two content boxes having different aspect ratios — `aspectDelta` reports the proportion
difference as a finding rather than normalising it away. So a reference and a render that disagree
about proportion (exactly the case an acceptance is most likely to be sitting on) would put a
single-ratio mask at the right x and the wrong y.

A selection that clips to empty is refused at authoring time rather than stored as a zero-area mask.

**One stability hazard to pin down before implementing.** `normalisedBoxes` does not always use the
content box: when either side's box covers less than `MIN_BOX_COVERAGE` (5%) of its canvas it falls
back to the **full canvas** for *both* sides. So the plane's definition can flip between "content
box" and "full raster" depending on the candidate's coverage — which the reference's `sha256` does
not pin. An acceptance must therefore record **which of the two the plane was** (a `plane:
"content-box" | "full-canvas"` discriminant plus the resolved box), and a comparison whose fallback
disagrees with the recorded one is `invalidated: plane-changed` rather than silently compared in the
wrong space.

### Evaluation order (the safety requirements, as an algorithm)

Given the raw normalised pair and the acceptances whose scope matches this `(previewId,
referenceId, variant)`:

1. **Fingerprint gate.** If the served reference's `sha256` ≠ the acceptance's `referenceSha256`,
   the acceptance is `invalidated: reference-changed`. It contributes no suppression, and the page
   says so. An acceptance targeting a reference that publishes no `sha256` is refused at validation
   time.
2. **Plane gate.** Recompute the plane for this pair. If its `plane` discriminant or resolved box
   disagrees with the acceptance's recorded one — a candidate that has crossed `MIN_BOX_COVERAGE`
   since the acceptance was authored — it is `invalidated: plane-changed`. Comparing across two
   different coordinate planes is meaningless, so this has to precede any pixel work.
3. **Candidate gate.** Inside each mask, compare the current candidate against
   `accepted-candidate.png` — not against the reference. Match within tolerance ⇒ the acceptance
   stays valid. Mismatch ⇒ `invalidated: candidate-changed`, and the region is reported as a new
   difference.
4. **Element gate.** When the acceptance names an `element`, resolve it against the current
   annotation layer. Missing, or moved beyond a bounds tolerance ⇒ `invalidated: element-moved`.
   This is what catches "the glyph disappeared" as distinct from "the glyph is still the wrong
   colour", which a rectangular ignore region fundamentally cannot.
5. **Only now, score** — everything outside the union of the **still-valid** masks, where "outside"
   means excluded in **both** roles (see below). An invalidated acceptance suppresses nothing.
6. **Report raw, accepted and unaccepted separately.** The raw finding is never destroyed. The
   comparison shows all three numbers and the delta map gains an "accepted" tint distinct from the
   magenta of unaccepted difference, so an acceptance is *visible* rather than a hole in the data.

**All four gates run before any scoring, and that ordering is load-bearing.** The tempting order —
score first, then check the acceptances and report the failures — does not work, because scoring
with a mask excluded is not something you can undo afterwards. The exclusion removes those
coordinates from the *neighbourhood search* in both directed passes, so the contribution of every
pixel near the mask is computed differently; adding the region back into the report afterwards
cannot reconstruct what the score would have been had the mask never been applied. An acceptance
that turns out to be `candidate-changed` or `element-moved` would therefore keep inflating the
effective score it was no longer entitled to suppress. Validate first, then score once against the
surviving set.

#### Masked pixels must be excluded in both roles, in both directions

Step 5 says "outside the union of masks", and the obvious reading — skip masked pixels when
iterating — is **not sufficient**, because of how `scorePlanes` actually works. Each directed pass
takes a source pixel and searches a ±`EDGE_SEARCH_RADIUS` (5 px) neighbourhood of the *target* plane
for its best match. So an unmasked pixel just outside a mask can find its best match at a target
pixel *inside* the mask — and since the inside pixels are accepted-but-different by construction,
that match can erase a real mismatch. A regression within 5 score-plane pixels of an accepted region
would score as clean.

The rule therefore has to be: **a masked coordinate is excluded both as a scored source and as a
candidate neighbour, in both directed passes.** A source pixel whose entire neighbourhood is masked
contributes nothing rather than falling back to a best-of-nothing default.

This is precisely the kind of thing two implementations can agree on for ordinary inputs and diverge
on at the boundary, so the conformance fixtures in the next section must include **a regression
placed within `EDGE_SEARCH_RADIUS` of a mask edge** as a named case. Without it, both engines can
pass the suite and still let an accepted region hide its neighbour.

#### A score-plane pixel can straddle the mask edge

The exclusion rule above is stated in score-plane coordinates, but the mask is authored in the
canonical plane, and the two are not the same resolution. `scoreImages` downsamples each whole
content box with a smoothed `drawImage` **before** `scorePlanes` runs, capped at `MAX_SIDE = 192`.
So a single score-plane pixel can have a source footprint that straddles the mask edge, and by the
time the mask is mapped down it is a binary answer about a pixel that is genuinely part accepted and
part not. Either choice is wrong in one direction: drop it and an adjacent regression can hide
inside the boundary ring; keep it and accepted pixels bleed into the score they were supposed to
leave alone.

**Resolve it conservatively: a score-plane pixel is masked only if its *entire* source footprint is
masked.** A partially-covered pixel is scored normally. That biases the boundary ring toward
over-reporting — an accepted difference may leak a thin halo into the score — which is the correct
direction to be wrong given the non-goals: an acceptance that reports slightly too much is a
cosmetic annoyance, one that reports too little is the failure mode the whole model exists to
prevent. The halo is also bounded and explainable, so the page can say so rather than looking like
an unexplained residue.

The alternative — evaluating mask boundaries at canonical resolution and only then downsampling — is
more faithful but means restructuring the scoring path around the mask. Worth revisiting if the halo
turns out to be visible in practice; not worth it up front.

Either way, **the fixtures must include a mask edge deliberately not aligned to the score-plane
grid**, since an axis-aligned fixture at a convenient scale would never exercise this at all.

This is deliberately more expensive than a threshold or an ignore rectangle, and that expense is the
point: the epic's non-goals rule out anything that can hide an unrelated regression, and gates 3 and
4 — plus the neighbourhood exclusion and the footprint rule above — are what make an accepted colour
delta unable to mask a missing glyph or the regression sitting next to it.

### Two engines, one semantics

`design-parity`'s offline run and `format-compare.js` must agree, or an acceptance means different
things depending on which tool you asked. Options considered:

- **Duplicate the algorithm in both** — status quo shape, and the failure mode is silent divergence.
- **Publish the effective verdict from the offline run and have serve display it** — cheap, but the
  browser scorer is what runs against a *live* render with overrides in force, so it would have
  nothing to apply acceptance to.
- **Shared conformance fixtures** — a committed set of `(reference, candidate, acceptance) →
  expected {raw, accepted, unaccepted, invalidations}` cases, in this repo, run by both the JS unit
  tests here and design-parity's own suite.

**Recommended: the third.** It is the same device already used for `parity-activity.mjs` ↔
`ServeParityActivityStore` (one committed fixture, two languages, both tests load it), it is cheap,
and it fails loudly.

The fixtures have to pin the **active** scorer's behaviour, not a textbook one: `scorePlanes`'
bidirectional search at `EDGE_SEARCH_RADIUS = 5`, its `LUMA_TOLERANCE = 16` floor, and the
`MAX_SIDE = 192` cap on the score plane are all load-bearing to the number that comes out. A fixture
suite written against SSIM windows would pass in both engines and describe neither.

---

## 5. Element selection on the focused comparison

The comparison page already receives `referenceAnnotations` and `actualAnnotations` and draws them
as numbered boxes. Element selection is therefore: make an annotation box clickable, and let the
selection become the reported region. Manual drag-rectangle is the fallback for the common catalog
that publishes no annotations.

Two prerequisites came out of reading the handler and the annotation model, and **both** have to
land before element selection can back an acceptance.

**The render side of this page has nothing to click.** `handleReferenceComparison` sources the
*actual* layer from `annotationsForPreview` — the **producer-authored** `annotations/index.json` —
not the semantics-derived layer `ServeDesignAnnotations` builds for the viewer's inspection
overlays. So on most catalogs the render column carries no annotations at all. Feeding the
semantics-derived layer into this page (as the viewer already does) is small and independently
useful.

**`DesignAnnotation` cannot express a stable element identity.** There is no `testTag` field on the
type at all, and the projection throws the tag away: `themeAnnotation` collapses
`node.role ?: node.testTag ?: node.textSnippet()` into a single `role` string, and
`typographyAnnotation` sets `role` from `textSnippet()` alone, dropping the tag entirely. So an
`element` selector keyed on "role / testTag" is not a selector — a node carrying a common role
(`Button`) and a unique `testTag` loses the only part that identified it, and the element
check would either resolve the wrong one of several repeated roles or fail to resolve at all. Both
outcomes are worse than no element check: one silently moves an acceptance onto a different element,
the other permanently reports `element-moved`.

The fix is a **distinct, additive selector field on the annotation contract** — carry `testTag` and
the semantics `ref` as their own fields rather than folding them into the display `role`. `role`
stays what it is: a human-facing label for the legend, never an identity. This is a
`compose-preview-annotations/v1` addition and should be sequenced with the semantics-layer wiring
above, in Phase 2 step 5.

**But not every `ref` is durable, and preferring `ref` blindly is worse than having no selector.**
`SemanticsRefs` anchors on the test tag when there is one (`r/tag:submit`), falls back to the role
(`r/role:Button`), and — the problem — **indexes siblings that share an anchor**
(`r/role:Button[0]`, `r/role:Button[1]`; see `SemanticsRefsTest`). That index is a structural
occurrence number, so inserting or reordering repeated-role siblings makes the *same* ref string
resolve to a *different node*. It resolves successfully, so nothing falls through to a second
choice; and if the new occupant sits in roughly the old bounds, the element gate passes too. The
acceptance silently transfers to another element — the exact failure the gate exists to prevent,
arrived at through the mechanism meant to prevent it.

So durability is a property to **test for, not assume**:

| Ref shape | Durable? | Use as acceptance identity |
| --- | --- | --- |
| `r/tag:<tag>` | yes — a tag change moves the ref, which is detectable | **yes** |
| `r/role:<role>[n]` | no — the index is positional and silently retargets | **no** |
| `r/role:<role>` (lone anchor) | no — gaining a sibling turns it into `[0]`/`[1]` | no, but fails *loudly* |
| `r/node` | no — purely structural | no |

**Persist an element selector only when it is a `tag:`-anchored ref or another producer-defined
stable identity. Otherwise keep the drag rectangle** and accept the region geometrically, with no
element gate. A geometric acceptance is weaker — it cannot tell "the glyph disappeared" from "the
glyph is still wrong" — but it is honest about what it knows, whereas an indexed ref claims an
identity it cannot keep. The non-indexed `r/role:` case is worth distinguishing in the
implementation because its failure is the safe one: it stops resolving and reports `element-moved`
rather than pointing somewhere new.

Reporting an issue from a selection must **not** accept the difference. Filing and accepting are
separate, deliberate acts by separate artifacts — one is a GitHub issue the visitor's own browser
files, the other is a committed file that goes through review.

---

## 6. Delivery order

Sequenced so each step is independently useful and nothing is blocked on the cross-repo work.

### Phase 1 — Issue visibility

1. **Locator contract + richer issue body.** Extend `ServeIssueReport.Context` with `componentId`,
   `referenceId`, variant axes, active overrides, comparison URL and raw scores; emit the
   `compose-parity-locator/v1` block alongside the existing prose table. No new files on the wire —
   but **not pure Kotlin**, see below. *Smallest useful PR; everything else keys off it.*

   The server fills the form's hidden `body` for the settings the page was **served** at, and
   `viewer.js`'s `refreshReportLink()` then rewrites exactly one thing: it swaps `{{render}}` for
   the live `/render` URL. Every other character of the body stays the initial server template. So
   a locator built entirely server-side would record the *default* variant while the embedded
   screenshot shows whatever the reporter had dialled in — the index would key the issue to one
   identity and the pixels would show another, and the acceptance lookup in Phase 3 would then miss.
   The override-dependent fields (variant, active overrides, comparison URL) need their own
   placeholders that `refreshReportLink()` substitutes from live viewer state, on the same "write an
   input value, never an href" rule the existing substitution already follows. The JS-off path keeps
   the server-rendered defaults, which are correct for a page nobody has touched.

   **The raw score is a separate problem: the surface with the report form is not the surface that
   knows the score.** The form (`cp-report-body`) and `refreshReportLink()` live only on the viewer,
   and the viewer's always-available live number is `scoreSvgUrls` — PNG against the *generated
   SVG*, a render-fidelity measurement that has nothing to do with the design reference. Emitting
   that as the locator's parity score would be silently wrong in the most expensive way: a plausible
   number, mislabelled, feeding an index. The reference-vs-render score exists in two places —
   `spec-compare.js` on the viewer, but **only while the Spec lane is open**, and the focused
   comparison page, which computes it on every load and has **no report form at all** (its body is
   the triptych, the overlay, `url-state.js` and `format-compare.js`).

   So Phase 1 step 1 has to pick a surface rather than assume one:

   - **Add the report form to the focused comparison.** Preferred. It is the page that always has a
     concrete `(previewId, referenceId)` pair *and* the score, it is where element selection lands
     in Phase 2 (so the two arrive together), and it is where a reporter is standing when they see
     the difference — §1's finding 1 already argued that.
   - **On the viewer, emit the score only when the Spec lane has computed one**, and omit the field
     otherwise. A missing field is honest; a wrong one is not. With multiple references the viewer
     also has no client-side notion of which one is selected, so the `referenceId` half of the
     locator is only reliable once the lane is up.
2. **`compose-preview-issues/v1` + reader + staging.** `ServeParityIssues.kt`, `ServeCatalogStore`
   staging, fixture-backed tests. Serves nothing yet.
3. **Producer + regeneration workflow.** `parity-issues.mjs` / `emit-parity-issues.mjs` here; the
   issue-triggered workflow lands in the catalog repo.
4. **Show open issues** on the viewer row, the focused comparison, the grid cards and the dashboard.
   *First visible payoff, and the point at which the epic's first four acceptance criteria are met.*

### Phase 2 — Triage

5. **Semantics annotations on the focused comparison** (the prerequisite from §5).
6. **Element selection** — click an annotated element, or drag a region; the selection rides into
   the prefilled report.
7. **Dashboard views** — new-vs-known split, components with open issues, area classification.

### Phase 3 — Scoped acceptance

8. **`compose-preview-known-differences/v1` schema + conformance fixtures**, defined here.
9. **Apply acceptances in `format-compare.js`** per §4, raw/accepted/unaccepted reported separately.
10. **Publish through catalog-export**, and **apply the same semantics in `design-parity`**.
    *Cross-repo; sequence after 8 so both sides build against a settled schema and the shared
    fixtures.*

### Phase 4 — Resolution

11. **Detect resolved** (raw difference gone, acceptance still present), **invalidated** (any of the
    four causes in §4 — `reference-changed`, `plane-changed`, `candidate-changed`, `element-moved`),
    and **stale** (issue closed, acceptance remains) — surfaced on the dashboard and as a gate in
    the offline run.
12. **Document** the reporting → triage → acceptance → verification → closure loop in
    `docs/public-preview-server.md`, beside the existing parity view section.

---

## 7. Risks and open questions

- **Mask authoring has no UI in this plan.** Phase 3 defines the artifact and both consumers, but a
  human still hand-writes `known-differences.json` and produces the two PNGs. That is probably
  acceptable for the first acceptances (they should be rare and deliberate), but "export this
  selection as an acceptance stub" from the focused comparison is the obvious follow-up, and worth
  deciding on before Phase 3 rather than after.
- **Tolerance is a threshold, and the epic is against thresholds.** Step 3 of §4 needs *some*
  tolerance for the candidate-vs-accepted-candidate comparison (PNG re-encoding, and the one
  resample of the live candidate into the canonical plane). It should be tight, fixed, and
  **per-pixel rather than aggregate** — an aggregate tolerance is exactly the global threshold the
  non-goals rule out. Pinning the canonical plane to the reference (§4) is what keeps that resample
  to a single, well-defined step instead of a moving target; if the tolerance still has to be loose
  enough to be uncomfortable, that is the signal to store the accepted candidate losslessly at
  canonical resolution rather than to widen it.
- **Fixing the publish race means touching a shared helper.** `push-branch.sh` is used by other
  publishers, so the carry-forward behaviour in §3 has to be opt-in (an env var naming the paths)
  and covered by its own test. A change that silently altered how every publisher resolves a race
  would be a much worse bug than the one it fixes.
- **Cross-repo schema ownership.** Defining the known-difference schema here and consuming it in
  `design-parity` means a version bump is a two-repo change. The conformance fixtures are the
  mitigation; a `v1`-frozen-then-`v2` discipline (as with the other wire formats) is the other.
- **The example issue is in a third repo.** `m3-catalog` drives the end-to-end validation, so Phase
  1 step 3 and Phase 3 step 10 both need work landing there. Worth confirming that repo is the
  intended pilot before Phase 1 step 3.
- **What counts as "the same variant"?** The locator carries variant axes *and* active overrides. An
  acceptance recorded with `fontScale=1.5` in force should almost certainly not apply at
  `fontScale=1.0`, but the current preview id does not encode overrides. Decide whether overrides
  are part of the acceptance scope (recommended: yes, and an acceptance with any override recorded
  applies only at those overrides) before Phase 3.
