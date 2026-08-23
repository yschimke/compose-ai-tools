# Component parity: issues and scoped acceptance

> **Status: Phase 1 shipped; Phases 2–4 are still proposal.** Investigation + phased plan for
> [#3680](https://github.com/yschimke/compose-ai-tools/issues/3680). This document settles the
> contracts (locator, issue index, known-difference schema) and the delivery order; each phase below
> is meant to become its own PR against an existing surface, not a new subsystem.
>
> **What is built:** the `compose-parity-locator/v1` block and the richer focused-comparison report
> (#3887); `parity/issues.json` — producer, reader, staging and the four display surfaces (#3886,
> #4404); and the catalog-side regeneration workflow (yschimke/m3-catalog#170). §3's "pilot
> population" records what running it on real issues then measured. **Everything in §4 and §5 —
> scoped acceptance, element selection, resolution automation — is still a proposal**, and that
> measurement is the reason to read it again before implementing it.
>
> **`v1` erratum, since delivered:** batch 01 required `element` and `bounds` to be **reserved as
> optional fields** before the writer, the parser and the shared fixture froze, and Phase 1 shipped
> without them. Both are now reserved and round-tripped by both engines, `bounds` carrying its plane
> explicitly (D1), and a body may now carry **one block per component** so an umbrella report
> reaches every component page it names. §2 has the contract; §7 records what those two decisions
> settled.

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
| Scoring | [`format-compare.js`](../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js) | `scorePlanes` — a **bidirectional, edge-gated, distance-penalised** comparison over content-box-normalised gray planes (see the six clauses below) — plus a magenta delta map, **entirely in the visitor's browser** |
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

- **The active scorer is `scorePlanes`**, and it is considerably more particular than it looks.
  Every clause below is load-bearing to the number that comes out:

  1. Build an **edge mask** per plane — a pixel is an edge when its 4-neighbour luma gradient
     reaches `EDGE_GRADIENT_THRESHOLD = 12`.
  2. Each directed pass starts from the difference **at the same coordinate**.
  3. It widens to the `EDGE_SEARCH_RADIUS = 5` px displaced search **only** when the source pixel is
     an edge *and* that same-coordinate difference already exceeds `LUMA_TOLERANCE = 16`.
  4. Within that search, a candidate target is considered **only if it is itself an edge** — so
     repeated flat luminance cannot absorb a displaced mark.
  5. Each displaced match is **penalised by distance**: `√(ox² + oy²) × EDGE_POSITION_COST`, with
     `EDGE_POSITION_COST = 10`. Displacement is tolerated, not free.
  6. The per-pixel charge is `max(0, best − LUMA_TOLERANCE) / (255 − LUMA_TOLERANCE)`, averaged over
     `width × height`, and the two directions are averaged into `mismatch`.
  7. The returned score is `max(0, min(100, (1 − mismatch) × 100))` — a **percentage**, clamped.
     Worth stating because the six steps above all describe *mismatch*: a port that stops at step 6
     and returns the average reports `0` for two identical images and `100` for two that share no
     pixel, which is the published number inverted on every comparison.

  `ssim` / `globalSsim` are still in the file but **have no callers**. So it is neither SSIM nor a
  plain nearest-neighbour search — a distinction that matters to anything claiming to reproduce the
  verdict, and one this document got wrong across several revisions before reading the whole
  function. That history is itself the argument for §4's open problems.
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

The identity is `repository + system + componentId + previewId + referenceId + variant + overrides`,
and every part of it is already computable on the serve host:

| Field | Source today |
| --- | --- |
| `repository` | `ServeIssueReport.repoFor(source, provenance)` — catalog source repo, then delivery repo, then `yschimke/compose-ai-tools` |
| `system` | the served catalog id (`m3`, `wear-m3`, …) |
| `componentId` | `ServePreview.componentId` from `catalog.json`; falls back to `ServeParityDashboard.componentKey`'s derivation when the catalog names none |
| `previewId` | the route-safe served id (`iconbutton-tonal__ideal__default__light`) — **not** the daemon discovery id |
| `referenceId` | `DesignReference.id` |
| `variant` | the axis segments already inside the preview id — **axes only**. Live overrides are *not* folded in here; they travel in their own `overrides` field, because two representations of one fact means two ways to spell it and no rule for which wins |
| `overrides` | the whole normalised override map the render lane received (display fields, size fields, overlay toggles, `knob.*`, `rc.*`) — the same set §4 matches acceptances on |
| `revision` | `repo@branch` provenance + the compose-ai-tools version that rendered it |
| `element` | the `testTag` a selection resolved to, as a **JSON string** (`element: "glyph"`). Optional: absent unless the reporter picked an element |
| `bounds` | the region the selection covered — `{"height":…,"space":"render-pixels","width":…,"x":…,"y":…}`. Optional, and independently so: a tag whose every carrying node had a zero-area box names an element with no region |

**A tag selection is only offered where the index describes the frame on screen.** The element tag
index is published per *render* — computed in CI over the baked PNGs, read back by `ServeBundleHost`,
and delegated to that baked host by both live wrappers — so on a frame an override or a revision pin
has re-rendered, its bounds were measured on different pixels. A tag selection persists those bounds
into the locator as the acceptance's *baseline*, so the wrong ones survive into a record that later
reports an unchanged element as `moved`: a false invalidation with a plausible explanation attached,
which is worse than a missing check. The focused comparison therefore withholds the tag picker
whenever the frame is not the baked one, says which of the three reasons applies, and leaves the
**drag** — a dragged region is derived from the displayed pixels, so it describes what the reporter
saw by construction.

That gate is **necessary but not sufficient**, and the residual is caching: a public server sends an
override-free baked render with `max-age=300, stale-while-revalidate=3600` while the index is
`no-store`, so a client can pair pixels from the previous catalog generation with a freshly-fetched
index — the invariant broken by a republish rather than by an override. Closing it needs the image
and the index to carry a **shared generation**, which is the coupling §5 must build before an element
gate may read the index at all; until a gate measures against it, a slightly wrong recorded baseline
is latent rather than active. The index itself is served at `GET /{system}/tags/{previewId}`, in the wire shape
`ServeTagIndexStore` validates, `space` on every entry included.

**A body may carry one block per component, and that is how an umbrella report is indexed.** One
issue legitimately spans several components — m3-catalog#42's Elevated shadow level covers
`Button/`, `Card/` and `ToggleButton/Elevated` — and a block can only name one. So `parseLocators`
reads every fence in the body and the index emits **one row per block**, keyed by issue *and*
component. The blocks must agree: one `repository`, one `system`, no component twice — two rows with
one identity collapse against each other in the reader — and **no preview twice**, because
`issuesForPreview` matches rows by preview id as well as by component, so one preview named by two
blocks would carry the same issue twice and count two in its badge. The alternative — splitting the
umbrella into one issue per component — was rejected because it multiplies the backlog and loses the
one fact that matters most about those issues, that they share a cause.

**`element` and `bounds` were reserved before anything wrote them, deliberately.** Batch 01 called
for both before the writer, the parsers and the shared fixture froze; that did not happen, and both
parsers ignore unknown keys, so a report carrying a selection would have been indexed with the
selection silently discarded — no strict-parser rejection to notice it. Batch 03 fills them: the
focused comparison's `<cp-element-selection>` writes them into the served body template through a
`{{selection}}` placeholder, and `cli/serve-web`'s `report/locator.ts` is pinned to the same shared
fixture as the Kotlin writer and the JavaScript parser, so all three engines agree on the bytes. `bounds` names its space
rather than being a bare rectangle, and `v1` accepts only `render-pixels`: per D1 both tag-index
producers publish render pixels and the canonical-plane transform is a step of the **comparison**, a
plane being a property of a comparison and the index a property of a render. A rectangle with no
space is exactly what makes an element that never moved report as `moved`. Both values are canonical
JSON on the same code-point rule the overrides carry — and `element` is a **quoted JSON string**
rather than a bare value, which is load-bearing rather than tidy: a `testTag` is arbitrary text, the
block is line-oriented, and a bare tag containing a newline does not stay one field. `row⏎revision:
injected` would read back as an element plus a revision nobody wrote, and a tag carrying a fence
delimiter could end the block early and take the whole issue out of the index. Quoting also makes a
tag with leading or trailing whitespace expressible, which a format whose readers trim cannot
otherwise carry — so the writer records the selected tag **verbatim**. A tag index keys on the exact
string, and normalising `" glyph "` into `"glyph"` would point an acceptance at a different element,
or at none.

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
    overrides: {"fontScale":"1.5","knob.label":"Send;now=x","uiMode":"dark"}
    revision: yschimke/m3-catalog@main
    ```

**`overrides` is part of the block, not an optional extra.** §4 makes overrides part of the scope an
acceptance matches on, so a locator that omits them describes two frames at once: an issue filed at
`fontScale=1.5` and one filed at the default serialise identically, and the indexer associates the
report with whichever it happens to resolve.

**The value is canonical JSON on one line, not a delimited pair list.** `key=value` joined by `;`
was the obvious form and it cannot round-trip its own inputs: knob values are free text — `viewer.js`
sends the control's contents verbatim — so a label of `a;knob.color=red` serialises identically to
two separate overrides, and the indexer resolves the issue against a frame nobody reported. Escaping
rules would fix it and are one more thing two implementations get subtly different. So the value is
a JSON object with **keys sorted by Unicode code point** and no insignificant whitespace, which
escapes separators, quotes and newlines by construction and is *the same encoding §4 already stores
in the record* — one representation, not a text one and an object one. The line is **always
present**; `{}` means the default render, so an absent line and an empty one are never two spellings
of one state. Parsed back it must yield exactly the object §4 compares, and the emitter produces
both from one `Context` so they cannot drift.

Fenced rather than an HTML comment: a comment is invisible in the rendered issue, and a reporter
editing the body has no way to see they have broken it. A fenced block is visible, copy-pasteable,
survives edits, and is trivially recoverable by the indexer. The prose table `ServeIssueReport.body`
already writes stays as-is — the block is *additional*, and the two are generated from one `Context`
so they cannot disagree.

**Labels stay low-cardinality**, exactly as the epic specifies: `area:{spec,component,preview,
renderer,comparison}` and `parity:{regression,known-difference,verification-needed}`. No label per
component — component identity lives in the locator block.

### Which fields may be blank, and which may be absent

Three rules that look like validator trivia and are not: each one is a shape the **writer**
(`ServeIssueReport.locatorBlock`) can legitimately emit, and each was refused by the **producer**
(`parseLocator`) — which drops the whole issue from the index, so the report the reporter filed is
simply not there.

- **`revision` is optional.** It comes from the session's delivery provenance, and a session that
  has none — a developer's local `compose-preview serve` over a bundle directory, or a live daemon —
  omits the line. That is the session a developer reports *from*. The index carries no revision
  column either, so requiring it never bought anything.
- **`variant` is always present and may be empty.** `ServeIssueReport.variantFor` returns `""` for a
  preview id carrying no `__` axes. "No axes" is a fact about the preview, not a mangled body. Every
  *other* field must be non-blank: an emptied `system` or `preview` means the block no longer names
  one component, and that is a mangled body.
- **`element` and `bounds` are absent or non-blank.** Optional fields, and independently so: most
  blocks carry neither, a report that named an element without dragging a region carries only
  `element`, and a tag whose every carrying node had a zero-area box is one the index itself gives
  no bounds for. A *blank* one is a mangled body rather than an absent field, as is an `element` that is
  not a canonical JSON string (a bare tag, or `""`). A `bounds` naming any space
  other than `render-pixels`, carrying a non-integer or negative extent, a zero width or height, or
  keys in insertion order rather than code-point order, is refused rather than stored as a guess.
- **`overrides` keys sort by Unicode code point, not by UTF-16 code unit.** The distinction is
  invisible until a key is astral-plane: JavaScript's default `Array.prototype.sort` orders
  surrogates (`D800`–`DFFF`) below `E000`–`FFFF`, so a canonical block written by
  `ServeIssueReport.canonicalOverrides` came back re-serialised in a different order and was refused
  as "not canonical" — a cross-engine disagreement manufactured by the validator itself, on a body
  nobody had touched.

**The fixture is the enforcement, not this list.**
[`scripts/design-artifacts/fixtures/parity-locators.json`](../../scripts/design-artifacts/fixtures/parity-locators.json)
carries each shape once and is read by *both* engines: `ServeIssueReportTest` asserts the writer
emits those exact bytes, and `parity-issues.test.mjs` asserts the producer parses them back. Cases
with no `writer` key are shapes no writer emits and the producer must keep refusing. Without one
file both sides read, each engine only ever tests itself against itself — which is precisely how all
three rules above came to be broken while every test in both languages passed.

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
- **An absent locator is not a failure.** A repository is mostly ordinary issues — a dependency
  dashboard, a docs nit — and none of them carry a locator block. The producer skips those silently
  and reserves its non-zero exit for a locator that is *present and broken*. Conflating the two made
  the emitter red on a healthy repository, which is a good reason for a catalog never to adopt it.
  The one absent-locator case still worth a warning is an issue carrying an `area:` or `parity:`
  label: that is a parity report filed without its identity, and a human has to go add the block.
  **"Absent" means no opening fence, not no complete fence.** A block whose closing ``` was deleted
  matches the full-fence pattern zero times and is otherwise indistinguishable from an ordinary
  issue — which would send a real, damaged report down the silent path and let the run go green
  without it. The opener is matched separately and anchored to a line, so a body that merely names
  the fence in prose still counts as absent, while an unterminated one — and a good block trailed by
  a dangling opener — is reported. Both patterns tolerate the **one to three leading spaces**
  CommonMark allows, because a block pasted inside a list item renders as an ordinary fence on
  GitHub and a column-zero-only pattern would read a plainly visible locator as absent. Four spaces
  is an indented code block, not a fence, so the marker is literal text and there is no locator.

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

**The dispatch solves the inbound half; the catalog still has to read those issues.** Installing the
App on the catalog repo lets a source repo *wake* the regeneration, but the regeneration then queries
that source repo's issues — and the catalog workflow's own `GITHUB_TOKEN` is catalog-scoped by the
same rule that started this, while the source's installation token does not travel with a
`repository_dispatch`. A private or permission-restricted source repo therefore contributes no rows,
on the dispatch path *and* on the daily reconciliation, and the index quietly under-reports rather
than failing. Two ways to close it, and they are not equivalent: give the catalog side a credential
with **Issues: read** on every scanned repo (works for both paths, and is more provisioning), or
have the dispatch **carry the changed issue in its payload** so the low-latency path needs no
cross-repo read at all (cheaper, but the cron backstop still needs the credential, so it only
narrows the window rather than removing the requirement).

**Cross-repository triggers.** One delivery branch can carry issues from several repositories (a
catalog whose components are implemented elsewhere). `repository` is per-issue in the schema, so the
*format* handles it — but a workflow triggered only by `issues:` events in the catalog repo never
wakes when someone closes or relabels an issue in one of the other scanned repos, so those rows go
stale indefinitely. Telling the emitter which repos to scan is necessary and not sufficient. Every
configured source repo needs either a `repository_dispatch` into the catalog repo from its own
`issues:` workflow, or the whole thing needs a scheduled reconciliation pass as a backstop.
**Recommended: both** — dispatch for latency, a daily cron for the repos nobody remembered to wire
up. A reconciliation pass is cheap here precisely because regenerating the index costs no render.

**The dispatch leg needs a credential, and the default one won't do it.** A source repo's automatic
`GITHUB_TOKEN` is scoped to that repo and cannot create a `repository_dispatch` event in the catalog
repo, so the low-latency half fails closed unless something is provisioned: a GitHub App (preferred
— scoped, rotatable, no human owner) or a PAT with `repo` on the catalog repo, distributed to each
source repo as a secret.

**Store App credentials and mint the token per run — do not store the token.** An installation
access token expires after an hour, so provisioning *it* as a repository secret produces a trigger
that works in testing and is silently dead the next morning. What each source repo stores is the App
id and private key; each workflow run exchanges them for a fresh installation token (the standard
`actions/create-github-app-token` step) — **passing that action's `owner` and `repositories` inputs
to name the catalog repo.** Its default is to scope the token to the repository it runs in, which is
the source repo, where the App is not installed and which is not the dispatch target; taking the
default either fails while minting or yields a token that cannot dispatch. The setup example must
carry those two inputs, because the default is the thing that looks right and silently is not.

The PAT alternative is the one credential that *is* durable
as a stored secret, which is its only advantage over the App.

**Name the App's target and permission explicitly in the setup doc**: the App must be installed *on
the catalog repo* and its installation token needs **Contents: write** there, because that is what
`POST /repos/{owner}/{repo}/dispatches` requires. An App carrying the more natural-sounding
read-only Contents permission returns 403 and the trigger is silently dead — which looks exactly
like "no issues changed". That provisioning is
per-source-repo setup work and is the reason the cron backstop is not optional: it is what a source
repo falls back to when nobody has wired its credential yet, which will be the normal state for a
while.

Both the serve host and the design-parity CI run read the same file. Neither ever calls the GitHub
API at page-render time — same rule that keeps the host away from Figma.

### The pilot population, measured

*Phase 1 is live.* `m3-catalog`'s caller ([yschimke/m3-catalog#170](https://github.com/yschimke/m3-catalog/pull/170))
publishes the index on every issue event, and as of 2026-08-22 `parity/issues.json` carries three
rows — #40 `IconButton/Tonal`, #41 `NavigationBar/Short`, #87 `Checkbox/Checked` — backfilled with
ids read out of the published `references/index.json` rather than guessed.

Three, out of a known-difference backlog of ten. None of the other seven was skipped for effort, and
the reasons are worth having on the record before §4 freezes a schema around this population. Two of
them are limits of the locator; the third is a triage judgement, and the table keeps those apart
because they lead different places.

| Issue | Subject | Backfilled? | Why |
| --- | --- | --- | --- |
| [#40](https://github.com/yschimke/m3-catalog/issues/40) | `IconButton/Tonal` glyph colour | **yes** | one component, one preview, a 23.6% pixel delta inside the glyph |
| [#41](https://github.com/yschimke/m3-catalog/issues/41) | `ShortNavigationBar` measures items at full bar width | **yes** | one component, one preview (`__compact`) |
| [#87](https://github.com/yschimke/m3-catalog/issues/87) | `Checkbox` box padding 2dp vs 4dp | **yes** | one component, one preview |
| [#42](https://github.com/yschimke/m3-catalog/issues/42) | Elevated shadow level | **expressible since the erratum** | names **three** components — `Button/Elevated`, `Card/Elevated`, `ToggleButton/Elevated` — so its body carries three blocks and the index three rows. Three acceptance sites too |
| [#91](https://github.com/yschimke/m3-catalog/issues/91) | no hover/press state drawn | no | **five** components, and the variants it is about are deliberately *not authored* — there is no preview id to name |
| [#85](https://github.com/yschimke/m3-catalog/issues/85) | `DropdownMenu` corner 4dp vs 16dp | no | the catalog publishes **no menu component**: no `images/menu-*`, no reference, no preview |
| [#95](https://github.com/yschimke/m3-catalog/issues/95) | menu container colour and item icon size | no | same — the subject is not in the bundle |
| [#86](https://github.com/yschimke/m3-catalog/issues/86) | expanded full-screen search corner | no | `Search/Bar` publishes `default`, `query`, `avatar` and `container-docked`; the full-screen view is not among them |
| [#89](https://github.com/yschimke/m3-catalog/issues/89) | no slider size scale on `SliderDefaults` | not yet — **a choice, not a limit** | the size previews exist and score **98.1–99.5%**: the catalog transcribed the kit's numbers by hand, so a locator would name a comparison that matches. Indexable whenever we want it on the page; what it cannot have is an acceptance |
| [#93](https://github.com/yschimke/m3-catalog/issues/93) | no `ButtonDefaults.SmallContainerHeight`, no default FAB icon size | not yet — **a choice** | same missing-constant shape and no pixel delta; spanning `Button/*` and `Fab/*` stopped being a limit once a body could carry a block each |

Two limits and one judgement, and only the last is about masks:

1. **One issue, several components** (#42, #93, and #91 as well as its other problem). *Solved by
   the erratum, and left here because it is what the measurement found.* The locator carried exactly
   one `component` and one `preview` and the index row was keyed by issue number, so an umbrella
   report could join to at most one component page. A body now carries one block per component and
   the index a row per block. `previewIds` / `referenceIds` are arrays on the row, which suggests the shape
   was anticipated; the *writer* has no way to fill them. Note that `issuesForPreview` already joins
   on `component` as well as on the exact preview id, so an indexed issue surfaces on **every**
   preview of its component — the gap is across components, not across variants.
2. **The subject is not in the catalog** (#85, #95, #86 — and #91). A parity report is about a
   published render compared with a published reference. Three of the ten are about components the
   catalog does not draw, which makes them spec/library findings the index has nothing to attach
   them to. **#91 belongs here more than in (1)**: splitting it per component would not help,
   because the interaction variants it is about are deliberately unauthored, so after the split each
   piece still has no `preview` to name. They are not badly written issues; they are outside what a
   *preview*-keyed index can hold.
3. **Nothing to accept** (#89, #93) — a judgement rather than a limit, and worth stating precisely
   because the two are easy to conflate. `buildIssueIndex` asks for a well-formed locator and
   nothing else; it never inspects a pixel. Both issues have published previews with scored
   references, so either could carry a locator today and would then show on its component's page
   like any other — #89 cleanly, #93 only by choosing one of `Button/*` and `Fab/*`, which is
   limit (1) again. What neither can have is an **acceptance**: they describe a constant the library
   does not publish, and the renders already match. Indexing them is a question about whether an
   upstream-ergonomics report belongs on a component page. §4's population is the smaller number
   either way: **six issues can carry a locator (#40, #41, #87, #89, and — since the erratum — #42
   and #93), and four are acceptance candidates across six sites (#40, #41, #87, and #42 three
   times)** — different counts answering different questions, and not even the same issues.

**What this means for §4 — and note that §4 still counts differently from §3.** An index row is one
issue on one component; an acceptance is one preview, and §4's closure rule is built on the fact
that **several acceptances may point at one tracking issue** ("mandatory per acceptance but not
*unique* to one"). The erratum aligned the two for #42 — three blocks, three rows, three acceptances
(`button-elevated`, `card-elevated`, `togglebutton-elevated` at 81.2%, 87.0% and 81.5% published
match), closable only once all three resolve — but the counts still differ, because an issue can be
indexable with nothing to accept (#89, #93) and an acceptance is per *preview* rather than per
component. The acceptance population is **four issues and six sites** (#40, #41, #87, and #42 three
times) against six indexable issues.

The model is designed around #40 — a small mask over one element, a recorded accepted-candidate
crop, the gates of the evaluation order — and #40 fits it exactly. But of those six sites only #40's
is glyph-sized: #41 is a layout failure whose mask is most of the bar, #87 a 2dp ring around a 20dp
box, and #42's three are a shadow that surrounds each component.

A component-sized mask is **not** an ignore rectangle. An earlier draft of this section said it was;
the evaluation order says otherwise. Gate 3 compares the current candidate with the immutable
`accepted-candidate.png` *inside* the mask, so a missing glyph, a geometry shift or an unrelated
regression in there invalidates the acceptance as `candidate-changed` before it suppresses anything,
and gate 1 does the same for a changed reference. What degrades at that scale is subtler, and it is
the actual open question:

- **The acceptance stops meaning "this element differs" and starts meaning "pin this render".**
  Every gate still fires, but inside the mask the thing being compared is the component's own past
  self. The reference does no work there any more — including when a render change moves *towards*
  it.
- **Gate 4 has nothing to bind to.** A whole-component mask has no distinguished element, so the
  acceptance falls back to geometric and loses the gate that separates "the glyph disappeared" from
  "the glyph is still the wrong colour" — the one §4 calls load-bearing.
- **The per-pixel tolerance covers thousands of pixels instead of forty.** Capped at `8` and
  per-pixel rather than aggregate, so it is bounded either way, but the surface it applies to is two
  orders of magnitude larger.

***Settled: a geometric acceptance is allowed, and an element gate is preferred wherever an element
exists.*** Requiring the gate outright would make #41 inexpressible until the bar's parts are
tagged, which puts Phase 2's tag work in front of Phase 3 and leaves the pilot with #40 and #87
alone. So `v1` permits an acceptance with no `element`, at the price the three bullets above
describe — it re-invalidates on every render change, and its churn is the cost of being able to
express #41 at all. An acceptance that *can* name an element must: the gate is what separates "the
glyph disappeared" from "the glyph is still the wrong colour", and dropping it when it is available
buys nothing. Refusing masks above a coverage fraction was the third option and was rejected as
exactly the global threshold the epic's non-goals rule out.

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
`{x, y, width, height}` box, without which the plane gate cannot be evaluated at
all; **the element's authoring-time bounds** in canonical-plane coordinates **and its
`element.tolerance`**, when the acceptance names an `element` (both live *inside* the `element`
object — see its wire shape below, not as top-level fields); **`candidateTolerance`**, the
per-pixel threshold the candidate gate compares against; and free-text `note` + `acceptedAt`.

**`candidateTolerance` is bounded, and an out-of-range value is `refused`.** It is the one field an
author can use to defeat the entire model: set it to the maximum channel distance and every future
candidate matches `accepted-candidate.png`, so the mask suppresses a missing glyph or any other
regression forever — precisely the ignore rectangle the non-goals rule out, reached through a
number rather than a shape. So the schema constrains it to a small finite range, and anything
outside that is `tolerance-out-of-range`, refused at validation. A tolerance that needs to be large
is evidence the acceptance is wrong, not evidence the bound is.

**`v1` fixes the range at `0 ≤ candidateTolerance ≤ 8`**, in 8-bit channel distance, integer. Named
here rather than deferred to Phase 3 for the reason the budget caps are named: a ceiling each engine
picks for itself is not a ceiling, and a record legal to one consumer and refused by another is the
one outcome this contract exists to prevent. `8` is the defensible upper end — the only real source
of slack is the single resample into the canonical plane (PNG round-tripping is lossless), and `8`
sits comfortably below the `LUMA_TOLERANCE = 16` at which the existing scorer already stops charging
for a pixel at all. A tolerance above that would let an acceptance suppress differences the scorer
itself would have called free. `2` is a sensible value to author with. As with the budget caps the
bound is inclusive — `8` is legal, `9` refuses — and the fixtures pin both.

**`candidateTolerance` is a field for the same reason `element.tolerance` is.** The candidate gate
needs *some* slack for PNG round-tripping and the resample, and two engines choosing their own
constants would disagree at the boundary — the one thing that must not happen. Recording it means
both read one number off one artifact. The **metric** it applies to (which channels, compared how,
and whether a count of over-threshold pixels or any single one trips the gate) is not settled here;
it belongs with the pixel semantics in the open problems above, and the fixtures must pin cases on
both sides of whatever boundary Phase 3 picks.

**An earlier draft also carried an optional structured `finding` matcher** — `{ kind: "color",
token: …, expected: …, actual: … }` — for the design-parity checks that are not pixel comparisons.
It is **cut from `v1`**, for the same reason `kind: producer` was: every gate in this contract
assumes a mask and an accepted-candidate raster, and nothing here says what matching such a finding
would mean (complete object equality? selected fields? which fields?) or what it would suppress. A
field an offline consumer must guess the semantics of is worse than an absent one, because two
consumers will guess differently and both will believe they implemented the contract. Non-pixel
findings deserve their own evaluation path and their own conformance cases; that is a deliberate
`v2` conversation, not a spare field in `v1`.

**The element's baseline bounds are a required field, not derivable.** The element gate invalidates
when a resolved element has moved "beyond tolerance" — which needs something to measure *from*, and
the evaluator otherwise holds only the element's *current* bounds. The mask is not a usable stand-in:
a mask commonly covers one part of the selected element (the glyph inside the button), so treating
its bounding box as the element's baseline would report movement for an element that never moved,
or miss movement smaller than the slack between them. Record the bounds at authoring time, in the
canonical plane so they survive device-size changes.

**The tolerance is a recorded field too, not a constant each engine picks.** `element.tolerance`
lives inside the acceptance's `element` object — one canonical path, not a top-level sibling — so
both engines read one number off one artifact and cannot disagree at the threshold, which is the
actual requirement; *which* number it is matters far less than that
there is exactly one of it. The contract fixes everything around it: the fraction is relative to
the element's **smaller baseline dimension**, it is compared against the **maximum of the four edge
displacements** between baseline and current bounds, and the comparison is `>` so a displacement
exactly at tolerance passes. Those are the parts two implementations would otherwise choose
differently. `0.1` is a sensible value to author with.

It is **bounded and non-negative**, for exactly the reason `candidateTolerance` is: a large enough
fraction disables the movement gate, letting a uniquely tagged element drift far from its authoring
bounds while the acceptance keeps suppressing the old region's pixels. Both tolerances are
author-controlled numbers that quietly widen what an acceptance covers, so both are range-checked
and both refuse with `tolerance-out-of-range`.

**`v1` fixes that range at `0.0 ≤ element.tolerance ≤ 0.25`**, and it is named here for the same
reason the candidate ceiling is. `0.25` is where the gate stops meaning anything: every edge may
then move by a quarter of the smaller baseline dimension, so the whole element can translate by that
much and still be judged to have stayed put — a 16 px icon that slid 4 px is not the element the
mask was authored over. Inclusive again, so `0.25` passes and anything above it refuses, with a
fixture on each side.

A fraction rather than an absolute pixel count because an absolute tolerance means
something different for a 16 px icon than for a 300 px card.

**The mask must be hashed, not just referenced by path.** The mask is the thing that decides *what
gets suppressed*, so an edited or swapped `mask.png` with an unchanged JSON record silently widens
the accepted region — hiding regressions the record still claims are in scope, with no invalidation
anywhere. That is a direct breach of the safety requirement the whole model exists for. A mask whose
bytes don't match its `maskSha256` is a **hard validation failure** (the acceptance is refused
outright and reported), not an invalidation that degrades to "compare normally" — a mask we cannot
trust is a broken artifact, not a stale one.

**`acceptedCandidateSha256` is checked the same way, and for a sibling reason** (invariant I7 of the
pipeline). The mask decides which pixels are suppressed; the accepted candidate decides what those
pixels are permitted to look like. Leaving either unverified lets an edited artifact redefine what
"accepted" means without any record changing — so both are validated before decode, and both refuse
rather than degrade.

The schema is defined **here**, in this repo, because `serve` is a consumer and this is where the
other wire contracts (`compose-preview-references/v1`, `compose-preview-annotations/v1`,
`compose-preview-activity/v1`) already live. `design-parity` and `@design-parity/catalog-export` are
the second consumer and the publisher respectively; that is cross-repo coordination and should be
sequenced as such (§6).

### The normative contract

> **This subsection is the single source of truth for how an acceptance is evaluated.** Everything
> after it in §4, all of §5, and Phase 4 in §6 are *rationale and consequences* — where they appear
> to say something different, this wins. It is separated out deliberately: earlier revisions of this
> document restated the pipeline, the selector rules and the invalidation list in several places,
> and every one of those restatements eventually drifted out of step with the others.

**Evaluation, as ordering constraints rather than an algorithm.** Earlier revisions of this section
spelled out a numbered pixel-level pipeline. That was a mistake, and the mistake is instructive
enough to record: a planning document cannot validate a pixel algorithm, and successive review
rounds found real defects in every version of it — a gate placed before the data it reads existed,
a resample that mixed what a previous step had just separated, a delta computed in the wrong
direction, a scorer description that turned out not to match `scorePlanes` at all. Each fix was
correct and each introduced the next defect, because there was no implementation and no fixture to
check any of it against.

So what belongs here is the part that *is* a design decision — the constraints any correct
implementation must satisfy, and why — with the algorithm itself as a Phase 3 deliverable validated
by the conformance fixtures. Stated as invariants:

| # | Invariant | Why it is not negotiable |
| --- | --- | --- |
| I1 | Every gate resolves before any score is computed | Excluding coordinates changes the neighbourhood search nonlinearly; a mask found invalid later cannot have its suppression subtracted back out |
| I2 | Each gate runs at the earliest point its inputs exist — no earlier | `reference-changed` is metadata; `plane-changed` needs decoded pixels because `contentBox` samples them; the element gates need the semantics tree |
| I3 | Masked and unmasked regions stay separate through **every** resample | Once a kernel averages across a mask edge the contributions are mixed irreversibly |
| I4 | Separation applies to **both** inputs | `scorePlanes` is bidirectional: a contaminated *reference* sample can erase a *candidate* regression |
| I5 | Gates run per acceptance; scoring runs against the union of **survivors** | Separating against the union up front lets an invalidated mask keep suppressing; combining per-acceptance planes is not equivalent to filtering against the union at their boundaries |
| I6 | Raw and unaccepted traverse **identical** resampling stages | Filtering is not associative, so a shortcut path makes raw ≠ unaccepted even with no surviving mask, manufacturing a delta out of nothing |
| I10 | Scoring resamples **once, source → score plane**, for both passes, at the **candidate box's** dimensions scaled to `MAX_SIDE`; the canonical plane is for the **gates**, not the score | `scoreImages` draws each original straight into the score plane and its own comment pins the single-resample geometry. Routing the score through canonical would change every catalog's number *structurally*, on top of whatever the portable kernel already changes — see the rebaseline note below |
| I7 | Both artifact hashes are verified before their bytes are used | The mask decides which pixels are suppressed; the accepted candidate decides what they may look like. Either one edited silently redefines "accepted" |
| I8 | Every coordinate transform is stated, in both directions | Baselines are canonical-plane; `boundsInRoot` is render pixels; a drag is display pixels — mixing them invalidates unchanged elements or passes moved ones |
| I9 | The **recorded** plane discriminant and box define the canonical destination, for masks, transforms and resampling alike | `normalisedBoxes` falls back to the full canvas below `MIN_BOX_COVERAGE`, so a full-canvas acceptance resampled against a content box suppresses the wrong pixels and invalidates as `candidate-changed` for no real reason |

**Open problems Phase 3 must resolve.** These are the things the review rounds proved cannot be
settled by prose here. They are listed because finding them was expensive and forgetting them would
be worse than leaving them open:

1. **The portable pixel path** — kernel, rounding, edge handling, channel/alpha/premultiplication
   and gray-projection semantics, and content-box sampling, which currently reaches its verdict
   through a host `drawImage` downscale and so can differ per engine.
2. **Mask participation in `edgeMask`** — the scorer classifies edges from raw neighbour values with
   no notion of validity, so whatever fills a separated region can manufacture or suppress an edge
   at the boundary, which decides whether a neighbouring pixel gets the displaced search at all.
   Excluding masked coordinates as *sources and search candidates* is not sufficient.
3. **The masked pass's denominator** — dividing by the full plane versus by remaining scorable
   coordinates gives different numbers, and the all-masked case needs a defined result.
4. **What "accepted contribution" means** — it is *not* a simple difference of the two scores. Under
   a scorable-coordinate denominator the unaccepted mismatch can legitimately exceed raw (a small
   accepted delta removed from a badly-regressed image raises the average), so the subtraction goes
   negative while the acceptance is perfectly valid. Either define it as a signed score *effect*, or
   report the accepted region's own regional mismatch instead of presenting a difference as an
   additive contribution. The current text's claim that a valid acceptance necessarily raises
   similarity is false.
5. **Sub-pixel geometry** — element-bounds tolerance and mask-edge alignment both need defined
   rounding, at each transform.
6. **The match metric**, shared by the candidate gate and the resolution test — which channels,
   compared how, against what threshold, and what happens at the mask edge. The two must use the
   same one (see the status table) or they can disagree about whether two images match, but *which*
   one is a Phase 3 choice for the same reason the kernel is.

The gates and their invalidation causes below are design decisions and do stand as written; it is
the pixel mechanics above that are deferred.

**Selector contract.** An acceptance's `element` carries an explicit `kind`. **`v1` defines exactly
one identifying kind**, deliberately:

| `kind` | Resolves by | Ambiguous when | Notes |
| --- | --- | --- | --- |
| `tag` | the `testTag`, matched anywhere in the tree | the tag is carried by more than one node | the ancestor path is irrelevant — the resolver never walks it |
| *(absent)* | — | — | geometric acceptance: the mask alone, no element gate |

**The wire shape, spelled out**, because "resolves by the `testTag`" says how to match without
saying where the value lives — and a producer emitting `element.testTag` against a consumer reading
`element.value` fails in a way no amount of resolver agreement fixes:

```json
"element": {
  "kind": "tag",
  "tag": "iconbutton-tonal-glyph",
  "bounds": { "x": 24, "y": 24, "width": 24, "height": 24 },
  "tolerance": 0.1
}
```

`kind` is the discriminant; `tag` carries the value and is required when `kind` is `tag`; `bounds`
are the authoring-time baseline in canonical-plane coordinates; `tolerance` is the element movement
tolerance described above, in `[0.0, 0.25]`. An acceptance with no `element` key at all is the
geometric case. The fixtures pin this exact shape, not just the resolution behaviour — a schema two
producers serialise differently is not a schema.

An earlier draft also allowed a `producer` kind for a producer's own identity scheme. It is cut from
`v1` because nothing can currently carry it: the annotation prerequisite in §5 adds `testTag` and
the semantics `ref`, and `DesignAnnotation` has no producer-identity field — so an element selected
in the comparison UI would have no way to persist the id such a resolver is meant to match. A
selector kind with no authoring path is a capability on paper only, and worse than absent, because
it reads as available. Adding it later is a `kind` enum addition plus a wire field on
`compose-preview-annotations` and a projection path from the producer — do that work deliberately if
a producer needs it, rather than reserving the slot now.

**Uniqueness is evaluated against the full `ComposeSemanticsPayload`, never the annotation layer.**
`ServeDesignAnnotations.annotations` emits nothing for a node that resolves neither typography nor
container tokens, so a duplicate-tagged node with neither is *invisible* there — the uniqueness
re-check would pass on a tree that is genuinely ambiguous. Do not count what the overlay happens to
show.

**Which means the browser cannot run the element gates today, and that is a hard prerequisite.**
`handleReferenceComparison` hands the page `referenceAnnotations` and `actualAnnotations` and
nothing else, so `format-compare.js` has no tree to count tags in — and §5's prerequisite adds
semantics-*derived annotations*, which is the very projection that drops the nodes the check needs.
Enabling element gates in the browser therefore requires transporting something more, **before**
Phase 3 turns them on.

The full `ComposeSemanticsPayload` is the obvious candidate and probably the wrong one: it is large,
it is mostly irrelevant to this check, and it would ride on every comparison page load. The gate
needs exactly two things — whether a tag is unique tree-wide, and the current bounds of the node
carrying it. So the leaner contract is a **tag index**: `{ tag: { count, bounds } }` computed
server-side from the payload and embedded alongside the annotation payload. It answers
`element-ambiguous` from `count > 1` and `element-moved` from `bounds`, is a few hundred bytes, and
keeps the authoritative counting on the side that already holds the whole tree. Either way the
decision has to be made and the transport built before the gates can be trusted; an element gate
that silently cannot see duplicates is worse than no element gate, because it reports confidence it
does not have.

**Gates.** All five run before scoring. Any one of them invalidates the acceptance, which then
suppresses nothing and is surfaced as needing review:

| Cause | Condition |
| --- | --- |
| `reference-changed` | served reference `sha256` ≠ recorded `referenceSha256` |
| `plane-changed` | recomputed plane discriminant or resolved box ≠ recorded |
| `candidate-changed` | canonical candidate inside the mask ≠ `accepted-candidate.png` within tolerance |
| `element-ambiguous` | selector resolves to more than one node (per the kind's rule above) |
| `element-moved` | selector resolves to **nothing** (always evaluated); or — **when exactly one node matched** — its indexed bounds are missing, malformed or zero-area, or its displacement exceeds tolerance |

A mask whose bytes do not match `maskSha256` is not an invalidation at all — it is a **hard
validation failure**: the acceptance is refused and reported, because a mask we cannot trust is a
broken artifact rather than a stale one.

**The score plane's dimensions come from the candidate box** —
`scale = min(1, MAX_SIDE / max(candidateBox.width, candidateBox.height))`, applied to
`candidateBox`, exactly as `scoreImages` does today. This is normative rather than an open choice,
and the two facts are linked: I10 promises
the raw number does not move, and picking the reference box instead would move it for every pair
whose boxes differ in aspect. An earlier revision left this to Phase 3 and separately claimed the
canonical plane governed; both are wrong for the same reason.

**The portable kernel and the legacy number cannot both survive — and the kernel wins, once,
deliberately.** These two requirements are in genuine tension, and an earlier revision asserted both:
the portable path replaces the host `drawImage` filter so two engines agree, while I10 was written
as though the raw score would stay byte-identical to today's. It cannot. Any kernel that is not
`drawImage` produces different pixels, so the number moves the moment the portable path ships,
acceptance or no acceptance.

What I10 actually buys, and all it buys, is that the **geometry** stays one resample from source to
score plane at the candidate box's dimensions — so the number does not move *a second time*, and
raw and unaccepted stay comparable to each other. The kernel change is a **one-off, versioned
rebaseline**: bump the score's schema version, regenerate any committed baselines in the same
change, and say so in the release notes. Pretending continuity here would be the worse outcome,
because the drift would show up later as unexplained score movement that nobody could attribute.

**The gate path and the score path are separate, and only the gates use the canonical plane.** Gates
compare at canonical resolution because that is where a glyph is still a glyph and where the mask
and accepted candidate are stored. Scoring does not: it draws each region straight from the source
image into the score plane, one resample, exactly as `scoreImages` does today (I10). What that
preserves is the **geometry**, not the value: enabling acceptance must not move a score by itself,
and both passes stay on identical stages, which is what I6 actually requires. The *number* still
shifts once when the portable kernel replaces `drawImage` — see the rebaseline note above; these two
statements are only compatible if this one is read as being about geometry alone.

**Scope matching uses every recorded field.** A comparison page is keyed by `(previewId,
referenceId)` and it is tempting to scope acceptances the same way, but the record stores the full
§2 locator for a reason: one source repo can publish several systems, and served preview and
reference ids are only unique *within* a system. Matching on the page's key alone lets an acceptance
authored against `wear-m3` suppress pixels in `m3` because both happen to publish
`iconbutton-tonal__ideal__default__light` — a mask applied to a component nobody accepted anything
for. Every recorded field must match, and `system`/`component` are the two that a
comparison-shaped mental model quietly drops.

**Overrides are part of the scope, and this is where that is settled.** The preview id does not
encode them, so a record must carry them explicitly: an `overrides` object of normalised
`key: value` pairs — **the whole map the render lane receives**, with **absent meaning "no
overrides"**, which matches only the default render. Matching is exact over the whole set: an
acceptance authored at `fontScale=1.5` does not apply at `1.0`, and one authored with no overrides
does not apply to an overridden frame.

**The whole map, not an enumerated list of families.** An earlier revision named four —
`uiMode`, `fontScale`, device, locale — as though that were the set. It is not: `viewer.js`'s
`overrides()` also sends the size fields (`minWidthPx`, `maxWidthPx`, `minHeightPx`, `maxHeightPx`),
every checked overlay toggle, and every author-declared knob as `knob.<key>`, with the Remote
Compose lane adding `rc.<name>`. Those change the render — a knob *is* the component's state — so
an enumerated scope lets a mask authored at `knob.label=Send` suppress pixels at
`knob.label=Delete`, which is a different component in every sense that matters. The rule is
therefore structural rather than a list: **every key the render lane accepts is in scope**, because
a key that did not affect the render would not be in the map. A list would drift the first time a
knob family was added, and drift silently, since the acceptance would keep matching.

**`variant` carries axes only; overrides live here and nowhere else.** §2 originally folded active
overrides into `variant`, which would now give the same state two representations with no defined
serialisation between them — a publisher, the browser and the offline consumer could each render a
different `variant` string while their normalised `overrides` objects agreed perfectly, and the
acceptance would miss the frame it was authored for. One fact, one field.

That direction is the safe one. Overrides change layout, and a mask is geometry — an acceptance for
a glyph at one font scale covers different pixels at another, so applying it across scales suppresses
a region nobody looked at. The cost of being strict is a duplicated acceptance per override
combination someone genuinely cares about, which is visible work; the cost of being loose is silent
suppression, which is not. §7 previously left this open with this as the recommendation; the scope
rule above cannot be implemented while it stays open, so it is settled here — reversing it is a
`v1` schema decision, not a free choice.

**Only `valid` acceptances contribute a mask to the scoring union.** `resolved`, `invalidated` and
`refused` all suppress **nothing** — "survivor" means status `valid`, not "reached the end of the
gates". `resolved` is the case worth spelling out, because its candidate gate *did* fire and the
precedence table merely re-labelled the outcome: a resolved region now agrees with the reference, so
it contributes no mismatch to suppress, and keeping it in the union would actively remove its pixels
as neighbourhood candidates for the pixels around it — which can hide a regression sitting next to
the thing that was just fixed. The required fixed-candidate fixture therefore carries an **adjacent
regression** as well, since that is the case the wrong reading gets wrong.

**The mask's encoding is part of the contract, not a producer's choice.** "A PNG" leaves at least
three readings — alpha-vs-luminance coverage, which polarity means masked, and what an intermediate
value means — and two consumers can read the same hash-valid bytes as different suppression unions
while satisfying every invariant below. So: **8-bit greyscale, no alpha, `0` = unmasked, `255` =
masked, and any other value is `refused`.** A strictly binary mask rather than a threshold, because
a threshold is one more constant two engines could pick differently, and an anti-aliased mask edge
is exactly the boundary case the separation rules already work hardest to keep unambiguous. A
producer that has a soft-edged selection must decide where the edge falls before committing it,
which is the right place for that decision to be made.

**And the encoding is checked in the header, not inferred from the samples.** Stating the rule is
not enough on its own, because the browser's only decode path normalises everything to 8-bit RGBA:
an indexed, 16-bit, or RGBA mask whose samples all happen to land on `0` and `255` sails through a
sample-value check while flatly violating the declared encoding — and the offline engine, decoding
natively, sees a different type entirely. Two consumers, same hash-valid bytes, no disagreement
visible anywhere until a mask with a palette entry between the two values arrives. So the same
`IHDR` preflight that yields `width × height` also validates the two bytes after them: **bit depth
must be `8` and colour type must be `0`** (greyscale, no alpha, no palette). Anything else is
`mask-encoding-invalid`, refused, before any decode. The fixtures carry a palette mask and an RGBA
mask, both with strictly binary samples, since those are precisely the files a sample-only check
accepts.

This constrains `mask.png` alone — `accepted-candidate.png` is an ordinary colour raster and carries
no encoding rule beyond decoding to the dimensions it declares. And since the verdict is reached in
the same preflight pass, it lands on the same side of the budget: an acceptance refused for its
mask's encoding contributes neither raster to the running total, exactly as an unreadable header
does. A refusal that excluded the record on one engine and charged it on another would put the
order-dependence back that the whole-record preflight removes.

**Neither artifact may be animated.** An APNG is a PNG: it carries the signature, a conforming
`IHDR` — greyscale and 8-bit if the producer wants the mask to pass — honest dimensions, and a hash
that verifies. Every check in this contract accepts it, and then the two engines read different
pixels out of it, because an animated PNG has two answers to "what does this file contain": the
default image in `IDAT`, which is what a decoding library returns, and the animation, which is what
an `<img>` or `createImageBitmap` may advance through. A mask that changes between frames is a
suppression union that changes while you look at it. So the preflight rejects any file carrying an
`acTL` chunk, for **both** rasters, with `animated-png`. That widens the preflight from "read the
`IHDR`" to "walk chunk *headers* — the length and type of each, never its data — until the first
`IDAT`", which `acTL` must precede: still a bounded read of a fixed number of bytes per chunk, still
nothing decoded. Rejecting is right rather than pinning "decode frame zero":
a static acceptance artifact has no use for frames, so the file is a mistake or an attack either way,
and "we defined which frame counts" is a rule two engines can still implement differently.

**The acceptance set is bounded before anything is decoded.** Every record costs two more raster
decodes plus several intermediate planes on both engines, and a catalog is third-party data — so a
document with a few thousand individually valid, individually small acceptances can exhaust a
browser tab or an offline run long before any per-record refusal has a chance to fire. The existing
catalog fetch limits are per asset and do not see the aggregate. So the document declares out of
budget and is rejected **before** the per-acceptance decode loop begins: a cap on the number of
acceptances, and a cap on total decoded pixels across their rasters, both fixed constants rather
than per-catalog settings — and **named**, because "a fixed constant" that each engine picks for
itself is not fixed. `v1` sets them at **256 acceptances** and **128 megapixels** of declared raster
across the set, plus a **per-axis maximum of 8192 px**; all three are versioned with the schema, and
the fixtures pin a document at each exact
boundary and one past it, since an off-by-one here means one engine evaluates what the other
rejects. The numbers are generous against real use — a catalog with hundreds of deliberate,
reviewed, issue-linked acceptances has a different problem — and deliberately not per-catalog
settings, since a hostile document must not be able to raise its own ceiling.

The count cap is free, but the **pixel** cap needs dimensions the JSON does not record — and reading
them with the ordinary decoder would allocate the oversized raster to measure it, defeating the
protection at the moment it fires. So the budget is enforced after a **bounded header preflight**:
read each PNG's `IHDR` (the first handful of bytes after the signature), take `width × height` from
it, and walk the chunk headers to the first `IDAT` for the animation check below. Bounded throughout
— chunk lengths and types only, never chunk data, and never a decode.

**Compare as you go; never accumulate a total that can overflow.** Summing `width × height` across a
third-party set is exactly where the two engines diverge silently: several PNGs with large but
individually legal dimensions overflow a Kotlin `Int`/`Long` into a negative or wrapped value that
sits comfortably under the cap, while JavaScript keeps a large positive `Number` and rejects — the
offline consumer then proceeds to allocate what the browser refused. So the check short-circuits:
reject the moment **any single dimension exceeds 8192**, **any single `width × height`** exceeds the
pixel cap, or **the running total** does, and stop reading. **Exceeds, not reaches**: a document
exactly at 256 acceptances, exactly 128 megapixels, or exactly 8192 px on a side is legal, which is
what makes the boundary fixtures meaningful — one case sitting on the limit and passing, one a
single unit past it and refused. A `>=` check would reject both and leave the two engines free to
disagree about the case in between.

**The per-axis cap is a separate number because the area cap does not imply it.** A `1 × 128,000,000`
PNG is 128 megapixels exactly — inside the area budget, and no *dimension* is over an area cap, since
comparing a length against a pixel count is a category error that happens to type-check. But no
browser will decode it: canvas and image dimensions are capped well below that (the smallest limit
among current engines is the binding one), so the browser reports `decode-failed` for bytes the
offline decoder evaluates normally. That is the divergence class this whole section exists to
prevent, reached through a shape rather than a size. `8192` is the number because it clears every
mainstream engine's limit with room to spare and is still an order of magnitude above any plausible
canonical plane — a preview render is hundreds of pixels a side, not thousands.

**Encoded bytes are a third limit, and the serving host already has one.**
`ServeCatalogStore.fetchCatalogAsset` caps every fetched catalog asset at `MAX_FETCH_BYTES` — 25 MB
— so a noisy `accepted-candidate.png` that is comfortably inside 8192 px and 128 megapixels can
still be evaluated offline, where it is read off disk, and refused on the serving host, where the
fetch never completes and becomes `artifact-unreadable`. Dimensions do not bound file size: PNG
compression varies by orders of magnitude with content, and an acceptance's rasters are exactly the
noisy sub-regions that compress worst. So `v1` caps each artifact at **8 MiB encoded**, checked from
the byte length the preflight already has in hand, refused as `artifact-too-large` — comfortably
under the host's own limit so the two engines agree well before the host's fetch would fail, and far
above a real mask or crop. Per-artifact rather than per-document, and excluded from the pixel budget
like every other preflight refusal, so the order-independence rule still holds.

Nothing ever holds a value larger than the cap plus one raster, so
there is nothing to overflow, and the two engines cannot disagree about arithmetic they never do.

A file whose header is unreadable, or whose declared
dimensions disagree with what the full decode later produces, is `header-invalid` — the second half
matters because a lying header is otherwise a way to walk straight past the cap.

**Preflight a record completely before it contributes anything.** An acceptance has two rasters, and
if one has an oversized-but-readable header while the other's is unreadable, the outcome would
depend on which was read first — oversized first rejects the document, unreadable first excludes the
acceptance and carries on. Two engines walking the same file in different orders would disagree. So
both headers are read and validated **before** either raster joins the running total: an acceptance
with any unreadable header is refused and contributes nothing, and only fully-preflighted
acceptances are summed. The budget check is then order-independent, which is the property that
actually matters — not which of the two verdicts "wins".

**`header-invalid` refuses that acceptance, not the document.** The preflight is an aggregate, which
makes it tempting to treat a header it cannot read as fatal to the whole file, but the useful
behaviour is the local one: exclude that acceptance and its rasters from the running total — they
will never be decoded — refuse it, and carry on summing the rest. One unreadable PNG then costs its
own acceptance rather than every other acceptance in the catalog. The dimensions-disagree half is
necessarily per-acceptance anyway, since it is only detectable after the budget has already passed.

`document-too-large`
is then checked alongside the duplicate-id scan, both whole-document verdicts reached before any
pixel buffer is allocated.

**The `id` must also be safe as a map key, which is not the same constraint.** `__proto__` is a
perfectly good path segment and a catastrophic object key: `statuses[id] = value` in the browser
mutates the prototype instead of creating the own-property the contract requires, while the offline
map stores it normally — same document, two different results, and duplicate detection breaks the
same way for `constructor`. Two defences, because either alone is brittle: the reserved names
`__proto__`, `constructor` and `prototype` are rejected as `id-not-safe`, **and** the browser builds
`statuses` as a `Map` or a null-prototype object rather than an object literal. The fixtures include
a `__proto__` id so an implementation using `{}` fails visibly rather than silently.

**The `id` must be a safe single path segment, because the artifact directory is derived from it.**
Checking a child path against `.design-parity/known-differences/<id>/` is worthless if `<id>` can
move that directory: an `id` containing `/` or `..` relocates the base, after which `mask.png` is
perfectly "contained" within the escaped location and passes. So `id` is constrained first — a
single segment of `[A-Za-z0-9._-]`, no separators, and **neither `.` nor `..` exactly** — and the
final resolved artifact paths are additionally verified against the **fixed** `known-differences`
root rather than only against the derived directory. Two checks because the id is doing double duty
as an identifier and a filesystem path, and the second is what holds if the first is ever loosened.
`id-not-safe` is its own refusal reason.

**Both dot names, not just `..`.** `.` is the one that reads as harmless: it contains no separator,
every character is in the class, and it is not the `..` everyone checks for — yet
`.design-parity/known-differences/./` normalises to the `known-differences` root itself, so a
`mask` of `some-other-id/mask.png` is genuinely contained within the derived directory and the
containment check passes. One acceptance can then address every sibling's artifacts, and a
`.`-and-`..` pair of ids collides on the same directory while remaining distinct map keys. Rejecting
the two names outright is cheaper than reasoning about what they normalise to; the fixtures carry
an `id` of `.` reaching a sibling's `mask.png`, since that is the case a `..`-only check lets
through.

**Artifact paths resolve against the known-difference directory, and may not leave it.** `mask` and
`acceptedCandidate` are relative to the acceptance's own directory under `.design-parity/
known-differences/<id>/` — not the repo root, not the JSON file's location, not an implicit
`.design-parity/` — because "an ordinary relative path" resolves to three different files under
those three readings and nothing in the artifact says which was meant. Absolute paths, `..`
segments, and anything resolving outside that directory are `path-not-contained`, refused. This is
not a new rule so much as the one `ServeDesignReferenceStore.isSafeRelativePath` already applies to
reference rasters, for the same reason: these paths are read during staging on a host that fetches
third-party catalogs, so a traversal is an escape from the artifact tree rather than a typo.

**Containment is not portability, so the grammar is restricted too.** A path can be perfectly
contained and still name different files on either side of the contract. `isSafeRelativePath`
rewrites `\` to `/` before splitting, so `a\b.png` is *checked* as two segments and *opened* as one
filename on POSIX and as two on Windows. `#` and `?` are ordinary filename characters that become
fragment and query syntax the moment the serving host fetches the artifact by URL rather than
reading it off disk — so the offline engine hashes one file while the host fetches another, or
reports `artifact-unreadable` for a file that is right there. Percent-encoding rules would settle it
and are one more thing to get differently right twice. So the grammar is simply narrow: **segments
of `[A-Za-z0-9._-]` joined by `/`**, the same character class the `id` already uses, with no
backslashes, no `#`, `?` or `%`, and no whitespace. Anything else is `path-not-contained`. A
committed artifact path has no need of the rest of Unicode, and the restriction removes the encoding
question instead of answering it.

**A mask must select something.** An all-zero mask satisfies the encoding and dimension rules and
still has no bounding box, which leaves `accepted-candidate.png`'s required dimensions undefined —
one engine treats it as a harmless no-op, another refuses, a third throws while cropping. At least
one `255` pixel is required; otherwise `mask-empty`, refused, with its own fixture.

**Artifact dimensions are checked against the recorded plane.** `mask.png` must match the recorded
canonical plane's `width × height` exactly, and `accepted-candidate.png` must match the mask's
bounding box exactly. Otherwise one consumer rescales, another rejects, a third compares only the
overlap — same acceptance, three different suppression unions. Mismatches are `refused`, with
conformance cases for both.

**A path that resolves is not a file that opens.** A `mask` or `acceptedCandidate` path can be
contained and syntactically perfect while the file is missing, unreadable, or truncated to nothing —
at which point there are no bytes to hash, no header to parse and no decode to attempt, so none of
the other tokens apply. Left unnamed, the browser turns a failed fetch into a local refusal while the
offline reader throws or silently drops the record. `artifact-unreadable`, refused.

**Strictly a fetch/open/read failure, though.** A file that *opens* and is merely empty or truncated
is not this: the preflight gets its hands on the bytes and finds too few of them for an `IHDR`, which
is `header-invalid`. The line is where the failure occurs, not how little data there turned out to
be — otherwise "truncated to nothing" is describable by both tokens and the two engines pick
differently for identical bytes.

**A correct hash does not make an artifact usable.** Bytes can be committed with a correctly
computed `sha256` and still be corrupt, non-PNG, or decode to zero dimensions — the hash proves
nobody edited the file, not that the file was ever valid. Left undefined, one engine aborts the
whole comparison and another silently drops the acceptance, and neither produces the per-acceptance
status the contract promises. Decode failure and degenerate geometry are therefore `refused` like a
hash mismatch, and a *correctly hashed malformed artifact* is its own fixture.

**An acceptance whose target no longer exists is `refused` with `orphaned-target`.** Everything else
in this contract is evaluated *from a comparison* — some `(previewId, referenceId)` pair a page or a
run opened — so an acceptance naming a preview or reference the catalog has since renamed or removed
is never scoped into any evaluation at all. It produces no status, appears in no dashboard, and
survives every cleanup pass by being invisible to all of them: the failure mode is an acceptance
that quietly outlives the thing it was about. `reference-hash-missing` does not cover it, because
there is no served reference to compare a hash against. So **both** engines additionally walk the whole
`acceptances[]` against the catalog, independent of any comparison, and report the unmatched ones.
Assigning that walk to the offline run alone — as an
earlier revision did — leaves the served dashboard blind to exactly the records it most needs to
show, and makes the two engines disagree about a status the contract says they share: the offline
gate refuses a record the browser never mentions. With a conformance case, since this is the one status
no comparison-driven fixture can produce.

**The walk checks every scope field the catalog can resolve, not just the two ids.** Scope matching
uses the full locator, so *any* recorded field diverging from the catalog makes the acceptance
permanently unreachable — and a walk that only asks "does this preview exist? does this reference
exist?" misses the ones that don't touch an id. A component renamed from `IconButton/Tonal` to
`IconButton/Filled` while its preview and reference ids stay put leaves both lookups succeeding and
the acceptance orphaned anyway, which is the exact invisible-forever failure this rule exists to
catch. So the walk resolves the acceptance's `previewId` **within its `system`**, then requires the
resolved preview's component to equal `component` and its axes to equal `variant`, and requires
`referenceId` to be a reference **attached to that preview** — a reference that exists but now hangs
off a different preview is as unreachable as one that was deleted. Any of those failing is
`orphaned-target`, and the component-rename case is its own fixture, since it is the one an
id-existence walk passes.

The `variant` half of that reads as redundant — §2 derives `variant` from the preview id's own axis
segments, so a resolved preview always has the axes its id spells — and it is included precisely
because the record's copy can disagree with its own `previewId`. That record matches nothing under
full-scope matching either, and a walk that skips the check because "it must agree" leaves the one
case where it doesn't as the invisible kind. Checking a derived field costs a string comparison and
turns a producer bug into a reported one.

**`overrides` is the one scope field the walk cannot check**, and deliberately so: overrides are a
property of the frame a viewer requested, not of anything the catalog publishes, so there is no
catalog fact to compare against. An acceptance naming an override combination nobody has opened is
*unused*, not orphaned — the target is still there — and reporting it as orphaned would flag a
correct record on every catalog. Unused acceptances are a dashboard question, not a validation one.

**A record that violates the schema is refused — and where the failure lands depends on whether it
can be keyed.** Missing required fields, wrong types, an unsupported `element.kind`: none of the
validation conditions so far covers these, so one consumer rejects at deserialization while another
defaults the missing value and proceeds to `valid`, applying a mask whose gate never functions. The
rule mirrors the duplicate-id logic and follows from the same constraint — a result keyed by id can
only report what it can name:

- **`id` present and valid** ⇒ that acceptance is `refused` with `schema-invalid`; the rest of the
  document evaluates normally.
- **`id` missing, malformed, or the wrong type** ⇒ the record cannot be keyed at all, so the
  **document** is rejected, exactly as for a duplicate id.

Both are conformance fixtures, since "one bad record" and "an unreadable file" are different repairs.

**The unkeyable case reports `id-missing`, and that token covers all three of its forms.** It needs
saying, because neither neighbouring token fits: `schema-invalid` is per-acceptance and presupposes
a valid id to key the status by — the very thing that is absent — and `document-unreadable` is about
a file that could not be parsed at all, which this one was. Absent, blank, `42`, an object: all are
`id-missing`, in the `{ "index": …, "reason": … }` shape, since the record's position in
`acceptances[]` is the only handle left. "Missing" names the absence of a usable key rather than a
literally absent field, which is the reading the index-shaped entry already forces.

**`id-not-safe` is the neighbouring case and stays per-acceptance.** Its input is a *present,
well-formed string* — `__proto__`, `a/b`, `..` — which is keyable, so that record refuses alone and
the rest of the document evaluates. The line between the two tokens is whether a key exists at all,
not whether it is a good one. Both sides of that line are fixtures, since an engine that folds them
together either takes down a whole document over one bad name or keeps evaluating a document it
cannot key.

**And the file itself may be unreadable**, which is neither of those: truncated JSON, a wrong schema
token, or `acceptances` that is not an array. There is no record to name and no index to fall back
on, so this is `document-unreadable` in the identifier-less `{ "reason": … }` shape, with `statuses`
absent — the same shape as `document-too-large`, for the same reason. Without a token an engine is
free to simply throw, which is not a result any fixture can compare against.

**Duplicate acceptance ids fail the whole document, not one record.** `statuses` is keyed by id, so
two records sharing one have a single slot between them — and making the duplicate a *per-acceptance*
`refused` status does not help, because that status would need the same colliding key to live in. A
result structure keyed by a non-unique key cannot represent the input at all, which makes this a
property of the **file** rather than of either record: `known-differences.json` is rejected wholesale,
no `statuses` entries are produced, and the failure is reported in `validationFailures` with the
offending id. That is also the honest signal — nobody meant to write two acceptances with one id, so
the artifact is malformed rather than partially applicable.

**One entry per duplicated *value*, ordered by first occurrence.** "Report the offending id" is
under-specified the moment a document has three records sharing an id, or two different ids each
duplicated: one engine emits one entry per colliding value, another one per colliding record,
another one per occurrence after the first — three different arrays for the same rejected file, all
defensible readings of the same sentence. So: **one `{ "id": …, "reason": "duplicate-id" }` per
distinct id that appears more than once**, regardless of how many records carry it, ordered by the
index of that id's **first** occurrence in `acceptances[]`. First occurrence rather than last
because it is the only position every engine has already seen at the moment it detects the
collision. A document with one id used three times and a second used twice is a fixture, since it
separates all three readings at once.

**A reference with no `sha256` is refused for the same reason**, not invalidated as
`reference-changed`. The fingerprint gate compares a recorded hash against a served one; with
nothing to compare, the gate cannot run, and an acceptance whose primary safety check is
inoperable is a broken configuration rather than a stale one. The distinction matters because
`reference-changed` reads as "the design moved" — a fact about the world — while this is "we cannot
tell", which needs a different fix (publish the hash) and a different message. Both belong in the
validation-failure fixtures.

**Hashes are compared normalised, never as raw strings.** `ServeDesignReferenceStore` lowercases a
reference's `sha256` to *validate* it and then serves the original spelling, so an upstream that
published uppercase hex reaches the gate uppercase while the acceptance records the conventional
lowercase. Raw string inequality then reports `reference-changed` — "the design moved" — for a
reference that is byte-for-byte the one the acceptance was authored against, and the fix looks like
re-authoring the acceptance. So both sides are lowercased before comparison (equivalently, compare
the decoded digest bytes). Separately, every hash *this* schema owns — `referenceSha256`,
`maskSha256`, `acceptedCandidateSha256` — must be 64 lowercase hex characters, `schema-invalid`
otherwise: we cannot constrain what a producer publishes upstream, but we can refuse to accept two
spellings of our own fields. An uppercase-served / lowercase-recorded pair is a fixture, since it
passes every other check and fails on the one comparison.

That refusal is the `refused` **status**, so every
acceptance id still maps to exactly one status and the hash-mismatch fixtures have an expected value
like any other case — `validationFailures` carries the detail of *what* failed, `statuses` says
which acceptance it happened to.

**`resolved` outranks `candidate-changed`, and that belongs here rather than in the lifecycle
section.** Every acceptance evaluates to exactly one **status**, and the resolution predicate is
part of this contract because §6 cannot override it:

Evaluated strictly in this order — the first row whose condition holds wins:

| # | Status | Condition |
| --- | --- | --- |
| 1 | `refused` | **any** validation condition holds — either artifact's bytes fail their recorded hash; an artifact is hash-valid but fails to decode, decodes to zero/negative dimensions, carries a non-binary mask encoding, selects no pixels, or does not match its required dimensions; the targeted reference publishes no `sha256`; either tolerance is out of range; the `id` is not a safe single path segment; or an artifact path does not resolve inside the known-differences root. The acceptance is never evaluated, and the `reasons` token set below is the complete list |
| 2 | `invalidated: [causes]` | **any gate other than `candidate-changed`** fires — `reference-changed`, `plane-changed`, `element-ambiguous`, `element-moved` |
| 3 | `resolved` | the candidate gate **fired** *and* the masked region now agrees with the **reference** (see below) |
| 4 | `invalidated: [candidate-changed]` | the candidate gate fired and the region did not converge |
| 5 | `valid` | nothing fired |

**`resolved` outranks `candidate-changed` only, and only after the other gates pass** — rows 2 and 3
are in that order deliberately. If the pinned reference changed and the *new* reference happens to
agree with the candidate inside the mask, that is not a resolution: it is an acceptance measured
against a different spec, and closing the issue on it would discard a review nobody performed. The
same holds for a changed plane or an ambiguous element. Only `candidate-changed` is ambiguous
evidence, because it is the one cause the success path necessarily produces.

That is the whole reason the precedence exists. When someone actually fixes the accepted difference,
the region stops matching `accepted-candidate.png` **and** starts agreeing with the reference — "it
was fixed" and "it changed into something else" are the same pixels, and only the reference test
tells them apart. Without this rule a dashboard could report a win while the offline gate failed the
same commit as stale. `resolved` means delete **that** acceptance.

**Issue identity is the canonical `owner/repo/number`, not the URL string.** Acceptances are
hand-authored, so the same issue arrives spelled several ways — a trailing slash, a `#issuecomment`
fragment, `www.`, a mixed-case owner. Aggregating on the raw `issue` string splits those into
separate groups, and a group that looks fully resolved then closes an issue a sibling acceptance is
still holding open — the precise failure the aggregation rule below exists to prevent, reintroduced
by string equality. Parse to `owner`, `repo`, `number` first and aggregate on those, exactly as the
issue index's own trust boundary already validates them; a URL that does not parse is
`schema-invalid` rather than its own group of one.

**Closing the issue is an issue-level decision, not an acceptance-level one.** The tracking issue is
mandatory per acceptance but not *unique* to one — the same glyph-colour delta legitimately spans a
component's light and dark variants, or several previews, as separate acceptances pointing at one
issue. So `resolved` on one of them does not mean the issue is fixed. Aggregate by issue: an issue
is closable only once **every** acceptance linked to it has resolved. Closing on the first resolution
would also be self-defeating, since Phase 4's stale detection (closed issue, live acceptance) would
immediately flag the siblings the closure just orphaned.

**"Every acceptance" is every acceptance *anywhere*, and one run cannot see that far.** An
evaluation reads one `known-differences.json`, in one source repo; the workflow explicitly supports
many source repos and many catalogs, and nothing stops two of them from filing against one issue —
the same upstream component bug reported from two catalogs is the *normal* way that happens. Each
run then sees its own records all resolved, concludes the issue is closable, and closes it out from
under a live acceptance in a document it never opened. There is no global acceptance inventory in
this plan, and inventing one is a much larger change than the closure step warrants. So `v1`
constrains the other side: **an issue is owned by exactly one `known-differences.json`**, and the
closing PR (Phase 4 step 12) may only close an issue whose acceptances all live in the document it
is editing. A run that cannot establish that — because it has no way to know what other documents
reference the issue — deletes its resolved records and leaves the issue open for a human, which is
the safe half of the operation and the one that never needs global knowledge.

Nothing offline can *enforce* single ownership, which is worth stating plainly rather than implying:
it is a convention the closing step depends on, and the honest fallback is that closure is a
reviewed PR rather than an automatic action. Aggregating every referencing document before closing
is the real fix and a `v2` conversation — it needs an inventory that does not exist yet.

**`resolved` requires the candidate to have actually changed.** Row 3 is guarded on
`candidate-changed` having fired, which looks redundant and is not: the resolution metric is
permitted to be tolerant, so an *unchanged* candidate can agree with `accepted-candidate.png` **and**
with the reference whenever the accepted delta was itself within that tolerance. Without the guard
such an acceptance is `resolved` the moment it is authored, and the workflow closes the issue before
anyone has fixed anything. The guard also names the real defect in that case — an acceptance whose
stored candidate already agrees with the reference is accepting a difference that does not exist, so
**authoring must reject it** — *and so must the evaluator*, because §7 records that mask authoring
is currently manual, so "authoring rejects it" describes a step that does not yet exist. Left to
authoring alone, such a record is simply `valid` and its mask joins the suppression union, hiding
whatever later appears in that region on the strength of an acceptance that never accepted anything.
The evaluator therefore checks it directly: a stored candidate that already agrees with the
reference under the match metric is `refused` with `acceptance-is-noop`, with its own fixture.

**But only once the fingerprint gate has passed.** The check compares the stored candidate against
the *served* reference, and the reference the acceptance was authored against is not kept — so the
moment the hash differs the predicate is being evaluated against the wrong image. A changed
reference whose new pixels happen to match the stored candidate would then trip the no-op check,
and because refusal outranks everything the result becomes `refused: acceptance-is-noop` instead of
the `invalidated: reference-changed` the contract intends. Sequence it after the fingerprint gate:
if the reference has moved, that is the finding, and nothing about the old acceptance's contents is
knowable enough to say more.

**Ambiguity short-circuits the *bounds* check, not the whole gate.** With several matches there is no
single node whose bounds the index can publish, so `element-moved`'s missing-bounds condition would
fire alongside `element-ambiguous` — and one engine would report both causes while another stopped
at the first. So the bounds and displacement checks run **only when exactly one node matched**, and
a duplicate tag produces exactly `["element-ambiguous"]`.

**The zero-match case is not part of that restriction.** A tag that has disappeared entirely must
still be `element-moved` — that is "the glyph vanished", the case the element gate exists for, and
reading the exactly-one rule as covering it would leave the acceptance `valid` and still suppressing
the pixels of an element that is no longer there. Zero matches is always evaluated; one match gets
the full check; more than one is ambiguous and stops. All three are fixtures.

**Causes are a list, not a single value.** Several gates can fire at once — a changed reference
alongside a tag that became ambiguous — and with a singular `invalidated: <cause>` two engines
would each pick one and report different statuses while both obeyed every gate. So row 2 carries
*every* non-`candidate-changed` cause that fired, in the fixed order above, and a multi-failure case
is a required fixture. Reporting all of them is also simply more useful: an acceptance that is stale
in three ways wants all three shown, not whichever the implementation happened to check first.

**What "resolved" tests, precisely.** The two comparisons in play run against *different* targets,
and conflating them is the easy mistake:

| Test | Compares | Answers |
| --- | --- | --- |
| candidate gate | canonical candidate inside the mask ↔ **`accepted-candidate.png`** | "is it still the difference we accepted?" |
| resolution test | canonical candidate inside the mask ↔ **the reference** | "has it converged on the spec?" |

So `resolved` is *not* "the candidate gate failed in a nice direction" — it is its own comparison,
against the reference, over the masked region only. That much is a design decision and is settled
here. What is **not** settled here is the metric and threshold it uses, or how it behaves at the
mask edge: exact channel equality, `candidateTolerance`, the scorer's `LUMA_TOLERANCE` floor, and a
regional score all classify a near-miss differently, and picking one blind would be the same error
as picking a resampling kernel blind. It joins the pixel-semantics **open problems** above, with one
constraint from this contract: whatever the resolution test uses, it must be the *same* metric the
candidate gate uses, so the two cannot disagree about whether two images match.

**`plane-changed` short-circuits the element gates, which is what makes one index sufficient.** Two
acceptances on the same comparison can record *different* canonical planes — one authored either
side of the `MIN_BOX_COVERAGE` fallback — and a single server-transformed index can only carry
bounds in one of them. But it does not have to carry both: a comparison resolves exactly one plane,
so at most one of those acceptances passes the plane gate, and the other is already `plane-changed`.
Running its element gate against bounds transformed through a plane it was not authored in would
manufacture a false `element-moved` cause on top of a correct `plane-changed` — so once
`plane-changed` fires, the element gates are not evaluated for that acceptance and contribute no
causes. The index is therefore always in the plane of every acceptance still being gated, and the
`causes` list stays comparable across engines.

**The index publishes bounds in canonical coordinates, already transformed.** `boundsInRoot` is
render-pixel space (I8), the fixtures expect canonical, and nothing said who converts — so one
implementation would compare raw bounds against a canonical baseline, another transform once, and a
third double-transform bounds the server had already converted, each giving different `element-moved`
verdicts on the same tree. The **server transforms once**, using the render→canonical row of the
table above, and consumers use the values as-is; a consumer that transforms again is wrong. That
placement follows the same logic as the index itself — the authoritative work stays where the whole
tree and the resolved plane already are.

**The tag index and the scored PNG must come from one render.** Semantics move with overrides,
conditional composition and animation, so an index computed by a different render than the frame
being scored can pass a uniqueness or movement gate that the actual pixels would fail — and let the
wrong mask suppress. This is not hypothetical plumbing: `ServeRenderHost.renderAnnotations` already
renders under `renderLock` before reading semantics *because* the per-preview sidecar is overwritten
by the next render, and the comparison page today embeds static annotation lists while its Actual
panel requests `/render` independently. So the index must be produced by the same override-keyed
render transaction (or cache entry) that produced the PNG the page scores, and Phase 2's transport
work has to carry that coupling rather than just the data.

**The result's wire shape, spelled out.** `invalidated: [causes]` above is table notation, not JSON,
and leaving the map value unspecified means two engines can agree on every verdict and still
serialise different results — which is the one thing a cross-engine contract exists to prevent:

```json
{
  "raw": 90.2,
  "unaccepted": 95.1,
  "accepted": 4.9,
  "statuses": {
    "m3-iconbutton-tonal-glyph": { "status": "valid" },
    "m3-fab-shadow":             { "status": "resolved" },
    "m3-switch-track":           { "status": "invalidated",
                                   "causes": ["reference-changed", "element-ambiguous"] },
    "m3-chip-border":            { "status": "refused",
                                   "reasons": ["mask-hash-mismatch",
                                               "accepted-candidate-hash-mismatch"] }
  },
  "validationFailures": [
    { "id": "m3-chip-border", "reason": "mask-hash-mismatch" },
    { "id": "m3-chip-border", "reason": "accepted-candidate-hash-mismatch" }
  ]
}
```

Every value is an object, never a bare string, so a consumer never has to branch on type. `status`
is one of `valid` / `resolved` / `invalidated` / `refused`. `causes` is present **only** for
`invalidated`, always an array even for one cause, ordered as the gate table lists them — that fixed
order is what makes the multi-cause fixture comparable. `reasons` is present **only** for `refused`, and is an
**array** for the same reason `causes` is — both artifacts can fail at once, and a single-value
field would leave two engines free to pick different ones. Same fixed ordering rule, drawn from:
**the list below, in this order** — it is the authoritative ordering for `reasons` *and* for
`validationFailures`, including when several document-wide failures land together:

`document-unreadable`, `document-too-large`, `duplicate-id`, `id-missing`, `id-not-safe`,
`schema-invalid`, `orphaned-target`, `path-not-contained`, `artifact-too-large`, `header-invalid`,
`decode-failed`,
`degenerate-dimensions`, `dimension-mismatch`, `mask-encoding-invalid`, `animated-png`, `mask-empty`,
`artifact-unreadable`, `mask-hash-mismatch`, `accepted-candidate-hash-mismatch`,
`reference-hash-missing`, `tolerance-out-of-range`, `acceptance-is-noop`.

**Within one reason, order by the record's index in `acceptances[]`** — the ordering above is
between *tokens*, and two records failing the same way (two `mask-hash-mismatch` acceptances) would
otherwise come out in map order in one engine and input order in another. The input index is the
only ordering both engines can see.

Document-wide tokens lead, then identity, then structure, then artifacts — so a combined failure
serialises the same way in both engines, and a reader sees the widest problem first. A combined
document-failure case is a fixture, since that is where an ordering that exists only implicitly
would diverge.

**A per-acceptance refusal populates `validationFailures` as well**, one entry per `(record, reason)`
pair — so two acceptances failing the same way contribute two entries, never one. The two
fields are not alternatives: `statuses` answers "what happened to this acceptance", and
`validationFailures` is the flat list a build gate reports and fails on, without walking the map.
An earlier revision showed a `refused` status beside an empty `validationFailures`, which left both
readings implementable and would have produced different fixture results.

`validationFailures` is a list whose entries take one of **three** shapes, chosen by how precisely
the failure can be attributed:

| Shape | When | Example reason |
| --- | --- | --- |
| `{ "id": …, "reason": … }` | the failing record can be named | `mask-hash-mismatch` |
| `{ "index": …, "reason": … }` | the record has no usable `id`, so it is identified by its position in `acceptances[]` — always available, always deterministic | `id-missing` |
| `{ "reason": … }` | the failure is a property of the **document**, attributable to no single record | `document-too-large`, `document-unreadable` |

The third shape is not a convenience: a budget overrun is caused by the set, not by any member of
it, and forcing an identifier onto it would mean inventing one — picking an arbitrary record to
blame, which is both false and unstable across engines. Each of the three exists because there is a
failure that fits nothing else.

That list is the *only* populated field when the document itself is rejected — `duplicate-id`,
`id-missing`, `document-too-large`, `document-unreadable` — in which case `statuses` is absent entirely
rather than empty, since "no acceptance was evaluated" and "every acceptance was valid" must not
serialise the same way.

Status is **per acceptance**, not per comparison — a set with one invalidated and one surviving
member has two statuses, and the fixture result carries them as a map keyed by acceptance id. The
fixed-candidate case is a required fixture: it is both the happy path and the case two
implementations are most likely to classify differently.

### Coordinate space — the real problem

The mask has to be authored somewhere stable, and the accepted pixels have to be *stored* somewhere
stable. Neither of the planes `format-compare.js` already builds qualifies, because both are sized
from `boxes.candidate` (finding 2 in §1): the same component re-rendered at a different device size
or density produces a different plane, so a stored crop would mismatch on dimensions alone and every
acceptance would false-invalidate as `candidate-changed` — or would need a resample whose resampling
error immediately swamps the tight per-pixel tolerance §7 calls for.

So acceptance gets **its own canonical plane, defined by the reference**:

- **The canonical plane is the one the acceptance recorded** — normally the reference's content box
  at the reference raster's own resolution, but the **full canvas** whenever this pair fell back
  below `MIN_BOX_COVERAGE` (I9). The reference is a published PNG with fixed dimensions and a
  `sha256` the acceptance already pins, so the content-box case is byte-stable by construction: it
  cannot move unless the reference changes, and a changed reference is already the fingerprint gate.
  The fallback case is not derivable from the reference alone, which is exactly why the discriminant
  and the resolved box are recorded fields rather than something re-derived at evaluation time.
- **`mask.png` is authored in it directly** — but *nothing else is already in it*, and that is the
  easy mistake. See the translation rules below.
- **`accepted-candidate.png` is stored in it**, cropped to the mask's bounding box. Evaluating an
  acceptance therefore means resampling the live candidate into the canonical plane — against a
  stored crop that never moves — rather than storing a crop in a plane that moves under it. Note
  this is a resample of the **separated regions**, not of the whole frame (invariants I3/I4),
  which is what keeps a kernel from averaging across the mask edge.
- **Suppression is then mapped into whichever plane is being reported.** The canonical plane, the
  score plane and the diff plane are all content-box crops related by an affine scale, so the mask's
  coverage maps into each with the same arithmetic `boxCanvas` already does. Do the *comparison* at
  canonical resolution and the *reporting* wherever the surface lives; do not do the comparison at
  the 192 px score plane, where a glyph is a handful of pixels and a 5 px edge search covers most of
  it.

This costs a resample per separated region per acceptance, on a page that is already decoding two
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
| Render-side annotation `bounds` (**not** the tag index, which the server already publishes canonical — see below; transforming it again is the mistake this row invites) | **render** pixel space (`boundsInRoot`) | subtract `(candidateBox.x, candidateBox.y)`, then scale **x and y independently** — `plane.width / candidateBox.width` for x, `plane.height / candidateBox.height` for y, where `plane` is the acceptance's **recorded** canonical plane (I9), not `referenceBox`, which is only the same thing outside the `full-canvas` fallback; clip |

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

Given the raw normalised pair and the acceptances whose **entire recorded scope** matches this
comparison — `system`, `component`, `previewId`, `referenceId`, `variant` **and `overrides`**, every
field, not the subset a page happens to key by:

1. **Fingerprint gate.** If the served reference's `sha256` ≠ the acceptance's `referenceSha256`,
   the acceptance is `invalidated: reference-changed`. It contributes no suppression, and the page
   says so. An acceptance targeting a reference that publishes no `sha256` is `refused` — status
   row 1 of the contract, not an invalidation, since a gate with nothing to compare against cannot
   have fired.
2. **Plane gate.** Recompute the plane for this pair. If its `plane` discriminant or resolved box
   disagrees with the acceptance's recorded one — a candidate that has crossed `MIN_BOX_COVERAGE`
   since the acceptance was authored — it is `invalidated: plane-changed`. Comparing across two
   different coordinate planes is meaningless, so this has to precede all **mask mapping and
   resampling**. It does *not* precede decoding: resolving the plane samples both images' pixels
   (see the contract's step 3).
3. **Candidate gate.** Inside each mask, compare the current candidate against
   `accepted-candidate.png` — not against the reference. Match within tolerance ⇒ the acceptance
   stays valid. Mismatch ⇒ `invalidated: candidate-changed`, and the region is reported as a new
   difference.
4. **Element gate.** Resolve the acceptance's `element` per its `kind`, against the **full semantics
   payload**, and invalidate as `element-ambiguous` or `element-moved` per the contract's gate
   table. Re-checking uniqueness *here*, at evaluation time, is the load-bearing part: it was unique
   when the acceptance was authored, and only this check notices when it stops being — resolving to
   an arbitrary one of several duplicates and suppressing its pixels is the failure mode.

   This gate is what catches "the glyph disappeared" as distinct from "the glyph is still the wrong
   colour", which a rectangular ignore region fundamentally cannot.
5. **Only now, score** — everything outside the union of the masks of acceptances whose status
   is `valid`, where "outside"
   means excluded in **both** roles (see below). An invalidated acceptance suppresses nothing.
6. **Report raw, accepted and unaccepted separately.** The raw finding is never destroyed. The
   comparison shows all three numbers and the delta map gains an "accepted" tint distinct from the
   magenta of unaccepted difference, so an acceptance is *visible* rather than a hole in the data.

**Every gate runs before any scoring, and that ordering is load-bearing.** The tempting order —
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

There is no binary rule at score-plane resolution that fixes this. "Mask the pixel only if its whole
footprint is masked" *sounds* conservative and is not: `drawImage` averages **signed** luma, so an
accepted difference on one side of a straddling footprint can cancel an opposite unaccepted
regression on the other before `scorePlanes` ever sees the pixel. Masking it instead just hides the
boundary ring outright. Both choices can hide an adjacent regression, which is the one outcome the
model may not have.

**So the masked and unmasked contributions have to stay separate through the resample** — score the
mask boundary at canonical resolution, or build the two regions as separate planes before
downsampling. This is not a tuning parameter; see the architectural note below.

**The fixtures must include a mask edge deliberately not aligned to the score-plane grid**, since an
axis-aligned fixture at a convenient scale would never exercise this at all.

#### The acceptance comparison needs its own comparison path

Two findings above point at the same conclusion, and it is worth stating outright rather than
patching around: **acceptance cannot be implemented as a mask bolted onto the existing browser
scorer.**

- The straddling-footprint problem has no correct resolution at score-plane resolution, because the
  downsample has already mixed accepted and unaccepted signal.
- The canonical-plane resample itself is undefined across engines. `drawImage`'s smoothing is
  implementation-dependent, and the offline engine will use some other image library — so the *same*
  unchanged candidate bytes can produce different canonical pixels in the two engines and get
  falsely invalidated as `candidate-changed`. Shared expected-value fixtures cannot fix this: they
  pin the answer, not the resampler that produces it.

Phase 3 therefore owns a **specified, portable comparison path** as a deliverable in its own right,
with these requirements:

1. **A named resampling algorithm** with defined kernel and rounding (e.g. box-filter at integer
   ratios, explicit bilinear with specified edge handling otherwise), implemented identically in
   both engines rather than delegated to whatever the host provides.
2. **Defined pixel and colour semantics** — channel order, alpha handling, premultiplication, the
   gray projection — since these differ between canvas and most image libraries.
3. **Masked and unmasked regions kept separate through every resample**, so no averaged pixel ever
   carries both.
4. **Conformance fixtures that pin intermediate planes, not only final scores**, so a resampler
   divergence fails as a resampler divergence instead of surfacing as an unexplained score drift
   months later.

**These are requirements on a deliverable, not the specification itself — deliberately.** Picking
the exact kernel, the rounding and edge rules, and the concrete channel/alpha/gray formulas is
Phase 3's first task, not this document's. Two engines could both satisfy the list above and still
diverge on, say, a translucent pixel at a non-integer scale ratio; that is a real gap, and closing
it here would mean choosing constants with no implementation to validate them against and no
fixtures to catch the choice being wrong. The mechanism that actually forces convergence is the
intermediate-plane fixtures, which fail at the diverging stage — so the sequencing is: choose the
kernel and semantics as Phase 3 step 1, land the fixtures with them, and treat any later engine
disagreement as a fixture gap. A planning document that guessed the constants would produce a
number both engines cite and neither validates.

This is a meaningful increase in Phase 3's cost, and it is load-bearing: without it, "the same
acceptance semantics are used by design-parity and the preview server" — an explicit acceptance
criterion of the epic — is not achievable, only approximated.

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
- **Shared conformance fixtures** — a committed set of
  `(reference, candidate, acceptances[], semanticsPayload) → expected …` cases, in this repo, run
  by both the JS unit tests here and design-parity's own suite.

**Recommended: the third.** It is the same device already used for `parity-activity.mjs` ↔
`ServeParityActivityStore` (one committed fixture, two languages, both tests load it), it is cheap,
and it fails loudly.

**A fixture must pin the intermediate planes, not only the final numbers.** Expecting just
`{raw, accepted, unaccepted, invalidations}` is what the portable-comparison-path requirement above
rules out: a resampler divergence would surface as an opaque score difference, or be hidden entirely
when two later steps happen to cancel it. So each case pins, as named artifacts:

| Stage | Pinned artifact |
| --- | --- |
| decode (validated inputs only) | **every** *hash-valid* raster input decoded — the two shared ones (reference, current candidate) plus `mask.png` and `accepted-candidate.png` **for each member of `acceptances[]`**, so a two-acceptance case pins six, not four. The candidate gate reads each accepted-candidate decode and coverage reads each mask decode, so an alpha or colour divergence in any of them would otherwise first surface as a wrong verdict rather than a decoder bug. **A hash-mismatch fixture pins no decode for the failing artifact** — I7 refuses it before its bytes are used, and a tampered file may not be decodable at all, so requiring a decoded plane for it would contradict the contract it exists to test |
| content boxes | each input's measured content box and the resolved `plane` discriminant, since content-box detection is itself part of the portable path and two engines can otherwise measure differently near a sampled edge or the `MIN_BOX_COVERAGE` threshold |
| tag index | the `{tag: {count, bounds}}` projection the server computes from `semanticsPayload`. Pinned as its own stage because **production does not ship the tree to the browser** — feeding the full payload to both conformance consumers would leave the projection itself untested, so the server could count tags or transform bounds differently from the offline resolver while every fixture still passed. **A third runner is required for this stage**: the server projector's own Kotlin tests must consume these same payload fixtures and produce this expected index. The two named runners cannot cover it — production JavaScript consumes an already-built index, so the JS suite never exercises the projector, and design-parity has its own resolver rather than this one. Pinning the artifact without running the code that produces it proves nothing about the code that produces it |
| selector | the resolved element — which node the selector matched, its bounds in canonical coordinates, and the tag-uniqueness verdict. Resolved **from the index** as the browser does — **and, separately, from the raw payload through design-parity's own production resolver**, since that is what the offline verdict actually comes from. Pinning only the index path lets the browser tests, the projector test and this row all pass while the offline resolver disagrees about the same tree; the alternative is to make production design-parity consume the index too, which is the better end state and a larger change. Ahead of the union stages, because which acceptances survive decides which masks the union contains (I5) |
| separation (per acceptance) | the masked and unmasked regions of **both inputs**, each in its own pixel space, before any resample (never a pre-averaged composite), for **each** acceptance independently |
| canonical | every separated region — reference *and* candidate — after the named resampler, in the resolved canonical plane |
| separation + canonical (surviving union) | the same split redone against the union of **`valid`-status acceptances** only — resolved, invalidated and refused ones suppress nothing — **and resampled into the canonical plane again** — separation still precedes its resample (I3). A distinct, later stage on purpose: the union cannot be formed until the candidate and element gates have run, and those gates need the canonical pixels the row above produces (I5) |
| score plane (separated) | the `MAX_SIDE`-capped planes the unaccepted pass consumes — both sides, each region drawn **straight from the source image** (I10), per-region rather than whole-image |
| score plane (whole) | the `MAX_SIDE`-capped unseparated planes the raw pass consumes — both sides, drawn straight from the source by the same single resample, so raw and unaccepted stay on identical geometry and acceptance alone never moves the number (the kernel rebaseline does, once) |
| result | `{raw, accepted, unaccepted, statuses, validationFailures}` — `statuses` is a **map keyed by acceptance id**, absent entirely for a document-level rejection (duplicate ids), where `validationFailures` carries the reason alone; `validationFailures` has **one entry per `(record, reason)` pair** — a record failing two hash checks contributes two, and two records failing the same way contribute one each, in input-index order, per the ordering rule in the contract. Not "one per reason": that reading would let one engine deduplicate a token shared by two acceptances while the other emitted both, from the same document. `statuses` still carries one entry per member of `acceptances[]`, each per the contract's precedence table. A single aggregate status cannot express a mixed-validity case, so both engines could emit the same summary while disagreeing about which mask survived; the mixed-validity fixtures pin the per-id identities |

The stages exist to localise a divergence, so they must follow whatever order the Phase 3 algorithm
settles on — and must satisfy the invariants above regardless. Two orderings are already known to be
wrong and must not be pinned: resampling before separating (I3), and building the surviving-union
planes before selector resolution has decided which acceptances survive (I5).

**Hard validation failures need an expected value too.** A mask or accepted candidate whose bytes
don't match its recorded hash is a *refusal*, not an invalidation — a distinct branch of the
contract with no home in `{raw, accepted, unaccepted, invalidations}`. Without a
`validationFailures` field and hash-mismatch cases, one engine could refuse, the other could
silently drop the acceptance, and both would pass the suite while disagreeing about whether a
tampered artifact is an error or a no-op.

**Both sides, at every stage.** The pipeline separates and resamples the reference as well as the
candidate, so pinning only the candidate's intermediates leaves reference-side mask coverage and
reference-side resampling unchecked — and a divergence there shows up as a score difference at the
very end, which is exactly the diagnosis-by-guesswork the fixtures exist to avoid.

**Acceptances are a set, and the set is where engines diverge.** A fixture carrying one acceptance
exercises none of the behaviour that only appears with several: masks that **overlap** (whose union
is what step 8 excludes, so double-counting or gapping at the seam is invisible with one), and
**mixed validity** — one acceptance invalidated while another survives, where the failure mode is
retaining suppression from the invalidated mask. Both engines can pass every single-acceptance case
and still disagree on these, so the input is a collection and the suite must include an overlapping
pair and a mixed-validity pair as named cases.

**The semantics payload is a fixture input, not context.** With `element.kind: tag`, the verdict
depends on the current `ComposeSemanticsPayload`: whether the tag is still unique, still present,
and still within bounds tolerance. A fixture whose inputs are only the two rasters plus the
acceptance cannot express "the tag became duplicated" or "the tag moved", so both engines could
implement `element-ambiguous` / `element-moved` differently and pass the suite. Each case therefore
carries a semantics tree and pins the selector-resolution outcome as its own stage.

A divergence then fails at the stage that caused it, which is the whole point of paying for the
fixtures at all.

The fixtures also have to pin the **active** scorer's behaviour, not a textbook one: `scorePlanes`'
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

The criterion is **the tag's uniqueness, not the ref's path shape** — see the rule below for why the
ancestor segments drop out. Judged that way:

| Selector | Durable? | Use as acceptance identity |
| --- | --- | --- |
| a `testTag` unique in the tree — **at any depth**, under any ancestors | yes — resolution matches the tag, not the path | **yes** |
| a `testTag` that is (or becomes) shared by two nodes | no — nothing distinguishes them | no — `element-ambiguous` |
| `r/role:<role>[n]` (no tag) | no — the index is positional and silently retargets | **no** |
| `r/role:<role>` (lone anchor, no tag) | no — gaining a sibling turns it into `[0]`/`[1]` | no, but fails *loudly* |
| `r/node` | no — purely structural | no |

Note what is deliberately **absent** from that table: the ref's ancestor path. `r/role:Row[0]/tag:item`
is a perfectly good selector *provided `item` is unique*, because the resolver never walks
`role:Row[0]` at all.

**The durable property is the tag's uniqueness, not the path's shape.** It is tempting to require
the whole ref path to be tag-anchored, since `SemanticsRefs` indexes every level whose siblings
share an anchor and `r/role:Row[0]/tag:item` retargets when those `Row`s reorder. But that is only
true of a resolver that *walks the path*. **Resolve by tag identity instead** — search the tree for
the node carrying that `testTag` — and the ancestor segments stop mattering entirely: reordering
ancestors cannot retarget a tag that only one node has. Requiring a fully tag-anchored path would
reject a perfectly durable selector on a tagged element sitting under ordinary `Row`/`Column`
ancestors, which is most real Compose UI, and would push those elements onto the weaker geometric
fallback for no safety gain.

So the rule is: **the `testTag` must be unique across the semantics tree**, wherever it sits, and
resolution matches on the tag rather than the path. Uniqueness is checked at authoring time *and*
re-checked at evaluation time — only the first is under the acceptance author's control. The
selector kinds, what each resolves by, and what makes each ambiguous are defined once in §4's
[normative contract](#the-normative-contract); this section is the reasoning behind it, not a second
statement of it.

**Persist an element selector only when the contract's durability test passes. Otherwise keep the
drag rectangle** and accept the region geometrically, with no element gate. A geometric acceptance
is weaker — it cannot tell "the glyph disappeared" from "the glyph is still wrong" — but it is
honest about what it knows, whereas a positional ref claims an identity it cannot keep. The
non-indexed `r/role:` case is worth distinguishing in the implementation because its failure is the
safe one: it stops resolving and reports `element-moved` rather than pointing somewhere new.

Reporting an issue from a selection must **not** accept the difference. Filing and accepting are
separate, deliberate acts by separate artifacts — one is a GitHub issue the visitor's own browser
files, the other is a committed file that goes through review.

---

## 6. Delivery order

Sequenced so each step is independently useful and nothing is blocked on the cross-repo work.

### Phase 1 — Issue visibility

> **All four steps are delivered** — step 1 in [#3887](https://github.com/yschimke/compose-ai-tools/pull/3887),
> steps 2–4 in [#3886](https://github.com/yschimke/compose-ai-tools/pull/3886) with the producer
> corrections in [#4404](https://github.com/yschimke/compose-ai-tools/pull/4404), and the catalog's
> caller in [yschimke/m3-catalog#170](https://github.com/yschimke/m3-catalog/pull/170).
>
> **The prose below is the plan as written before delivery, kept for its reasoning, and it is now
> historical in the places where it describes the code.** `handleReferenceComparison` does read the
> normalised overrides and does build a report context — it carries the form the step argued for —
> so an implementer reading step 1 as a to-do list would redo work that exists. What it says about
> *why* (which surface owns the score, why the interactive lanes stay disabled, why a
> server-rendered locator would record the wrong frame) is still the rationale the code implements.
>
> **One requirement in step 1 did not land:** `element` and `bounds` were never reserved as optional
> `v1` fields. See the status header and §7 — it is batch 03's problem now, and it is the reason
> Phase 1 is "delivered" rather than "complete".

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

   **Substitute from the displayed frame, not from the controls.** `refreshLinks()` calls
   `refreshReportLink()` as soon as a control moves, but the viewer deliberately records the frame's
   real provenance later — in the replacement image's `onload`, once the render it asked for has
   actually arrived. Between those two moments the visible pixels are still the *previous* frame. A
   locator substituted from the controls during that window recreates exactly the identity/pixel
   mismatch this step exists to prevent, and a render that fails outright leaves it wrong
   indefinitely. Derive the locator from the successfully displayed frame's recorded state, or
   disable the report affordance until the requested frame has loaded — the second is cruder but
   cannot be got subtly wrong.

   **And neither remedy reaches the interactive lanes at all.** In the Live, Wasm and Remote Compose
   lanes the visible frame is painted into a canvas or an iframe, overrides are applied in place, and
   `#cp-img` / `data-cp-blob` / `data-cp-src` go on describing the static snapshot the visitor
   arrived from. `viewer.js` already documents this precisely — `specActualUrl()` calls that blob "a
   stale bystander" in exactly these lanes and falls back to asking the server. An `onload` hook on
   the replacement `<img>` therefore fires for a frame nobody is looking at. Either each lane grows
   its own frame-arrival/provenance signal, or — simpler, and consistent with §1's finding 1 — **the
   report affordance is disabled throughout the interactive lanes**, full stop — not redirected to
   the focused comparison. An earlier revision recommended that redirect and the delivery step now
   rules it out for the reason below; leaving the recommendation here would be the version an
   implementer read first.

   **The focused comparison still has to be able to file, and to carry overrides — for the lanes
   that *do* redirect there.** Phase 1 offers two surfaces and marks the comparison one "preferred";
   if the viewer-only option is taken, a redirect from any lane points at a page with no reporting
   affordance at all. And `handleReferenceComparison` reads `name` and `reference` and nothing else,
   while `referenceComparisonPage` builds its Actual `/render` URL from `linkQuery`'s auth/session
   parameters alone — no theme, locale, font scale, device or Remote Compose state — so a reporter
   arriving from an overridden *static* frame still lands on the default snapshot and files against
   pixels they never saw. Both are prerequisites of using that page as a reporting destination at
   all; neither rehabilitates the interactive lanes, which is a separate problem with a separate
   cause.

   **And override support is necessary without being sufficient.** Overrides are query parameters;
   *interaction* is not. Once a visitor has clicked, scrolled, or let animation advance in a Live,
   Wasm or Remote Compose lane, the visible frame is a function of runtime state that no URL carries
   — the comparison route issues a fresh `/render` and starts from the initial state. So forwarding
   theme, device and RC parameters still lands the reporter on different pixels than the ones that
   prompted them. **Reporting stays disabled in the interactive lanes**, not "until overrides land"
   but until the exact displayed frame and its interactive state can actually be transferred — which
   is a much larger piece of work (capturing and replaying runtime state, or attaching the displayed
   bitmap directly to the report) and should be scoped deliberately rather than assumed. The
   override work above is what makes the *static* lanes' redirect correct; it does not rehabilitate
   the interactive ones.

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

   **Selection must be drivable from the tag index, not only from annotation boxes.** A uniquely
   tagged node with neither typography nor container tokens produces no annotation at all — the same
   omission that forced uniqueness counting onto the payload — so a comparison page that only makes
   *annotation boxes* clickable offers nothing to select for exactly the nodes best suited to a tag
   selector, and the reporter falls back to a drag rectangle. That silently downgrades the
   acceptance to geometric, with no element gate, so a tagged glyph that later vanishes or moves
   goes undetected. The index already carries `{count, bounds}` per tag, which is everything a
   selectable target needs, so the page renders selectable entries from it alongside the annotation
   boxes rather than treating the annotation layer as the only source of targets.

   **Neither option redirects the interactive lanes.** Whichever surface takes the form, the Live,
   Wasm and Remote Compose lanes keep reporting **disabled outright** — not pointed at the focused
   comparison. That page issues a fresh `/render` from initial state, so a report filed from it
   after the visitor has clicked, scrolled or let an animation run describes pixels nobody saw. §5
   reaches the same conclusion; stating it here too because this is the step someone implements
   from, and "redirect to the comparison" is the instruction they would otherwise follow.
2. **`compose-preview-issues/v1` + reader + staging.** `ServeParityIssues.kt`, `ServeCatalogStore`
   staging, fixture-backed tests. Served nothing until step 3's producer and a catalog caller
   existed — which is exactly how it sat, tests passing over an empty path, until
   yschimke/m3-catalog#170.
3. **Producer + regeneration workflow.** `parity-issues.mjs` / `emit-parity-issues.mjs` here; the
   issue-triggered workflow lands in the catalog repo.
4. **Show open issues** on the viewer row, the focused comparison, the grid cards and the dashboard.
   *First visible payoff, and the point at which the epic's first four acceptance criteria are met.*

### Phase 2 — Triage

5. **Semantics annotations on the focused comparison** (the prerequisite from §5), **plus the
   tag index** the element gates need — the comparison page currently receives only the two
   annotation lists, and the derived annotations drop exactly the untagged nodes a uniqueness check
   must count. Both halves land here; Phase 3 step 9 must not enable element gates without them.
6. **Element selection** — click an annotated element, or drag a region; the selection rides into
   the prefilled report.
7. **Dashboard views** — new-vs-known split, components with open issues, area classification.

### Phase 3 — Scoped acceptance

8. **`compose-preview-known-differences/v1` schema + conformance fixtures**, defined here.
9. **Apply acceptances in `format-compare.js`** per §4, raw/accepted/unaccepted reported separately
   — **including status evaluation**, `resolved` among them. Detecting resolution is not Phase 4
   work: the conformance fixtures Phase 3 must pass carry a per-acceptance `statuses` map with a
   required fixed-candidate `resolved` case, so an engine that defers resolution cannot satisfy its
   own contract. Phase 4 surfaces and acts on these statuses; it does not compute them.
10. **Publish through catalog-export**, and **apply the same semantics in `design-parity`**.
    *Cross-repo; sequence after 8 so both sides build against a settled schema and the shared
    fixtures.*

### Phase 4 — Resolution

11. **Surface** the statuses Phase 3 already computes — `resolved`, `invalidated`, `refused` — plus
    **stale** (issue closed, acceptance remains), which is the one lifecycle state that needs the
    issue index rather than the comparison: on the dashboard, and as a gate in the offline run.

    **Stale requires positive evidence of closure — never inference from absence.** An issue missing
    from `parity/issues.json` means almost nothing: the index is fail-soft (a malformed file drops
    wholesale), it is capped, it can be stale between regenerations, and it does not cover source
    repos whose dispatch credential nobody has wired yet. Treating absence as closed would mark live
    acceptances stale across an entire catalog the first time the file failed to parse. So the index
    **publishes closed rows** — an issue referenced by any acceptance stays in the index with
    `state: "closed"` rather than being dropped — and a consumer that cannot find a row reports
    *unknown*, not stale. That is one more reason the emitter must be told which repos to scan: an
    unscanned repo's issues are permanently unknown, which is honest, where inferred-closed would be
    confidently wrong.

    The `resolved` / `invalidated` / `valid` statuses and their precedence are defined once in §4's
    [normative contract](#the-normative-contract) — including why the success path trips a gate and
    must still classify as resolved. This step is the surfacing of those statuses, not a second
    definition of them.

    **Stale and unknown are a second axis, not two more statuses.** They must not join the
    precedence table or the `statuses` map: `status` is what a *comparison* concluded, and it is
    exactly what the two engines are required to agree on, while these come from the issue index —
    which the offline run may have and a serving host may not, or vice versa. Fold them in and the
    conformance fixtures start depending on a file that is not part of the contract, and one engine
    reports `stale` where the other reports `valid` for the same bytes. So the join happens **at
    the dashboard and the gate**, over an acceptance's mandatory `issue` URL, and produces a
    separate lifecycle value per acceptance: `open`, `closed`, or `unknown` (no row in the index).
    The evaluation result is unchanged by it, and `compose-preview-known-differences/v1` gains no
    field.

    The two axes compose freely — all four statuses occur against all three lifecycle values — and
    only one combination is called **stale**: `closed` on an acceptance whose status is *not*
    `resolved`, i.e. a difference the mask still suppresses after its issue was declared done. The
    combination that looks similar is the opposite of a problem: `resolved` + `closed` is the loop
    having completed, and the only thing left is step 12's deletion. `unknown` is never stale, per
    the rule above, and a `refused` acceptance's lifecycle is worth showing but never actionable —
    fix the record first, since a refused acceptance suppresses nothing whatever its issue says.

12. **Close the loop — deletion and issue closure need an owner.** Nothing so far actually *removes*
    anything: §4 says `resolved` means deleting the acceptance and that an issue closes once every
    linked acceptance has resolved, but surfacing a status is not doing either. Both are committed-
    file operations, so both belong in a PR, and the ordering matters — delete the acceptance first
    and the issue loses the only record of what it was about; close the issue first and the surviving
    siblings are briefly `stale` against a closed issue.

    So they happen **in one change**: a PR that deletes the resolved acceptances' records and
    directories, and closes the issue via a closing keyword in its description, so the merge does
    both atomically and the PR itself preserves the association. Opening it can be automated later
    (the offline run already knows the full set of resolved ids and their canonical issue identity);
    doing it by hand is fine to start with, but it has to be someone's documented step rather than
    an implication of the status.

    **The closing keyword goes in only when this document owns the issue** — §4's single-ownership
    rule. A run that cannot establish ownership still opens the PR and still deletes its resolved
    records; it just omits the keyword and says so in the body, leaving the close to whoever can see
    the other referencing documents. Deletion is always safe locally; closure is the half that needs
    knowledge this plan does not give a single run.
13. **Document** the reporting → triage → acceptance → verification → closure loop in
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
  canonical resolution rather than to widen it. §4 caps it at `8` for `v1` so no author can widen it
  quietly, which bounds the risk without removing it: the *metric* the tolerance applies to is still
  open, and a permissive metric at a tight ceiling is no safer than the reverse.
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
- **What counts as "the same variant"?** *Settled in §4's contract, flagged here because it was an
  open question for most of this document's life.* The preview id does not encode overrides, so the
  record carries them explicitly and matching is exact — an acceptance authored at `fontScale=1.5`
  does not apply at `1.0`. It had to be settled rather than left open, because the full-scope
  matching rule depends on it. Say so if you want the looser reading; it is a `v1` schema decision
  either way.
- **One issue, several components.** ***Settled: one locator block per component.*** *Measured, not
  hypothetical — see "The pilot population" in §3.* Three of `m3-catalog`'s ten known differences
  are umbrella reports: #42 names three components, #93 spans `Button/*` and `Fab/*`, and #91 names
  five. A block names one component, so a body carries one block each and the index emits a row per
  block, keyed by issue × component. Splitting the umbrella into one issue per component was the
  alternative and was rejected: it multiplies the backlog and loses the fact that the pieces share a
  cause. **The reader was half of this**, and the half that would have been missed:
  `ServeParityIssuesStore.sanitize` deduped with `distinctBy { it.repository to it.number }` before
  anything component-aware ran, so the rows collapsed to an arbitrary one at load time and the issue
  still reached exactly one component page. Identity is now repository + number + component in both
  engines, with the shared fixture carrying a body that names two components and one that names the
  same component twice. **#91 is not fixed by this either way** — its interaction variants are
  unauthored, so each piece of a split still has no `preview` to name; it belongs with the
  missing-subject cases below.
- **`element` and `bounds` were never reserved in `v1`.** ***Settled: reserved as a `v1` erratum,
  after answering D1.*** Batch 01 called for both as optional fields *before* the writer, the parser
  and the shared fixture froze, precisely so batch 03 would not have to bump the version to add a
  selection — and Phase 1 shipped without them. Since both parsers ignore unknown keys, that would
  have been the permissive failure the requirement existed to prevent: a report carrying a selection
  indexed with the selection dropped and no error anywhere. Reserving them meant settling
  [D1](parity-batches/00-decisions.md#d1--which-plane-the-element-tag-index-reports-bounds-in)
  first, since a rectangle with no plane is the ambiguity that makes an unmoved element report as
  `moved`; D1 is answered **(a)** — the index publishes render-pixel bounds and names its space, the
  canonical-plane transform belongs to the comparison — so `v1` accepts `render-pixels` and nothing
  else. Doing it now cost a fixture case and two parsers; the alternative was a `v2` bump across the
  writer, the producer and the reader once selection landed.
- **A parity report needs its subject to be in the catalog.** Three of the ten (#85, #95, #86) are
  about a `DropdownMenu` and an expanded full-screen search view that this catalog does not publish
  — no preview, no reference, nothing to compare, however the locator is shaped. Two more (#89,
  #93) are missing-constant reports whose renders match: those *could* be indexed, since the
  producer never inspects a pixel, but there is nothing for §4 to accept. ***Settled: index them.***
  A reader asking "why can't I name this size?" is standing on the component page, which is where
  the answer belongs — so they carry locators and `area:component` like any other report, and simply
  never carry an acceptance. §4's population is unchanged by that: four issues across six acceptance
  sites (#40, #41, #87, and #42 three times), not ten. Still open: whether the catalog should grow
  the stickers the three missing-subject reports are about.
