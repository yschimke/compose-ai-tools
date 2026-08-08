// Self-test for the PR run reaper. Run: node --test .github/scripts/reap-pr-runs.test.mjs
//
// This runs on every PR (the `actions-tests` job in ci.yml) because the reaper
// cancels workflow runs unattended, and every guard it has exists because the
// failure it prevents is invisible until after it has happened: cancelling a
// `push` run wipes post-merge CI for commits in flight; matching on branch name
// alone lets a fork's `main` sweep up ours; reaping without a close-time cutoff
// kills the checks of whatever reopened or re-used the branch; and sweeping up a
// manual dispatch kills someone's regression investigation.

import assert from 'node:assert/strict'
import test from 'node:test'

import { reapPrRuns } from './reap-pr-runs.mjs'

const REPO = { owner: 'yschimke', repo: 'compose-ai-tools' }
const THIS_REPO = 'yschimke/compose-ai-tools'
const CLOSED_AT = '2026-08-08T12:00:00Z'
const BEFORE = '2026-08-08T11:00:00Z'
const AFTER = '2026-08-08T13:00:00Z'

function run(id, name, status, overrides = {}) {
  return {
    id,
    name,
    status,
    created_at: BEFORE,
    event: 'pull_request',
    head_repository: { full_name: THIS_REPO },
    ...overrides,
  }
}

function harness({
  runs = [],
  cancelError,
  listError,
  prState = 'closed',
  prError,
  openOnSameHead = [],
  openOnSameHeadError,
} = {}) {
  const calls = { listed: [], cancelled: [], headQueries: [] }
  const github = {
    paginate: async (fn, params) => {
      if (fn === 'pulls.list') {
        calls.headQueries.push(params.head)
        if (openOnSameHeadError) throw openOnSameHeadError
        return openOnSameHead
      }
      // One snapshot, no status filter — mirrors the real call.
      calls.listed.push({ branch: params.branch, status: params.status })
      if (listError) throw listError
      return runs
    },
    rest: {
      pulls: {
        list: 'pulls.list',
        get: async () => {
          if (prError) throw prError
          return { data: { state: prState } }
        },
      },
      actions: {
        listWorkflowRunsForRepo: 'listWorkflowRunsForRepo',
        cancelWorkflowRun: async ({ run_id }) => {
          const error = cancelError?.(run_id)
          if (error) throw error
          calls.cancelled.push(run_id)
        },
      },
    },
  }
  const warnings = []
  const core = { info: () => {}, warning: (m) => warnings.push(m) }
  return { github, core, calls, warnings }
}

const BASE = {
  repo: REPO,
  prNumber: 42,
  headRef: 'agent/some-branch',
  headRepo: THIS_REPO,
  thisRepo: THIS_REPO,
  closedAt: CLOSED_AT,
  currentRunId: 999,
}

test('cancels queued and in-progress runs on the closed PR branch', async () => {
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued'),
      run(2, 'VS Code Extension E2E', 'queued'),
      run(3, 'Integration', 'in_progress'),
    ],
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1, 2, 3])
  assert.equal(result.skipped, null)
  // A single listing with no status filter: per-status queries are separate
  // snapshots, and a run advancing between them escapes every one.
  assert.equal(calls.listed.length, 1)
  assert.equal(calls.listed[0].status, undefined)
  assert.equal(calls.listed[0].branch, 'agent/some-branch')
})

test('never cancels itself', async () => {
  const { github, core, calls } = harness({
    runs: [run(999, 'PR Run Reaper', 'in_progress'), run(7, 'CI', 'in_progress')],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [7])
})

test('reaps a fork PR’s own runs, which are recorded in this repo', async () => {
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { head_repository: { full_name: FORK } })],
  })
  const result = await reapPrRuns({ ...BASE, headRef: 'main', headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [1])
  assert.equal(result.skipped, null)
})

test('a fork branch named main does not sweep up this repo’s main runs', async () => {
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued', { head_repository: { full_name: THIS_REPO } }),
      run(2, 'CI', 'queued', { head_repository: { full_name: FORK } }),
    ],
  })
  await reapPrRuns({ ...BASE, headRef: 'main', headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [2])
})

