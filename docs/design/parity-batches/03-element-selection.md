# Batch 03 — element selection on the focused comparison

**Issue:** [#3803](https://github.com/yschimke/compose-ai-tools/issues/3803).
**Depends on:** [01](01-locator-and-report.md) (the selection rides into the locator). The tag index
half of this issue's original prerequisite is **already done** — see below.
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
