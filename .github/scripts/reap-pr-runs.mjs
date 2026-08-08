// Cancels the workflow runs left behind when a PR closes.
//
// Driven by pr-run-reaper.yml. The logic lives here rather than inline in the
// workflow because it cancels things unattended: an over-reaching branch match
// kills CI for work that is still open, and an under-reaching one silently
// leaves the queue full. Both failure modes are invisible without tests, so
// reap-pr-runs.test.mjs pins the guards.

/**
 * @param {object} options
 * @param {object} options.github        authenticated octokit (github-script's `github`)
 * @param {object} options.core          github-script's `core`, for logging
 * @param {{owner: string, repo: string}} options.repo
 * @param {string} options.headRef       the closed PR's head branch name
 * @param {string} options.headRepo      `owner/name` of the head repo
 * @param {string} options.thisRepo      `owner/name` of the repo being reaped
 * @param {string} options.defaultBranch
 * @param {number} options.currentRunId  this run, which must not cancel itself
 * @returns {Promise<{cancelled: string[], failed: string[], skipped: string|null}>}
 */
export async function reapPrRuns({
  github,
  core,
  repo,
  headRef,
  headRepo,
  thisRepo,
  defaultBranch,
  currentRunId,
}) {
  const cancelled = []
  const failed = []

  // A fork PR's head branch name can collide with one of ours (both sides
  // routinely have `main`), and the branch filter below matches on name alone,
  // not on repo. Reaping a fork PR would cancel *our* runs on the same-named
  // branch. Fork runs are charged to the fork, so skipping costs us nothing.
  if (headRepo !== thisRepo) {
    const skipped = `head is ${headRepo}, not ${thisRepo}`
    core.info(`Skipping: ${skipped}.`)
    return { cancelled, failed, skipped }
  }

  // Belt and braces. A PR cannot target its own base, but a mis-triggered run
  // that reaped the default branch would cancel post-merge CI for every commit
  // in flight — the single most damaging thing this script could do.
  if (!headRef || headRef === defaultBranch) {
    const skipped = `refusing to reap ref ${headRef || '(empty)'}`
    core.info(`Skipping: ${skipped}.`)
    return { cancelled, failed, skipped }
  }

  for (const status of ['queued', 'in_progress']) {
    const runs = await github.paginate(github.rest.actions.listWorkflowRunsForRepo, {
      ...repo,
      branch: headRef,
      status,
      per_page: 100,
    })
    for (const run of runs) {
      if (run.id === currentRunId) continue
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
