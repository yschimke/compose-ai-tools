// Self-test for the PR run reaper. Run: node --test .github/scripts/reap-pr-runs.test.mjs
//
// This runs on every PR (the `actions-tests` job in ci.yml) because the reaper
// cancels workflow runs unattended, and every guard it has exists because the
// failure it prevents is invisible until after it has happened: reaping the
// default branch wipes post-merge CI for commits in flight; matching on branch
// name alone lets a fork's `main` sweep up ours; and reaping without a
// close-time cutoff kills the checks of whatever reopened or re-used the branch.

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
    head_repository: { full_name: THIS_REPO },
    ...overrides,
  }
}

function harness({ runs = [], cancelError, listError, prState = 'closed', prError } = {}) {
  const calls = { listed: [], cancelled: [] }
  const github = {
    paginate: async (_fn, params) => {
      calls.listed.push({ branch: params.branch, status: params.status })
      if (listError) throw listError
      return runs.filter((r) => r.status === params.status)
    },
    rest: {
      pulls: {
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
  defaultBranch: 'main',
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
  assert.deepEqual(
    calls.listed.map((c) => c.status),
    ['queued', 'in_progress'],
  )
  assert.ok(calls.listed.every((c) => c.branch === 'agent/some-branch'))
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

test('refuses to reap this repo’s default branch', async () => {
  const { github, core, calls } = harness({ runs: [run(1, 'CI', 'queued')] })
  const result = await reapPrRuns({ ...BASE, headRef: 'main', github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.deepEqual(calls.listed, [])
  assert.match(result.skipped, /refusing to reap/)
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
  // One per status queried, both surfaced as warnings rather than thrown.
  assert.equal(result.failed.length, 2)
  assert.ok(result.failed.every((f) => /rate limited/.test(f)))
  assert.equal(warnings.length, 2)
})
