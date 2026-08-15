# Batch 01 — the locator contract and the report body

**Issues:** [#3801](https://github.com/yschimke/compose-ai-tools/issues/3801) (contract),
[#3802](https://github.com/yschimke/compose-ai-tools/issues/3802) (emit it).
**Depends on:** nothing. D2/D4 from [batch 00](00-decisions.md) decide *where* the form lives and how
it stays honest, so settle those first if 00 is being done at all.
**Blocks:** 02, 03, 04, 05, 06 — every one of them keys off this identity.
**Ships:** partly. Nothing new appears on a page, but every issue filed after this lands is
machine-identifiable, and issues filed before it need their bodies hand-edited later. **That is the
argument for doing this batch first even if nothing else gets done.**

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §2 and §6 step 1;
`cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeIssueReport.kt` in full (220 lines — the
KDoc explains why the link is prefilled rather than filed server-side, which constrains everything
here); `viewer.js` around `refreshReportLink()` (~line 657).

---

## What to build

### 1. `compose-parity-locator/v1`

Identity is `repository + system + componentId + previewId + referenceId + variant + overrides`.
Serialised into the issue body as a **fenced code block**, alongside — not instead of — the prose
table `ServeIssueReport.body` already writes. Both come from one `Context` so they cannot disagree.

Rules that are not negotiable, each because it has a silent failure mode:

- **`previewId` is the *served* id, not the daemon discovery id.** This mistake has been made before
  (see the `previewIds` note in `docs/public-preview-server.md`); the page still renders and every
  row quietly loses its link. `ServeCatalogStore.previewIdFor` / `catalogPreviewId()` is the rule.
- **The preview-server URL is a reproduction link, never the identity.** `ServeIssueReport.withoutToken`
  already strips the capability out of anything reaching a body, which is a second reason it cannot be.
- **`variant` is axes only.** Live overrides are *not* folded in. One fact, one field — two
  representations means two spellings and no rule for which wins.
- **`overrides` is the whole normalised map the render lane received** — display fields, size fields,
  overlay toggles, `knob.*`, `rc.*` — not an enumerated list of families. A knob *is* the component's
  state, so an enumerated scope lets an acceptance authored at `knob.label=Send` match
  `knob.label=Delete`, and the list drifts silently the first time a family is added.
- **`overrides` is one-line canonical JSON**, keys sorted by code point, always present, `{}` meaning
  the default render. `key=value` joined by `;` was tried and cannot round-trip its own inputs: knob
  values are free text, so a label of `a;knob.color=red` reads back as two overrides.
- **Fenced block, not an HTML comment.** A comment is invisible in the rendered issue, so a reporter
  editing the body cannot see they have broken the index.

### 2. Emit it

Extend `ServeIssueReport.Context` with `componentId`, `referenceId`, variant axes, active overrides,
comparison URL and raw scores; render the block from it.

**This is not pure Kotlin, and that is the whole difficulty.** The server fills the hidden `body`
input for the settings the page was *served* at; `refreshReportLink()` then rewrites exactly one
thing — it swaps `{{render}}` for the live `/render` URL. A locator built entirely server-side
records the **default** variant while the embedded screenshot shows whatever the reporter dialled in.
The index would key the issue to one identity and the pixels would show another, and the Phase 3
acceptance lookup would then miss.

So: override-dependent fields get their own placeholders alongside `RENDER_PLACEHOLDER`, substituted
by `refreshReportLink()` from live viewer state, on the existing **"write an input value, never an
href"** rule.

## Traps

- **Substitute from the displayed frame, not from the controls** — see [D4](00-decisions.md#d4--the-frame-vs-controls-race-in-refreshreportlink).
  Landing the crude version (disable the affordance until the requested frame has loaded) is
  explicitly acceptable and explicitly preferred over a subtly-wrong derivation.
- **Reporting stays disabled outright in the Live, Wasm and Remote Compose lanes** — not redirected to
  the focused comparison. Those lanes paint into a canvas or iframe while `#cp-img` / `data-cp-blob`
  go on describing the static snapshot the visitor arrived from (`viewer.js` already calls that blob
  "a stale bystander"). Overrides are query parameters; *interaction* is not, so a redirect lands the
  reporter on pixels nobody saw. Transferring the displayed frame and its runtime state is a much
  larger piece of work — scope it deliberately, do not smuggle it in here.
- **Pick the surface that knows the score.** The viewer's always-available number is `scoreSvgUrls` —
  PNG against the *generated SVG*, a render-fidelity measurement unrelated to the design reference.
  Emitting it as a parity score produces a plausible, mislabelled number feeding an index. Either
  move the form to the focused comparison (D2, recommended) or omit the score field entirely when the
  Spec lane has not computed one.
- Anything reaching a body goes through `withoutToken`. New URL-bearing fields must too, and a test
  should prove it for each.

## Done when

- A round-trip test: build a `Context`, render the body, parse the fenced block back, and get the
  same locator — including an `overrides` value containing `;`, `=`, a newline, and a non-ASCII
  label.
- Canonical JSON is byte-stable: same map, different insertion order, identical string.
- A `ServeWebFixtureTest` fixture pins the rendered body for a catalog preview *with* overrides in
  force, so a future change to the prose table cannot silently reshape the block.
- A JS test proves the placeholders are substituted into the input's **value**, and that no `href`
  is written.
- Token-bearing URLs are absent from every emitted field.

## Visual evidence

The report affordance is UI. If its enabled/disabled state or placement changes, capture the
before/after through the preview-harness (`ServeWebFixtureTest` → `pages-snapshot.spec.mjs`) and
embed the PNGs in the PR body — a description of the change is not evidence. If the batch lands
without touching visible pixels, say so explicitly in the PR.
