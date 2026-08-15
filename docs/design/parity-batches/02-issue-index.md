# Batch 02 — the issue index, end to end

**Issues:** [#3804](https://github.com/yschimke/compose-ai-tools/issues/3804) (producer),
[#3805](https://github.com/yschimke/compose-ai-tools/issues/3805) (reader),
[#3806](https://github.com/yschimke/compose-ai-tools/issues/3806) (surfaces).
**Depends on:** [01](01-locator-and-report.md) — the producer parses the locator block back out of
issue bodies, so there is nothing to parse until 01 has been filing them.
**Ships:** **yes.** This is the first visible payoff and the point at which the epic's first four
acceptance criteria are met: open issues appear on the pages where the problem is visible.

**Read first:** [`../COMPONENT_PARITY_WORKFLOW.md`](../COMPONENT_PARITY_WORKFLOW.md) §3 and §6
steps 2, 4, 7. `ServeParityActivity.kt` is the pattern for the reader — copy its shape, do not design
a new one. `ServeTagIndexStore.kt` is a second, closer worked example, including the
`ServeCatalogStore` staging call.

> **Not the tag index.** This batch handles `parity/issues.json` — a snapshot of *GitHub issues*,
> schema `compose-preview-issues/v1`. The already-merged `ServeTagIndexStore` reads `tags/index.json`,
> the *element* index, `compose-preview-tags/v1`. Different artifact, different schema; both are "an
> index the serve host loads fail-soft", which is exactly why they get conflated.

Three issues, one batch, because a producer with no reader and a reader with no surface are both
untestable end to end — and the whole value is in the last hop.

## 2.0 — settle the wire shape first

**`compose-preview-issues/v1` does not yet exist as a field-level schema.** §3 says "shape as in the
epic" and the repo carries no manifest, no row example, and no fixture. A JavaScript producer and a
Kotlin reader cannot be built independently against a schema token, and the locator block from batch
01 is not an index schema — it is what gets *parsed into* one. So the first commit of this batch
defines the rows: issue state, labels, title, the locator scope fields, and in particular **whether
`overrides` stays canonical text or becomes an object** (it is text in the issue body; it does not
have to stay text in the index, but the choice has to be made once and written down).

The doc already prescribes the arrangement that keeps the two languages honest, and it is the same
one `parity-activity.mjs` uses today: a **pure** producer half `scripts/design-artifacts/parity-issues.mjs`
(no I/O, no network, unit-testable without `npm ci`) driven by an I/O half `emit-parity-issues.mjs`,
with the output committed as `scripts/design-artifacts/fixtures/parity-issues.json` **and loaded by
the Kotlin reader's own test**. That shared fixture is the only thing preventing silent drift — build
it in this batch, not after.

---

## 2a — producer (#3804)

A workflow reads issues, parses each body's locator block, and commits `parity/issues.json` to
`design-artifacts/`.

**A file-only commit propagates without a render.** `ServeCatalogRefresher` re-fetches on any branch
head move and `ServeCatalogStore.load` re-stages the whole tree, so a workflow committing *only*
`parity/issues.json` reaches every serving host within one refresh interval. The epic's "updating
this index must not require rerendering the catalog" is satisfiable exactly as written.

Traps:

- **Credentials are the hard part, and the default is the trap.** A GitHub App installation token
  expires in an hour, so it is minted per run, never stored. `actions/create-github-app-token` **must**
  be given `owner` and `repositories` naming the **catalog** repo. The default scopes the token to the
  repo the workflow runs in — the *source* repo, where the App is not installed and which is not the
  dispatch target. Taking the default either fails while minting or yields a token that cannot dispatch.
- **Name the permission explicitly:** the App needs **Contents: write** on the catalog repo, because
  that is what `POST /repos/{owner}/{repo}/dispatches` requires. Read-only Contents returns 403 and
  the trigger is silently dead — indistinguishable from "no issues changed".
- **The dispatch credential is not the read credential.** Contents: write on the catalog repo is what
  lets the workflow *fire and commit*; it grants nothing on the **source** repo whose issues are being
  scanned. For a public source the catalog workflow's own `GITHUB_TOKEN` can read issues, but for a
  private or permission-restricted one it cannot — the dispatch wakes the workflow and the source's
  issues are simply absent from the generated index, which looks identical to "that repo has no parity
  issues". Provision a catalog-side credential with **Issues: read** on every scanned repository, and
  make an unreadable source repo a loud failure rather than an empty section.
- **…and that credential turns successful reconciliation into a disclosure risk.** The rows carry
  issue title, labels, state and URL, and the producer commits them to the delivery branch — so
  scanning a **private** source repo into a **public** catalog publishes private issue metadata, and
  it does so on the path where everything is working. Decide the policy before enabling private-source
  reads: either require the delivery repository to be at least as restricted as every repo it scans,
  or publish a reduced row (identity and state, no title or labels) for sources more private than the
  destination. Make the check part of the producer, not a convention — the failure is silent, and by
  the time anyone notices, the titles are in a git history.
- **The cron backstop is not optional** — but it only backstops the *trigger*, not the credential.
  Per-source-repo provisioning is real setup work, and an unwired repo has nothing else to fall back
  on. That will be the normal state for a while. Carrying the changed issue in the dispatch payload
  improves the event path and does nothing for the reconciliation cron, which still has to read.
- **Closed rows are published, not dropped.** An issue referenced by any acceptance stays in the index
  with `state: "closed"`. Absence must never be read as closure — a consumer that cannot find a row
  reports *unknown*. Otherwise the first time the file fails to parse, every acceptance in the catalog
  is marked stale at once. Batch 06 depends on this directly.
- **The publish race needs care, and the two publishers need *different* modes.**
  `.github/actions/apply/lib/push-branch.sh` computes `TREE=$(git write-tree)` once *before* its retry
  loop and reparents that stale tree on a non-fast-forward, never merging the fetched parent — so a
  concurrent render can be silently dropped. Making a carry-forward mode opt-in (an env var naming the
  paths) is necessary but **not sufficient**, because the two publishers race in opposite directions:
  - the **render** publisher must carry the fetched parent's `parity/issues.json` **forward**;
  - the **index** publisher must replace **only its own path** on the fetched tip.

  Applying carry-forward symmetrically discards a freshly generated index; using the whole-tree helper
  as-is for the index rolls back a concurrent render. Two index jobs overlapping need cancellation or
  a re-query on retry, so an older snapshot cannot overwrite a newer one. Opt-in is required because
  other publishers share the helper. Test **both** publish orderings and index-vs-index.
- **Canonicalise issue URLs** to `owner`/`repo`/`number` before aggregating. Trailing slashes, `www.`,
  and mixed-case owners otherwise split one issue into several groups.

**Cross-repo:** the issue-triggered workflow lands in the catalog repo.

## 2b — reader (#3805)

`ServeParityIssues.kt` plus the `ServeCatalogStore` staging call plus fixture-backed tests. Serves
nothing yet; it lands before anything renders it.

- **The serve host never calls the GitHub API at page-render time.** Same rule that keeps it away from
  Figma URLs. Host and the offline design-parity run read the same committed file.
- **Validate, then reassemble.** A catalog is third-party data and an `html_url` from it is
  attacker-controlled. Parse to `owner`/`repo`/`number`, check each, and **rebuild** the URL — never
  forward the string.
- **Cap the row count**, in the same spirit as the acceptance budget. A hostile or broken index must
  not be able to exhaust a page.
- **Closed rows are legitimate content**, not noise to filter at load. Batch 06 needs to tell "closed"
  from "not in the file".
- Fixtures cover: valid, wrong schema token, truncated, oversized, a row with an unparseable URL, and
  a closed row.

Remember the three-part rule: producer, **staging call**, host loader. `ServeTagIndexStore` shipped
without the middle piece once and without a production caller once. Both were invisible until someone
went looking.

## 2c — surfaces (#3806)

**There is no component page, and one should not be invented.** The epic's "component page" is not a
route. The surfaces are the catalog landing grid (component *cards*), the viewer `/{system}/p/{id}`,
the focused comparison `/{system}/compare/{previewId}`, and the parity dashboard
(`ServeParityDashboard.kt`). Read "component page" as **"the page you are on when you see the
problem"** and the epic's presentation section is satisfied.

- Issue rows on all four surfaces.
- Dashboard splits: new differences needing triage; accepted known differences; components with open
  issues; and — once statuses exist (batch 06) — recorded differences that no longer reproduce, and
  closed issues with a surviving acceptance. **The last two columns can land after the rest.**
- Area classification surfaced from the low-cardinality labels: `area:{spec,component,preview,renderer,comparison}`
  and `parity:{regression,known-difference,verification-needed}`. **No label per component** (epic
  non-goal) — component identity lives in the body locator.

## Done when

- The producer round-trips: a locator block written by 01 parses back into the same identity, and a
  body with a *mangled* block is reported rather than silently skipped.
- The reader drops a malformed index wholesale and the session serves exactly as before — asserted,
  not assumed.
- All six fixtures pass, including the closed row surviving to the consumer.
- The publish race is covered in **three** orderings: render-then-index, index-then-render, and
  index-then-index. None of them may lose the other's output.
- A source repo the credential cannot read fails loudly rather than contributing an empty section.
- Issue rows are visible on all four surfaces, with an open issue and a closed one both represented.

## Visual evidence

This batch changes four rendered surfaces, so the PR **must** embed before/after PNGs for each. Add
the fixture(s) to `ServeWebFixtureTest` and register them with `pages-snapshot.spec.mjs` in the same
change, so every later change to these surfaces is diffed for free — that wiring is part of the batch,
not a follow-up.
