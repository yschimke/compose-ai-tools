# Batch 03 — element selection on the focused comparison

**Issue:** [#3803](https://github.com/yschimke/compose-ai-tools/issues/3803).
**Depends on:** [01](01-locator-and-report.md) (the selection rides into the locator). **D1 is
answered (a)** and both fields are reserved in `v1`, so the plane question that used to block this
batch is settled: bounds are recorded in render pixels and named as such, and the canonical-plane
transform is the comparison's step. Recording them in another space would make an unmoved element
fail the movement gate later — the exact failure the element gate exists to detect — which is why
both parsers refuse one. The tag index half of this issue's original prerequisite is **already
done** — see below.
**Blocks:** the element gates in [batch 05](05-acceptance-engines.md).
**Ships:** **yes.** Click an element, report *that element* rather than "somewhere in this picture".

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §5 and §6
steps 5–6; `ServeSemanticsTags.kt` and `scripts/design-artifacts/tag-index.mjs` (the two producers,
and the KDoc in each explains the rules); `inspect.js` and `viewer.js`'s `data-cp-src` frame
recording, which is the coupling this batch has to break.

---

## Prerequisite status

The tag index is **complete** — carved out to
[#3878](https://github.com/yschimke/compose-ai-tools/issues/3878) and delivered by
[#3830](https://github.com/yschimke/compose-ai-tools/pull/3830) /
[#3860](https://github.com/yschimke/compose-ai-tools/pull/3860) /
[#3864](https://github.com/yschimke/compose-ai-tools/pull/3864). Both producers exist (live daemon
projection; published-catalog `tags/index.json`) and the host API is `ServeHost.tagIndexForPreview`.
It has **no HTTP surface yet** — deliberately, because this batch is its first consumer and inventing
a route before a caller existed would have frozen a guess. Adding that route is part of this batch.

What remains from the original prerequisite is the **derived semantics annotations** on the focused
comparison.

## Work

### 1. Derived semantics annotations on the focused comparison

The comparison page today receives only the *producer-authored* annotation lists from a bundle's
`annotations/index.json`. The **derived** layers — typography and theme projected from the render's
own semantics tree by `ServeDesignAnnotations` — are viewer-only, because `inspect.js` is coupled to
the viewer's DOM ids and to `viewer.js`'s `data-cp-src` frame recording. Mounting them over the
comparison page's Actual panel means **generalising that machinery to accept a host element**, which
is its own change with real regression surface on a shipped page.

Do that generalisation as its own commit, with the viewer's behaviour pinned by fixtures *before* the
refactor, so the diff bot proves the viewer did not move.

(#3830 made the *theme* annotation kind reachable on the comparison page — it previously had a box
and a legend row built for it and no toggle able to reveal them — but that is the **authored** layer,
not the derived one.)

### 2. Expose the tag index over HTTP

`ServeHost.tagIndexForPreview(previewId)` needs a route the page can fetch. Keep it in the shape of
the other per-preview JSON the comparison already pulls, and keep the wire type the one
`ServeTagIndexStore` validates — including `space`, which the reader requires and
[D1](00-decisions.md#d1--which-plane-the-element-tag-index-reports-bounds-in) governs.

**But that route alone is not enough to enable an element gate, and this is the trap in this batch.**
`tagIndexForPreview` is the *published static* index, and both live host wrappers delegate it to
their baked host — so on a daemon-backed or otherwise live comparison it does not describe the frame
being scored. The live per-render index rides the `.annotations` response instead, and *that* call
re-renders: it is a separate request from `/render/<id>.png`, so it does not describe the PNG the
client already fetched either. (This was asserted the other way round while the index was being
built, and it was wrong. Co-locating `tags` with `annotations` buys agreement between those two
projections and nothing more.)

With overrides, conditional composition or animation in play, a gate fed from either path can
validate an element against bounds from a **different frame** and let the wrong mask suppress
pixels. So: establish a shared render generation, or carry the index with the scored pixels, before
element gates are switched on in [batch 05](05-acceptance-engines.md).

**And the exemption for selection is narrower than it first looks.** A *drag* selection is fine
without coupling — it is derived from the displayed pixels, so it describes what the reporter saw by
construction. A **tag** selection is not: its bounds come from the index, they are persisted into the
locator as authoring-time `bounds`, and those bounds become the acceptance's baseline. Bounds read
from a different frame therefore survive into a record that later reports an unchanged element as
*moved* — a false invalidation with a plausible explanation attached, which is worse than a missing
check. So require same-generation coupling for **tag-derived** selection too; leave drag selection
available meanwhile.

### 3. Selection

Click an annotated element, **or** drag a region.

**Selection must be drivable from the tag index, not only from annotation boxes.** A uniquely tagged
node with neither typography nor container tokens produces no annotation at all — so a page that only
makes annotation boxes clickable offers nothing to select for exactly the nodes best suited to a tag
selector, and the reporter falls back to a drag rectangle. That silently downgrades the acceptance to
geometric, with no element gate, so a tagged glyph that later vanishes or moves goes **undetected**.
The index already carries `{count, bounds}` per tag, which is everything a selectable target needs.

### 4. Selection rides into the prefilled report

Into the locator block from batch 01, as the `element` selector plus its authoring-time `bounds` —
the same fields the acceptance schema (batch 04) will carry.

**Both fields are already reserved — this batch fills them, it does not add them.** Batch 01 called
for that and shipped without it; the reservation landed afterwards as a `v1` erratum, so
`compose-parity-locator/v1` now carries `element` and `bounds` as optional fields that the writer
emits when set and both parsers round-trip. Nothing writes them yet, which is this batch's job.

`bounds` is not a bare rectangle: it carries `{"height":…,"space":"render-pixels","width":…,"x":…,"y":…}`
as canonical JSON, and `v1` accepts no other space —
[D1](00-decisions.md#d1--which-plane-the-element-tag-index-reports-bounds-in) is answered (a), so the
tag index publishes render pixels and the canonical-plane transform belongs to the comparison. A
selection recorded in display pixels is refused by both parsers rather than stored; if this batch
needs a drag selection, convert it into render pixels before serialising.

## Traps

- **Element identity is tag uniqueness, not `ref` path shape.** `SemanticsRefs` assigns `ref` as a
  `/`-joined path and *indexes siblings sharing an anchor* (`r/role:Button[0]`), so an inserted
  sibling silently retargets an existing `ref`. That is the entire reason the tag index exists.
- **`count` is the uniqueness check.** It counts every node carrying the tag, including nodes whose
  bounds are unusable. A tag with `count > 1` is not a usable identity — offer it as a *region*
  selection or refuse it, never as an element selector.
- **The key is the tag verbatim.** No trimming anywhere in the chain: `"item"` and `" item "` are
  different identities, and collapsing them produces false ambiguity in one direction and false
  disappearance in the other.
- **Selection is a *report* affordance. Reporting a difference must never accept it** — epic non-goal,
  and the easiest boundary in the whole epic to erode by accident.
- `bounds` may be absent for a tag whose every carrying node had a zero-area box. Handle it; do not
  assume presence.

## Done when

- A tagged node with no typography or container tokens is selectable — the case the annotation-box-only
  design misses. Pin it with a fixture.
- A duplicated tag (`count: 2`) cannot be chosen as an element selector.
- A drag region and a tag selection both round-trip into the locator block and back.
- The viewer's annotation rendering is byte-identical before and after the `inspect.js`
  generalisation, proved by the snapshot harness.

## Visual evidence

Mandatory. Before/after of the comparison page with the derived layers mounted and a selection
active, both themes, via `ServeWebFixtureTest` → `pages-snapshot.spec.mjs`. The existing
`renders/parity-comparison-annotations/` set is the precedent for committing them alongside.
