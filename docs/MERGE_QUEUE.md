# The merge queue

`main` is merged through a **GitHub merge queue**. This page is the record of
why, what it changed in the workflows, and the one part of it that lives in
repository settings rather than in a file.

The rules it implements are in [root AGENTS.md](../AGENTS.md); nothing here
restates one.

## Why

Merges land a **median 4 minutes apart** — 89% within 25 minutes of the previous
one — while the pipeline takes 20–25. Post-merge verification therefore could
not finish before the next merge superseded it. Measured over the last 100
push-to-`main` runs of each workflow (#5063):

| Workflow | cancelled | success |
| --- | --- | --- |
| `ci.yml` | **75%** | 15% |
| `integration.yml` | 72% | 14% |
| `codeql.yml` | 72% | 28% |
| `compose-preview.yml` | 64% | 34% |
| `daemon-harness.yml` | 62% | 24% |

That is a property of the cadence, not of the job durations: no amount of making
jobs cheaper closes a 4-minute gap against a 20-minute pipeline. #5062 did the
mechanical part (~198 → ~110 job-minutes per merge) and moved the ratio not at
all.

Four things followed from it, and the queue fixes all four:

1. **"`main` is green" was not a statement anyone could make.** Only ~15% of
   merges were ever verified end to end. A merge-queue run verifies the batch,
   once, and finishes.
2. **Cancelled runs still burned the runner pool.** A cancelled `ci.yml` push run
   lived a median 4.3 minutes holding ~10–16 slots — a direct cause of the p90
   18–25 minute queue waits on PR jobs. Those runs no longer exist.
3. **`compose-preview` baselines were stale during merge bursts**, despite
   `cancel-in-progress: false`, because GitHub keeps at most one *pending* run
   per concurrency group. A batch lands its N commits as one push, so the push
   cadence becomes the batch cadence and the pending slot is free again by the
   time the next batch arrives.
4. **Nothing tested the actual merge result.** PR CI tests the PR head against a
   base that has usually moved by the time it lands. A merge group *is* the
   merge result.

## What the queue runs

Two lanes, and the difference between them is the whole design:

- **`merge_group` — verdicts.** A workflow whose `push: [main]` lane existed to
  say *yes this is fine* now runs on `merge_group` instead, and its push lane is
  gone. The tree a merge group holds is the tree GitHub is about to fast-forward
  `main` to, so a push run of the same suite would verify an identical tree a
  second time.

  `ci.yml`, `integration.yml`, `no-agent-attribution.yml` (the `scan` job),
  `pr-title.yml`, `format.yml`, `daemon-harness.yml`, `report-schemas.yml`,
  `agent-entrypoints.yml`.

- **`push: [main]` — side effects.** A workflow whose main lane *does* something
  — publishes, tags, deploys, records a baseline — keeps its push trigger. A
  merge group's SHA never becomes a commit on `main` (PRs are squash-merged, so
  `main` gets a different SHA for the same tree) and a batch can be dequeued and
  never land at all, so a side effect fired from one would point at a revision
  nobody can check out.

  `compose-preview.yml` (baselines), `release-please.yml`, `snapshot.yml`,
  `pages.yml`, `gradle-cache-seed.yml`, `design-artifacts.yml`,
  `no-agent-attribution.yml` (the `drift` backstop), `samples-sdk21.yml` and
  `install-action-test.yml` (both narrowly `paths:`-filtered, and `merge_group`
  supports no path filter).

Nightly drift detectors are unaffected: they were already gated to `schedule` /
`workflow_dispatch` and stay there, so a batch never waits on a 12-minute
anti-bit-rot job. Two of them (`Agent Audit Samples`, and the XR/bundle jobs
that share the pattern) are *required* checks that report `skipped` in the merge
group — which counts as passing, exactly as it already does on a release PR.
That is why those jobs stay defined rather than deleted: a required check that
never reports at all blocks every batch forever.

### Scope classifiers do not narrow a batch

`path-scope.py` and `change-scope.py` enable every group on any non-`pull_request`
event, so a merge-group run is always the full suite. That is deliberate and
worth keeping: a required check that can be talked out of running by the diff it
is meant to judge is not a gate. It is also why `merge_group` carries no
`paths-ignore` even where the `pull_request` trigger does.