test('reaps a PR whose head is the default branch, but never its push runs', async () => {
  // Merging `main` forward into a release branch is a legitimate PR with
  // headRef === the default branch. Its leftovers should be reaped; the
  // default branch's own post-merge CI (a `push` run) must not be touched.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued', { event: 'pull_request', pull_requests: [{ number: 42 }] }),
      run(2, 'CI', 'queued', { event: 'push' }),
    ],
  })
  const result = await reapPrRuns({ ...BASE, headRef: 'main', github, core })
  assert.deepEqual(calls.cancelled, [1])
  assert.equal(result.skipped, null)
})

test('leaves a manual workflow_dispatch run alone', async () => {
  // A manual one-off has no PR association, so the empty-association fallback
  // would sweep it up without the event allowlist. vscode-extension-e2e.yml is
  // run this way for regression work.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'VS Code Extension E2E', 'in_progress', { event: 'workflow_dispatch' }),
      run(2, 'CI', 'in_progress'),
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [2])
})

test('reaps runs stuck in waiting, requested and pending', async () => {
  // A run blocked on a deployment gate never passes through queued or
  // in_progress on its way out — it can sit in `waiting` indefinitely.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'Deploy', 'waiting'),
      run(2, 'CI', 'requested'),
      run(3, 'Integration', 'pending'),
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1, 2, 3])
})

test('never cancels an already-completed run', async () => {
  // The single snapshot includes terminal runs, so they must be rejected here.
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'completed'), run(2, 'CI', 'queued')],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [2])
})

test('a run advancing between statuses cannot escape the sweep', async () => {
  // The regression that per-status queries caused: `requested` during the
  // `queued` query, `queued` by the time `requested` was asked for, in neither.
  const { github, core, calls } = harness({ runs: [run(1, 'CI', 'requested')] })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1])
  assert.equal(calls.listed.length, 1)
})

test('leaves a post-close rerun alone', async () => {
  // `/rerun` (pr-commands.yml) reuses the run id and its original created_at,
  // bumping run_started_at. Judging by created_at alone would cancel a rerun a
  // maintainer deliberately started after the close.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'in_progress', { created_at: BEFORE, run_started_at: AFTER }),
      run(2, 'CI', 'queued', { created_at: BEFORE, run_started_at: BEFORE }),
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [2])
})

test('will not use the fork fallback while another PR shares the head', async () => {
  // Two PRs from one fork branch against different bases. The open one's runs
  // predate this close and carry no association, so every other guard passes.
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { pull_requests: [], head_repository: { full_name: FORK } })],
    openOnSameHead: [{ number: 77 }],
  })
  await reapPrRuns({ ...BASE, headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.deepEqual(calls.headQueries, ['someone-else:agent/some-branch'])
})

test('this PR appearing in the head query means it was reopened', async () => {
  // A closed PR cannot appear in a `state: open` listing, so our own number
  // coming back is not a self-match to filter out — it is a reopen that landed
  // after the pulls.get above, and the runs are live again.
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { pull_requests: [], head_repository: { full_name: FORK } })],
    openOnSameHead: [{ number: 42 }],
  })
  await reapPrRuns({ ...BASE, headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [])
})

test('warns when the listing hits the API result window', async () => {
  // Reaping less than expected is the safe direction, but a truncated view must
  // not be reported as a clean sweep.
  const many = Array.from({ length: 1000 }, (_, i) => run(i + 1, 'CI', 'completed'))
  many.push(run(9999, 'CI', 'queued'))
  const { github, core, calls, warnings } = harness({ runs: many })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [9999])
  assert.equal(warnings.filter((w) => w.includes('1000-result')).length, 1)
})

test('assumes ambiguity when the open-PR check fails', async () => {
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls, warnings } = harness({
    runs: [run(1, 'CI', 'queued', { pull_requests: [], head_repository: { full_name: FORK } })],
    openOnSameHeadError: new Error('rate limited'),
  })
  await reapPrRuns({ ...BASE, headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.equal(warnings.length, 1)
})

test('an explicit association still reaps even on an ambiguous head', async () => {
  // Ambiguity only gates the fallback; a run that names this PR is not in doubt.
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { pull_requests: [{ number: 42 }] })],
    openOnSameHead: [{ number: 77 }],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1])
})

