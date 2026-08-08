// Self-test for the PR run reaper. Run: node --test .github/scripts/reap-pr-runs.test.mjs
//
// This runs on every PR (the `actions-tests` job in ci.yml) because the reaper
// cancels workflow runs unattended. The two guards below are the whole safety
// story: without the fork check it cancels our runs when a fork PR's branch
// shares a name with ours, and without the default-branch check a mis-trigger
// wipes post-merge CI for every commit in flight. Neither failure is visible
// until it has already happened.

import assert from 'node:assert/strict'
import test from 'node:test'

import { reapPrRuns } from './reap-pr-runs.mjs'

const REPO = { owner: 'yschimke', repo: 'compose-ai-tools' }
const THIS_REPO = 'yschimke/compose-ai-tools'

function harness({ runs = [], cancelError } = {}) {
  const calls = { listed: [], cancelled: [] }
  const github = {
    paginate: async (_fn, params) => {
      calls.listed.push({ branch: params.branch, status: params.status })
      return runs.filter((r) => r.status === params.status)
    },
    rest: {
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
  const core = { info: () => {}, warning: () => {} }
  return { github, core, calls }
}

const BASE = {
  repo: REPO,
  headRef: 'agent/some-branch',
  headRepo: THIS_REPO,
  thisRepo: THIS_REPO,
  defaultBranch: 'main',
  currentRunId: 999,
}

test('cancels queued and in-progress runs on the closed PR branch', async () => {
  const { github, core, calls } = harness({
    runs: [
      { id: 1, name: 'CI', status: 'queued' },
      { id: 2, name: 'VS Code Extension E2E', status: 'queued' },
      { id: 3, name: 'Integration', status: 'in_progress' },
    ],
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [1, 2, 3])
  assert.equal(result.cancelled.length, 3)
  assert.equal(result.skipped, null)
  assert.deepEqual(
    calls.listed.map((c) => c.status),
    ['queued', 'in_progress'],
  )
  assert.ok(calls.listed.every((c) => c.branch === 'agent/some-branch'))
})

test('never cancels itself', async () => {
  const { github, core, calls } = harness({
    runs: [
      { id: 999, name: 'PR Run Reaper', status: 'in_progress' },
      { id: 7, name: 'CI', status: 'in_progress' },
    ],
  })
  await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(calls.cancelled, [7])
})

test('skips fork PRs so a shared branch name cannot reap our runs', async () => {
  const { github, core, calls } = harness({
    runs: [{ id: 1, name: 'CI', status: 'queued' }],
  })
  const result = await reapPrRuns({
    ...BASE,
    headRef: 'main',
    headRepo: 'someone-else/compose-ai-tools',
    github,
    core,
  })
  assert.deepEqual(calls.cancelled, [])
  assert.deepEqual(calls.listed, [])
  assert.match(result.skipped, /someone-else/)
})

test('refuses to reap the default branch', async () => {
  const { github, core, calls } = harness({
    runs: [{ id: 1, name: 'CI', status: 'queued' }],
  })
  const result = await reapPrRuns({ ...BASE, headRef: 'main', github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.deepEqual(calls.listed, [])
  assert.match(result.skipped, /refusing to reap/)
})

test('refuses to reap an empty head ref', async () => {
  const { github, core, calls } = harness({
    runs: [{ id: 1, name: 'CI', status: 'queued' }],
  })
  const result = await reapPrRuns({ ...BASE, headRef: '', github, core })
  assert.deepEqual(calls.cancelled, [])
  assert.match(result.skipped, /refusing to reap/)
})

test('treats a 409 as the finish/cancel race resolving itself', async () => {
  const { github, core } = harness({
    runs: [
      { id: 1, name: 'CI', status: 'queued' },
      { id: 2, name: 'Format Check', status: 'queued' },
    ],
    cancelError: (id) => (id === 1 ? Object.assign(new Error('completed'), { status: 409 }) : null),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(result.cancelled, ['Format Check (2)'])
  assert.deepEqual(result.failed, [])
})

test('reports a real API error without throwing', async () => {
  const { github, core } = harness({
    runs: [{ id: 1, name: 'CI', status: 'queued' }],
    cancelError: () => Object.assign(new Error('boom'), { status: 500 }),
  })
  const result = await reapPrRuns({ ...BASE, github, core })
  assert.deepEqual(result.cancelled, [])
  assert.equal(result.failed.length, 1)
  assert.match(result.failed[0], /boom/)
})