### Release PRs pay one batch

Release-please PRs skip every job on the PR lane (they only bump the manifest,
the CHANGELOG and version strings), and then run the full suite once inside the
merge group. The skip cannot be carried into the queue: a `merge_group` payload
has no `head_ref`, no PR author, and — under squash merge — no stable
commit-message shape to key off, and a required check must not guess. It is not
wasted anyway: `gradle.properties` is one of the files release-please rewrites,
and it is a `globalPaths` entry in `ci-paths.json` precisely because a version
bump there can break the build.

## The two required checks that had to be rebuilt

Required checks are matched **by name in the merge-group context**. Six of the
eight in the `Protect Main` ruleset come from `ci.yml` and `integration.yml` and
simply needed the trigger. Two are PR-shaped and did not:

- **`Conventional Commit title`** (`pr-title.yml`). The action reads the PR title
  out of its own event payload, and a merge group has no PR — a batch can hold
  several, and the payload names none of them. The `queue` job checks the thing
  the title exists to produce instead: every commit subject in
  `merge_group.base_sha..head_sha`. Under squash merge those subjects *are* the
  PR titles, and they are what release-please reads. GitHub's own merge-queue
  plumbing commits are exempt — failing on those would deadlock every batch.

- **`Reject agent attribution`** (`no-agent-attribution.yml`). This one gets
  *better* in the queue, and it is the reason to want the queue even without the
  cadence argument. On the PR lane the squash message does not exist yet, so the
  `Co-authored-by:` credits GitHub auto-adds for each distinct branch commit
  author are invisible to it — that is how `5aacb786` got its trailer, and why
  `drift` had to exist as a post-mortem. In the merge group those commits are
  real and in range, so the trailer is now a **blocked merge** rather than
  something `drift` reports after the fact. `drift` stays regardless: a queue can
  be switched off and a bypass actor can be added.

Both are one job reporting one context, gated by `github.event_name`, rather than
two jobs sharing a name — the ruleset cannot tell two contexts of the same name
apart.

## Enabling it: the part that is not in this repository

The queue itself is a setting on the `Protect Main` ruleset. Nothing in a diff
can turn it on.

**Order matters.** Enable the queue only *after* the workflow changes are on
`main`. A queue enabled first would demand required checks in a `merge_group`
context that no workflow on `main` yet produces, and every batch would time out.

1. Merge the workflow changes normally. Between this step and the next, `main`
   has PR-gate coverage only — which is, in practice, what it had before: the
   post-merge lane completed on ~15% of merges.
2. Settings → Rules → `Protect Main` → **Require merge queue**.
3. Merge method **Squash**, matching the repository's
   `squash_merge_commit_message=BLANK` setting, on which the `Conventional
   Commit title` queue job depends.
4. Maximum PRs to build **5**, minimum **1**; wait to build **5 minutes**;
   maximum time to merge **60 minutes**. Five is chosen against the measured
   cadence: it is roughly what accumulates in 20 minutes at 4-minute intervals,
   so a batch is usually full by the time the previous one finishes, and a batch
   that fails costs at most five requeues.
5. Leave the eight required checks as they are — they now report from the
   merge-group context under the same names.

### Rolling back

Turn off **Require merge queue** and restore `push: [main]` on the workflows
listed under *verdicts* above. The push lanes were removed, not disabled, so a
rollback is a revert of this change plus the setting. Nothing else in the
repository depends on the queue existing.

## Consequences worth knowing

- **A red merge-group run does not mark a PR red.** It dequeues it. Look at the
  merge queue, not the PR's own checks, when a merge silently does not happen.
- **A push to `main` is now a batch, not a merge.** Anything reading
  `github.event.before..github.sha` (the `drift` job, for one) sees N commits
  where it used to see one. `drift` already handled ranges.
- **Integration gallery branches refresh on the nightly cron only.** They used to
  refresh from the push lane for the two `pr_blocking` cells; that lane is gone,
  and publishing them from a speculative merge group is exactly the mistake the
  side-effect rule above exists to prevent. 72% of those push runs were cancelled
  before reaching the publish job anyway, so in practice this is fresher.
