# Batch 00 — decisions and corrections

**Issues:** none — this batch exists because writing code against an unsettled question is how the
design doc accumulated three wrong pixel pipelines.
**Depends on:** nothing.
**Blocks:** [01](01-locator-and-report.md) (D2, D4), [03](03-element-selection.md) (D1),
[04](04-acceptance-schema.md) (D1, D5), [05](05-acceptance-engines.md) (D1, D3, D5) — and 02 and 06
transitively, through 01. **Every one is a hard prerequisite**; see the graph in
[README](README.md#order), which this header must agree with.
**Ships:** no user-visible change. Output is edits to
[`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) and to the affected issues.

Each decision below is stated with the options, the recommendation, and what breaks if it is deferred
past the batch that needs it. None is large. All are cheap now and expensive after two engines have
been written against opposite readings.

---

## D1 — which plane the element tag index reports bounds in

**Blocks:** 03 (what a selection records, and in which space), 04 (the `plane` discriminant),
05 (the element gate).

The design doc says the server publishes tag bounds already transformed into an acceptance's
*canonical plane*. **Neither producer can do that.** The canonical plane is resolved per comparison,
from a reference raster and an acceptance record; `ServeSemanticsTags` sees one daemon render and
`scripts/design-artifacts/tag-index.mjs` sees one packed bundle. Both therefore emit `boundsInRoot`
render pixels and declare `space: "render-pixels"` on the wire, and `ServeTagIndexStore` rejects any
entry that declares anything else — so nothing can silently consume these as canonical today.

| Option | Consequence |
| --- | --- |
| **(a) The transform belongs to the comparison.** Index stays render-pixel; the comparison maps into the canonical plane at gate time. | Doc §4/§5 need correcting. No producer changes. **Recommended.** |
| (b) The index becomes comparison-scoped. | A third producer keyed by `(previewId, referenceId, acceptance)`, recomputed per comparison, and the published `tags/index.json` stops being a per-preview artifact. |

(a) is recommended because a plane is a property of a *comparison*, and the index is a property of a
*render*. Under (b) the published artifact would have to be regenerated whenever an acceptance
changed, which is precisely the "publishing must not require a re-render" property batches 02 and 05
both rely on.

**Do:** amend the doc to say the index publishes render-pixel bounds and names its space, and that
the canonical-plane transform is a step of the comparison. Then relax or keep `space` validation
deliberately rather than by accident.

> **Answered: (a).** A plane is a property of a comparison and the index is a property of a render,
> and (b) would make the published `tags/index.json` depend on the acceptances — breaking the
> "publishing must not require a re-render" property batches 02 and 05 both rely on. It is also what
> both producers already do, so (a) is a correction to the doc rather than a change to the code.
>
> Settled here because the `compose-parity-locator/v1` erratum needed it: the reserved `bounds`
> field names its space and `v1` accepts `render-pixels` only. §2 of the contract carries the rule,
> and both parsers refuse any other space rather than storing the guess.

## D2 — which surface carries the report form

**Blocks:** 01 directly — it decides which page the form is built on, and 01 builds it. 02 and 03
inherit it, since both put things on "the page where you see the problem".

[#3802](https://github.com/yschimke/compose-ai-tools/issues/3802) records that the form currently
lives only on the viewer, whose always-available live number is `scoreSvgUrls` — PNG against the
*generated SVG*. That is a render-fidelity measurement, not a design-parity one. Emitting it into an
issue body labelled as a parity score produces a plausible, mislabelled number feeding an index.

| Option | Consequence |
| --- | --- |
| **(a) Move the form to the focused comparison** `/{system}/compare/{previewId}`. | Always has a concrete `(previewId, referenceId)` pair *and* the real score, and is where element selection lands in 03. Viewer keeps a link to it. **Recommended.** |
| (b) Keep it on the viewer; omit the score field unless the Spec lane computed one. | Cheaper, but leaves the reporter on a page that cannot name a `referenceId`, so the locator is incomplete for exactly the reports that matter. |

**Do:** record the choice on #3802 and #3806 before either is picked up, since both spend their
budget on page wiring.

## D3 — the score rebaseline is versioned, and when

**Blocks:** 05.

Any resampler that is not `drawImage` produces different pixels, so the published parity numbers move
**once**, deliberately. Invariant I10 is what stops them moving a second time. The decision is not
*whether* but *how it is announced*:

**There is no version to bump yet — that is part of the decision.** `scoreImages` returns
`{percent, geometry}` and nothing else, and a repo-wide search finds no versioned parity-score
carrier and no committed score-baseline format. So a downstream reader currently has no way to tell
an old-kernel number from a rebaselined one.

**Do:** identify the carrier or introduce one *first*; then bump it, regenerate committed baselines
**in the same change**, and note it in the release notes. Never in a change that also alters
acceptance semantics — a moved number and a changed verdict in one diff cannot be told apart.

## D4 — the frame-vs-controls race in `refreshReportLink()`

**Blocks:** 01 directly — it is the difference between a correct locator and a subtly wrong one, and
01 is where locators start being written. 02 inherits it, since the index only ever sees what 01 wrote.

`refreshLinks()` fires the moment a control moves; provenance is recorded later, in the replacement
image's `onload`. Between those moments the visible pixels are the *previous* frame, so a locator
substituted from control state can describe a variant the reporter never saw.

**Do:** for the first landing, **disable the report affordance until the requested frame has
loaded**. Deriving from the successfully displayed frame is the better end state; it is also the one
that can be got subtly wrong and pass review. Take the crude version first and note the follow-up.

## D5 — the pixel semantics, decided before the fixtures are frozen

**Blocks:** 04 (the fixtures encode these answers), and therefore 05.

§4 deliberately specifies no pixel algorithm — earlier revisions carried a numbered pipeline and
every version of it had a real defect. What it gives instead is ten invariants plus an explicit list
of six open questions:

1. the portable pixel path — which resampler, since `drawImage`'s filter is not reproducible
   off-browser;
2. whether mask pixels participate in `edgeMask`;
3. the masked pass's denominator;
4. what "accepted contribution" means, given it can legitimately go **negative**;
5. sub-pixel rounding;
6. the match metric shared by the candidate gate and the resolution test, and whether it is aggregate
   or per-pixel.

**These cannot be left to batch 05, and an earlier draft of this plan did exactly that.** Batch 04
must ship a *passing* conformance runner whose fixtures pin intermediate planes and expected verdicts
— and every one of those expected bytes is a function of the six answers above. A fixture set frozen
before they are settled either encodes a guess or cannot be produced at all, and batch 05 then
"conforms" to whichever guess got written down.

**Do:** answer all six here, record each in the design doc as it is made, and only then let 04 freeze
fixtures. A decision made in code and not written down is how the previous three pipelines went
wrong.

> **Answered, all six, in §4 of the contract** — see
> [The pixel semantics, settled](../COMPONENT_PARITY_WORKFLOW.md#the-normative-contract), which is
> normative; this is the index into it.
>
> 1. **The resampler** is an area average over exact source footprints, per channel, on
>    non-premultiplied 8-bit RGBA, rounded half-up once at the end. No kernel radius and no
>    edge-extension rule, and it reduces to a box filter at integer ratios and to nearest-neighbour
>    on integer upscale — the three cases an implementation would otherwise special-case are one.
> 2. **Mask pixels do not participate in `edgeMask`.** Edge classification runs within each
>    separated region and an out-of-region neighbour contributes *no gradient term* rather than a
>    filler value, which is what stops the fill manufacturing or suppressing an edge at the
>    boundary. Same exclusion from `contentMask`'s dilation.
> 3. **The denominator is the scorer's own** — content-or-disagreeing coordinates — restricted to
>    unmasked ones. The all-masked case is already defined by `scorePlanes` returning `100` when it
>    measures nothing, and reusing that answer is what stops two engines picking two conventions.
> 4. **"Accepted contribution" is the accepted region's own regional match**, on the same scale as
>    `raw` and `unaccepted`, and explicitly not their difference — that subtraction goes legitimately
>    negative, which is the defect the old `accepted: 4.9` example encoded.
> 5. **Sub-pixel geometry rounds outward** to the enclosing integer box, at every transform, after
>    the arithmetic and never during it. Inward rounding is the direction that silently stops
>    covering pixels.
> 6. **The match metric is max absolute per-channel difference over R, G, B, A, per pixel**,
>    compared with `>` against `candidateTolerance`. Per-pixel rather than aggregate, because an
>    aggregate needs a second constant. The candidate gate and the resolution test share it.
>
> Answered against the measured population — six sites, only #40 glyph-sized — rather than against
> #40 alone, which is how §4 previously accumulated three wrong pipelines. Each answer is exercised
> by fixtures in
> [`fixtures/known-differences/`](../../../scripts/design-artifacts/fixtures/known-differences/), and
> the resampler has a group of its own so a kernel divergence fails *as* a kernel divergence.
>
> **What is still open is the score, and only the score.** These settle the gates, which is the half
> that has to be settled first because every gate resolves before any score is computed (I1). The
> separated-plane scoring path is batch 05's, measured against the same cases: each `expected.json`
> is a *partial* pin whose `pins` array names the keys a runner must check, and the score keys are
> the ones 05 adds.

---

## Loose ends (not blocking; file or fix opportunistically)

- **`stampPreviewDensities` has the same fold bug** `previewsByFunctionReplacing` was written to fix:
  it dedupes by id and *appends*, so a later bundle does not replace an earlier candidate. It was
  deliberately left alone because it feeds Figma export scale, not the live lane, and changing it
  moves exported artwork. Worth a scoped issue with a before/after export check.
- **`vscode-preview/main` baseline instability.** The visual-diff bot intermittently reports a
  phantom row: the fixture captures byte-identically at base and head across repeated runs
  (`9dd897a3…`), and the extra row changes identity between runs. Proven drift in the stored
  baseline, not in any PR's diff. Worth an issue so reviewers stop discounting real rows.
- **`ssim` / `globalSsim` in `format-compare.js` have no callers.** Decide in 05 whether they are
  removed or wired; leaving dead scoring code beside live scoring code invites a future reader to
  assume the wrong one is authoritative.
