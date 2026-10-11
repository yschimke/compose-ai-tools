// Cancels the workflow runs left behind when a PR closes (driven by pr-run-reaper.yml).
//
// Lives here rather than inline because it cancels unattended: over-reaching kills live CI,
// under-reaching silently leaves the queue full. reap-pr-runs.test.mjs pins every guard below.

// The only run events ever cancelled; dispatches, pushes and schedules belong to someone else.
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

  // No bail-out on `headRef === defaultBranch`: a PR whose head is the default branch is
  // legitimate. Post-merge CI on the default branch is protected by the event allowlist (it runs on
  // `push`).

  // Re-read the PR rather than trusting the payload: it may have been reopened, or a new PR opened
  // from the same branch, since the close. On failure, skip — not reaping is the cheaper mistake.
  let pr
  try {
    ;({ data: pr } = await github.rest.pulls.get({ ...repo, pull_number: prNumber }))
  } catch (error) {
    return skip(`could not re-read PR #${prNumber} (${error.message})`)
  }
  if (pr.state !== 'closed') return skip(`PR #${prNumber} is ${pr.state} again`)

  // Runs created at or after the close belong to whatever came next. `>=` because timestamps are
  // second-precision and cancelling live work is the worse mistake.
  const cutoff = closedAt ? Date.parse(closedAt) : NaN

  // Is anything else open on this exact head? Fork runs carry no PR association, so the
  // empty-association fallback below is only safe when no other open PR shares the branch. On
  // failure, assume ambiguity.
  //
  // A deleted fork nulls `head.repo`, so `headRepo` arrives empty for orphaned fork runs. Those
  // can't be matched by repo, so they must name this PR outright to be reaped.
  const headRepoKnown = Boolean(headRepo)

  let ambiguousHead = true
  if (!headRepoKnown) {
    core.info('Head repository is gone (deleted fork); only reaping runs that name this PR.')
  } else {
    try {
      const openOnSameHead = await github.paginate(github.rest.pulls.list, {
        ...repo,
        state: 'open',
        head: `${headRepo.split('/')[0]}:${headRef}`,
        per_page: 100,
      })
      // Any open PR here means stop, including this one: it has been reopened since `pulls.get`.
      ambiguousHead = openOnSameHead.length > 0
    } catch (error) {
      core.warning(`Could not check for other open PRs on ${headRef}: ${error.message}`)
    }
  }

  // One listing, filtered locally: per-status queries are separate snapshots, and a run moving
  // between statuses could appear in neither.
  //
  // The Actions list endpoint caps at 1000 results, which a long-lived branch's push history can
  // fill, leaving older stuck runs unreached. That is detected and reported rather than tolerated
  // silently; narrowing per status would reintroduce the gap above, so it's left to a human.
  const RESULT_WINDOW = 1000
  let runs = []
  try {
    runs = await github.paginate(github.rest.actions.listWorkflowRunsForRepo, {
      ...repo,
      branch: headRef,
      per_page: 100,
    })
    if (runs.length >= RESULT_WINDOW) {
      core.warning(
        `Listed ${runs.length} runs for ${headRef}, at or beyond the ${RESULT_WINDOW}-result ` +
          'API window. Older leftovers may not be visible and will not be reaped.',
      )
    }
  } catch (error) {
    // Never let a listing failure fail the workflow (and redden an already-merged PR).
    failed.push(`list runs: ${error.message}`)
  }

  for (const run of runs) {
    // Terminal runs are the reason the snapshot is cheap to take and the
    // reason it has to be filtered: there is nothing to cancel on them.
    if (run.status === 'completed') continue

    if (run.id === currentRunId) continue

    // Only PR-triggered runs: manual `workflow_dispatch` investigation runs have no PR association
    // and would otherwise be swept up, and post-merge default-branch CI (`push`) is excluded
    // categorically.
    if (!PR_EVENTS.has(run.event)) continue

    // The branch filter matches by name only; without this a fork PR from `main` would sweep our
    // `main` runs. Per-run, so a fork's own leftovers (recorded in this repo) are still reaped.
    if (headRepoKnown && run.head_repository?.full_name !== headRepo) continue

    // One branch can carry two open PRs with different bases, so believe GitHub's association when
    // it makes one. Empty (always, for fork runs) means "no claim" and falls through to the other
    // checks.
    const associated = run.pull_requests ?? []
    if (associated.length > 0) {
      if (!associated.some((p) => p.number === prNumber)) continue
    } else if (!headRepoKnown || ambiguousHead) {
      // Empty association and something else is live on this exact head — we
      // cannot tell whose run this is, so leave it. See `ambiguousHead` above.
      continue
    }

    // The attempt's start, not the run's creation: a re-run keeps `created_at`, and a deliberate
    // `/rerun` on a closed PR (pr-commands.yml) must not look like a leftover.
    const began = Math.max(
      Date.parse(run.created_at),
      Date.parse(run.run_started_at ?? run.created_at),
    )
    if (!Number.isNaN(cutoff) && began >= cutoff) continue

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

  core.info(`Cancelled ${cancelled.length} leftover run(s) on ${headRef}.`)
  for (const entry of cancelled) core.info(`  ${entry}`)
  // Never fail the workflow over this. The reaper is an optimisation; a
  // transient API error must not put a red check on an already-merged PR.
  for (const entry of failed) core.warning(`Could not cancel ${entry}`)

  return { cancelled, failed, skipped: null }
}
