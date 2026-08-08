// Cancels the workflow runs left behind when a PR closes.
//
// Driven by pr-run-reaper.yml. The logic lives here rather than inline in the
// workflow because it cancels things unattended: an over-reaching match kills
// CI for work that is still open, and an under-reaching one silently leaves the
// queue full. Both failure modes are invisible without tests, so
// reap-pr-runs.test.mjs pins every guard below.

// The only run events this script will ever cancel. Everything else on the
// branch — manual dispatches, pushes, schedules — belongs to someone or
// something other than the closed PR.
const PR_EVENTS = new Set(['pull_request', 'pull_request_target'])

/**
 * @param {object} options
 * @param {object} options.github        authenticated octokit (github-script's `github`)
 * @param {object} options.core          github-script's `core`, for logging
 * @param {{owner: string, repo: string}} options.repo
 * @param {number} options.prNumber      the closed PR
 * @param {string} options.headRef       its head branch name
 * @param {string} options.headRepo      `owner/name` of the head repo (the fork, for fork PRs)
 * @param {string} options.thisRepo      `owner/name` of the repo being reaped
 * @param {string} options.closedAt      ISO timestamp the PR closed at
 * @param {number} options.currentRunId  this run, which must not cancel itself
 * @returns {Promise<{cancelled: string[], failed: string[], skipped: string|null}>}
 */
export async function reapPrRuns({
  github,
  core,
  repo,
  prNumber,
  headRef,
  headRepo,
  thisRepo,
  closedAt,
  currentRunId,
}) {
  const cancelled = []
  const failed = []
  const skip = (reason) => {
    core.info(`Skipping: ${reason}.`)
    return { cancelled, failed, skipped: reason }
  }

  if (!headRef) return skip('head ref is empty')

  // A PR whose *head* is the default branch is legitimate — merging `main`
  // forward into a release branch is the usual case — so this deliberately does
  // not bail out on `headRef === defaultBranch`. An earlier version did, on the
  // reasoning that a PR cannot target its own base; that conflated head with
  // base, and silently stranded every such PR's leftovers.
  //
  // What actually protects post-merge CI on the default branch is the run-event
  // allowlist below: that CI runs on `push`, so it is excluded by kind rather
  // than by name-matching. That is a stronger guarantee than this check ever
  // was, and unlike this check it does not depend on guessing which branch
  // names matter.

  // Re-read the PR instead of trusting the event payload. Between the close and
  // this job getting a runner, the PR can be reopened, or a new PR can be
  // opened from the same branch — in both cases the branch has live work again
  // and its runs are not leftovers. If the read fails we skip rather than
  // guess: not reaping costs a few queue slots, over-reaping costs someone's CI.
  let pr
  try {
    ;({ data: pr } = await github.rest.pulls.get({ ...repo, pull_number: prNumber }))
  } catch (error) {
    return skip(`could not re-read PR #${prNumber} (${error.message})`)
  }
  if (pr.state !== 'closed') return skip(`PR #${prNumber} is ${pr.state} again`)

  // Anything created at or after the close belongs to whatever came next — a
  // reopen, or a fresh PR on the same branch — not to the PR we are cleaning up.
  // `>=` rather than `>` because GitHub timestamps are second-precision: a run
  // created in the same second as the close is ambiguous, and the two mistakes
  // are not equally bad. Skipping a real leftover costs one queue slot;
  // cancelling live work costs someone their CI.
  const cutoff = closedAt ? Date.parse(closedAt) : NaN

  // Every non-terminal status, not just the two obvious ones. A run sitting in
  // `waiting` (blocked on a deployment gate) or `requested`/`pending` is not
  // going to pass through `queued` on its way out — it can sit there
  // indefinitely, which is exactly the kind of straggler this script exists to
  // clear. Terminal statuses are not listed: there is nothing to cancel.
  for (const status of ['queued', 'in_progress', 'requested', 'waiting', 'pending']) {
    let runs
    try {
      runs = await github.paginate(github.rest.actions.listWorkflowRunsForRepo, {
        ...repo,
        branch: headRef,
        status,
        per_page: 100,
      })
    } catch (error) {
      // Listing is as fallible as cancelling. Letting this escape would fail
      // the workflow and put a red check on an already-merged PR, which is
      // exactly what this script promises not to do.
      failed.push(`list ${status} runs: ${error.message}`)
      continue
    }

    for (const run of runs) {
      if (run.id === currentRunId) continue

      // Only ever cancel PR-triggered runs. Two reasons, and the second is what
      // makes the rest of this function safe:
      //
      // 1. A manual `workflow_dispatch` on a PR branch has no PR association,
      //    so the empty-association fallback below would otherwise sweep it up.
      //    vscode-extension-e2e.yml exists partly to be run manually on any
      //    branch for regression work; killing someone's investigation run
      //    mid-flight would be a real cost for no queue benefit.
      // 2. Post-merge CI on the default branch is a `push` run, so this
      //    excludes it *categorically* rather than by heuristic — which is what
      //    lets a PR whose head is `main` be reaped at all (see below).
      if (!PR_EVENTS.has(run.event)) continue

      // The branch filter matches on name alone, not repo. Without this a fork
      // PR from a branch named `main` would sweep up our `main` runs. Filtering
      // per run — rather than skipping fork PRs wholesale — means a fork's own
      // leftover runs, which are recorded in this repo, still get reaped.
      if (run.head_repository?.full_name !== headRepo) continue

      // One branch can carry two open PRs at once (different bases), and the
      // other PR's runs can predate this close, so they clear the cutoff. When
      // GitHub tells us which PRs a run belongs to, believe it.
      //
      // Only when it says something, though: `pull_requests` is empty for fork
      // runs, so *requiring* a match would silently stop reaping exactly the
      // fork leftovers this script was fixed to catch. Empty means "no claim",
      // and we fall through to the repo and cutoff checks.
      const associated = run.pull_requests ?? []
      if (associated.length > 0 && !associated.some((p) => p.number === prNumber)) continue

      if (!Number.isNaN(cutoff) && Date.parse(run.created_at) >= cutoff) continue

      try {
        await github.rest.actions.cancelWorkflowRun({ ...repo, run_id: run.id })
        cancelled.push(`${run.name} (${run.id})`)
      } catch (error) {
        // A run that finished between the list and the cancel returns 409.
        // That is the race resolving itself, not a failure worth reporting.
        if (error.status === 409) continue
        failed.push(`${run.name} (${run.id}): ${error.message}`)
      }
    }
  }

  core.info(`Cancelled ${cancelled.length} leftover run(s) on ${headRef}.`)
  for (const entry of cancelled) core.info(`  ${entry}`)
  // Never fail the workflow over this. The reaper is an optimisation; a
  // transient API error must not put a red check on an already-merged PR.
  for (const entry of failed) core.warning(`Could not cancel ${entry}`)

  return { cancelled, failed, skipped: null }
}