test('a deleted fork still gets its runs reaped via explicit association', async () => {
  // Deleting a fork nulls `head.repo`, so headRepo arrives empty — for exactly
  // the orphans most worth reaping. The repo guard cannot apply (a run's
  // head_repository is null, not ''), so we require the run to name this PR.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued', { head_repository: null, pull_requests: [{ number: 42 }] }),
      run(2, 'CI', 'queued', { head_repository: null, pull_requests: [] }),
    ],
  })
  await reapPrRuns({ ...BASE, headRepo: '', github, core })
  assert.deepEqual(calls.cancelled, [1])
  // No head query is possible without a repo to name.
  assert.deepEqual(calls.headQueries, [])
})

test('a deleted fork never reaps a run claiming a different PR', async () => {
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { head_repository: null, pull_requests: [{ number: 77 }] })],
  })
  await reapPrRuns({ ...BASE, headRepo: '', github, core })
  assert.deepEqual(calls.cancelled, [])
})

test('refuses to reap an empty head ref', async () => {
  const { github, core, calls } = harness({ runs: [run(1, 'CI', 'queued')] })
  const result = await reapPrRuns({ ...BASE, headRef: '', github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.match(result.skipped, /empty/)
})

test('skips entirely if the PR was reopened while this job queued', async () => {
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued')],
    prState: 'open',
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.deepEqual(calls.listed, [])
  assert.match(result.skipped, /open again/)
})

test('skips rather than guesses when the PR cannot be re-read', async () => {
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued')],
    prError: new Error('gateway timeout'),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.match(result.skipped, /could not re-read/)
})

test('leaves a concurrent open PR’s runs on the same branch alone', async () => {
  // One branch, two open PRs against different bases. The other PR's runs
  // predate this close, so the cutoff alone would not save them.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued', { pull_requests: [{ number: 42 }] }),
      run(2, 'CI', 'queued', { pull_requests: [{ number: 77 }] }),
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1])
})

test('an absent PR association does not stop a fork run being reaped', async () => {
  // `pull_requests` is empty for fork runs. Requiring a match would re-break
  // the fork case, so an empty association must fall through to the other guards.
  const FORK = 'someone-else/compose-ai-tools'
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { pull_requests: [], head_repository: { full_name: FORK } })],
  })
  await reapPrRuns({ ...BASE, headRepo: FORK, github, core })
  assert.deepEqual(calls.cancelled, [1])
})

test('a run created in the same second as the close is left alone', async () => {
  // GitHub timestamps are second-precision, so this is ambiguous. Prefer
  // leaving a leftover over cancelling live work.
  const { github, core, calls } = harness({
    runs: [run(1, 'CI', 'queued', { created_at: CLOSED_AT })],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [])
})

test('leaves runs created after the PR closed alone', async () => {
  // A new PR on the same branch, or a reopen that then re-closed: its checks
  // are live work, not leftovers.
  const { github, core, calls } = harness({
    runs: [
      run(1, 'CI', 'queued', { created_at: BEFORE }),
      run(2, 'CI', 'queued', { created_at: AFTER }),
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1])
})

test('treats a 409 as the finish/cancel race resolving itself', async () => {
  const { github, core } = harness({
    runs: [run(1, 'CI', 'queued'), run(2, 'Format Check', 'queued')],
    cancelError: (id) => (id === 1 ? Object.assign(new Error('completed'), { status: 409 }) : null),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(result.cancelled, ['Format Check (2)'])
  assert.deepEqual(result.failed, [])
})

test('reports a real cancel error without throwing', async () => {
  const { github, core, warnings } = harness({
    runs: [run(1, 'CI', 'queued')],
    cancelError: () => Object.assign(new Error('boom'), { status: 500 }),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(result.cancelled, [])
  assert.equal(result.failed.length, 1)
  assert.match(result.failed[0], /boom/)
  assert.equal(warnings.length, 1)
})

test('a listing failure warns instead of failing the workflow', async () => {
  const { github, core, warnings } = harness({
    runs: [run(1, 'CI', 'queued')],
    listError: Object.assign(new Error('rate limited'), { status: 403 }),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.equal(result.skipped, null)
  assert.equal(result.cancelled.length, 0)
  // The single listing failed, surfaced as a warning rather than thrown.
  assert.equal(result.failed.length, 1)
  assert.ok(result.failed.every((f) => /rate limited/.test(f)))
  assert.equal(warnings.length, 1)
})
