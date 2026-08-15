# Batch 06 — resolution, closure, and the consumer-facing docs

**Issues:** [#3811](https://github.com/yschimke/compose-ai-tools/issues/3811) (statuses + closure),
[#3812](https://github.com/yschimke/compose-ai-tools/issues/3812) (documentation).
**Depends on:** [05](05-acceptance-engines.md) (the statuses) and [02](02-issue-index.md) (the issue
index — the stale join has nothing to join against without it).
**Ships:** **yes.** The loop closes: a fixed difference deletes its acceptance and closes its issue.

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §6 Phase 4 and
step 13.

---

## 6a — surface the statuses, and act on them (#3811)

**This step surfaces statuses; it does not compute them.** `resolved` detection lands with the scorer
in batch 05, because that engine's own fixtures require it. What lands here is `resolved` /
`invalidated` / `refused` on the dashboard and the gate, plus **stale**.

### Stale is a second axis, not a status

`status` is what a **comparison** concluded, and is exactly what the two engines must agree on.
Stale/unknown come from the **issue index**, which one engine may have and the other may not. Folding
them into `status` would make the conformance fixtures depend on a file outside the contract.

So the join happens *here* — at the dashboard and the gate — over an acceptance's mandatory `issue`
URL, producing `open` / `closed` / `unknown` per acceptance. **The evaluation result gains no field.**

- **Only one combination is stale**: `closed` **and** status not `resolved`. `resolved` + `closed` is
  the loop having *completed*. `unknown` is never stale.
- **Stale requires positive evidence of closure, never inference from absence.** The index is
  fail-soft, capped, can lag between regenerations, and does not cover source repos whose dispatch
  credential nobody has wired. Treating absence as closed would mark live acceptances stale across an
  entire catalog the first time the file failed to parse — which is exactly why batch 02 publishes
  closed rows.

### The loop needs an owner

Nothing above actually *removes* anything. `resolved` means deleting the acceptance, and an issue
closes once every linked acceptance has resolved — but surfacing a status does neither.

Both are committed-file operations, so both belong in a PR, and **the ordering matters**: delete first
and the issue loses the only record of what it was about; close first and the surviving siblings are
briefly stale against a closed issue. So they happen **in one change** — a PR that deletes the
resolved records and their directories *and* closes the issue via a closing keyword in its
description, so the merge does both atomically.

**With one restriction:** "every acceptance linked to this issue" is not evaluable from a run that
reads one `known-differences.json`. `v1` gives an issue a **single owning document**, and a run that
cannot establish ownership deletes its resolved records but **omits the closing keyword**, leaving
closure to whoever can see the other referencing documents. Deletion is always safe locally; closure
is the half that needs knowledge a single run does not have.

> This repo squash-merges from the **PR title**, not the body — so a closing keyword in the body does
> its work through GitHub's PR-closes-issue linkage, not through the commit message. Verify that on
> the first one rather than assuming.

## 6b — documentation (#3812)

Document the reporting → triage → acceptance → verification → closure loop in
`docs/public-preview-server.md`, beside the existing parity view section.

This is the **consumer-facing** half. `COMPONENT_PARITY_WORKFLOW.md` is the design record — why each
rule exists and what breaks without it — and it should stay that. What a catalog owner needs is
shorter and different in kind:

- how to file a parity issue from a comparison, what the locator block is for, and **that editing it
  breaks the index**;
- which labels to use — `area:{spec,component,preview,renderer,comparison}`,
  `parity:{regression,known-difference,verification-needed}` — and that there is deliberately **no
  per-component label**;
- how to author an acceptance: the directory layout, the mandatory tracking issue, the mask encoding
  rules, and the fact that **a tolerance which needs to be large is evidence the acceptance is wrong**;
- what each status means when it appears on the dashboard, and what to do about it;
- the closure step, and why deletion and issue-closing happen in one PR.

### Setup documentation for the credential path

This belongs in prose where someone provisioning a new source repo will find it, because the default
is the thing that looks right and silently is not:

- the App must be installed on the **catalog** repo with **Contents: write**;
- `actions/create-github-app-token` must be given `owner` **and** `repositories`;
- a 403 there looks exactly like "no issues changed".

### Worth noting, not building here

Mask authoring has **no UI** in this plan — a human hand-writes `known-differences.json` and produces
the two PNGs. Probably acceptable for the first acceptances, since they should be rare and deliberate.
But "export this selection as an acceptance stub" from the focused comparison is the obvious
follow-up, and is worth **deciding on** rather than discovering. File it as an issue in this batch
whichever way the decision goes.

## Done when

- The dashboard shows stale acceptances, and an index that fails to parse produces `unknown`
  everywhere rather than `stale` anywhere — asserted with a fixture, since this is the failure that
  would discredit the whole dashboard at once.
- `resolved` + `closed` renders as completion, not as a problem.
- One real acceptance has gone all the way round: reported, accepted, fixed, deleted, issue closed —
  and the PR that did it is linked from the docs as the worked example.
- `docs/public-preview-server.md` covers all six bullets above plus the credential setup.

## Visual evidence

Dashboard changes are visual. Before/after through the harness, both themes, with at least one row in
each new state.
