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
| Scoring | [`format-compare.js`](../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js) | SSIM over content-box-normalised, downscaled gray planes + a magenta delta map — **entirely in the visitor's browser** |
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

**2. Scoring is client-side, in a normalised space.** `format-compare.js` crops each side to its
content box, redraws both into one shared box, and scores there. So a mask expressed in raw
reference pixels is *not* directly usable — it has to ride through the same `normalisedBoxes`
transform the score uses. This is the single largest implementation constraint and §4 designs around
it.

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

Two consequences to design for:

- The index is written by a *different* job than the one that writes the rest of the branch, so it
  must never rewrite anything else, and the render pipeline must not clobber it. Simplest safe rule:
  `design-artifacts-reusable.yml` **preserves** an existing `parity/issues.json` when re-publishing
  rather than treating the bundle as authoritative for that path. Worth an explicit test.
- One delivery branch can carry issues from several repositories (a catalog whose components are
  implemented elsewhere). `repository` is per-issue in the schema, so this works, but the emitter
  needs to be told which repos to scan rather than assuming `github.repository`.

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
          mask.png                                 # binary mask, reference raster pixel space
          accepted-candidate.png                   # the render crop that was accepted

Each acceptance record carries: a stable `id`; a **mandatory** `issue` URL; the locator scope
(`system` / `component` / `previewId` / `referenceId` / `variant`); an optional `element` selector
(annotation `role` / `testTag`, when the region came from an annotated element rather than a drag);
`mask` and `acceptedCandidate` paths; `referenceSha256`; `acceptedCandidateSha256`; an optional
structured `finding` matcher (e.g. `{ kind: "color", token: "onSecondaryContainer", expected: …,
actual: … }`) for the checks design-parity runs that are not pixel comparisons; and free-text
`note` + `acceptedAt`.

The schema is defined **here**, in this repo, because `serve` is a consumer and this is where the
other wire contracts (`compose-preview-references/v1`, `compose-preview-annotations/v1`,
`compose-preview-activity/v1`) already live. `design-parity` and `@design-parity/catalog-export` are
the second consumer and the publisher respectively; that is cross-repo coordination and should be
sequenced as such (§6).

### Coordinate space — the real problem

The mask has to be authored somewhere stable and applied somewhere normalised.

- **Author in reference-raster pixel space.** The reference is a published PNG with fixed
  dimensions, a `sha256`, and annotation bounds already expressed in that space. The render's pixel
  space is not stable — it moves with device size, density, font scale and any override in force.
- **Apply after normalisation.** `normalisedBoxes(referenceImage, candidateImage)` already computes
  each side's content box and the shared output box. The mask goes through the *reference* side of
  that same transform, giving mask coverage in the shared space that both the score and the delta
  map live in. No new geometry, and the mask automatically follows a reference re-exported at a
  different scale.
- **`accepted-candidate.png` is stored in the shared normalised space**, cropped to the mask's
  bounding box. Storing it in raw render pixels would make it invalid the moment anyone changes a
  device size, which is precisely the kind of silent staleness the acceptance model exists to catch
  *deliberately*, not accidentally.

### Evaluation order (the safety requirements, as an algorithm)

Given the raw normalised pair and the acceptances whose scope matches this `(previewId,
referenceId, variant)`:

1. **Fingerprint gate.** If the served reference's `sha256` ≠ the acceptance's `referenceSha256`,
   the acceptance is `invalidated: reference-changed`. It contributes no suppression, and the page
   says so. An acceptance targeting a reference that publishes no `sha256` is refused at validation
   time.
2. **Score everything outside the union of masks normally.** This is the ordinary path and is
   untouched by acceptance; a regression anywhere else scores exactly as it does today.
3. **Inside each mask, compare the current candidate against `accepted-candidate.png`** — not
   against the reference. Match within tolerance ⇒ that mask's pixels are suppressed from the
   *effective* diff. Mismatch ⇒ `invalidated: candidate-changed`, nothing is suppressed, and the
   region is reported as a new difference.
4. **Element check.** When the acceptance names an `element`, resolve it against the current
   annotation layer. Missing, or moved beyond a bounds tolerance ⇒ `invalidated: element-moved`.
   This is what catches "the glyph disappeared" as distinct from "the glyph is still the wrong
   colour", which a rectangular ignore region fundamentally cannot.
5. **Report raw, accepted and unaccepted separately.** The raw finding is never destroyed. The
   comparison shows all three numbers and the delta map gains an "accepted" tint distinct from the
   magenta of unaccepted difference, so an acceptance is *visible* rather than a hole in the data.

This is deliberately more expensive than a threshold or an ignore rectangle, and that expense is the
point: the epic's non-goals rule out anything that can hide an unrelated regression, and steps 3 and
4 are what make an accepted colour delta unable to mask a missing glyph.

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

---

## 5. Element selection on the focused comparison

The comparison page already receives `referenceAnnotations` and `actualAnnotations` and draws them
as numbered boxes. Element selection is therefore: make an annotation box clickable, and let the
selection become the reported region. Manual drag-rectangle is the fallback for the common catalog
that publishes no annotations.

One nuance found while reading the handler: `handleReferenceComparison` sources the *actual* layer
from `annotationsForPreview` — the **producer-authored** `annotations/index.json`, not the
semantics-derived layer `ServeDesignAnnotations` builds for the viewer's inspection overlays. So on
most catalogs the render side of this page has no annotations to click. Feeding the semantics-derived
layer into this page (as the viewer already does) is a small, independently useful change and should
land **before** element selection, or the feature ships with nothing to select on the side that
matters most.

Reporting an issue from a selection must **not** accept the difference. Filing and accepting are
separate, deliberate acts by separate artifacts — one is a GitHub issue the visitor's own browser
files, the other is a committed file that goes through review.

---

## 6. Delivery order

Sequenced so each step is independently useful and nothing is blocked on the cross-repo work.

### Phase 1 — Issue visibility

1. **Locator contract + richer issue body.** Extend `ServeIssueReport.Context` with `componentId`,
   `referenceId`, variant axes, active overrides, comparison URL and raw scores; emit the
   `compose-parity-locator/v1` block alongside the existing prose table. Pure Kotlin + tests, no new
   files on the wire. *Smallest useful PR; everything else keys off it.*
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
    four gates in §4), and **stale** (issue closed, acceptance remains) — surfaced on the dashboard
    and as a gate in the offline run.
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
  tolerance for the candidate-vs-accepted-candidate comparison (PNG re-encoding, resampling). It
  should be tight, fixed, and per-pixel rather than aggregate — an aggregate tolerance is exactly the
  global threshold the non-goals rule out.
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
