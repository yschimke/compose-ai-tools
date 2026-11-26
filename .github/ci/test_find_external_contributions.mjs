// Self-test for the external contributions watcher.
// Run: node --test .github/ci/test_find_external_contributions.mjs

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  collectExternalContributions,
  filterExternalItems,
  isExcludedAuthor,
  parseArgs,
  renderMarkdown,
  repoFromItem,
} from './find-external-contributions.mjs';

const NOW = new Date('2026-11-26T12:00:00Z');

function makeItem(overrides = {}) {
  return {
    number: 10,
    title: 'Fix crash on launch',
    state: 'open',
    html_url: 'https://github.com/yschimke/compose-ai-tools/issues/10',
    repository_url: 'https://api.github.com/repos/yschimke/compose-ai-tools',
    user: { login: 'external-dev', type: 'User' },
    labels: [{ name: 'bug' }],
    comments: 2,
    created_at: '2026-11-23T12:00:00Z',
    updated_at: '2026-11-25T12:00:00Z',
    ...overrides,
  };
}

test('isExcludedAuthor excludes owner, [bot] logins, Bot type, and named bot accounts', () => {
  assert.equal(isExcludedAuthor({ login: 'yschimke', type: 'User' }, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: 'YSCHIMKE', type: 'User' }, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: 'renovate[bot]', type: 'Bot' }, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: 'custom-app[bot]', type: 'User' }, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: 'some-bot', type: 'Bot' }, 'yschimke'), true);

  for (const bot of [
    'renovate',
    'dependabot',
    'release-please',
    'github-actions',
    'claude',
    'copilot-swe-agent',
    'codex',
  ]) {
    assert.equal(isExcludedAuthor({ login: bot, type: 'User' }, 'yschimke'), true, `expected ${bot} excluded`);
    assert.equal(isExcludedAuthor({ login: bot.toUpperCase(), type: 'User' }, 'yschimke'), true);
  }

  assert.equal(isExcludedAuthor(null, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: '' }, 'yschimke'), true);
  assert.equal(isExcludedAuthor({ login: 'ardmn', type: 'User' }, 'yschimke'), false);
  assert.equal(isExcludedAuthor({ login: 'renovate-fan', type: 'User' }, 'yschimke'), false);
});

test('repoFromItem parses repository_url and falls back to html_url', () => {
  assert.equal(
    repoFromItem({ repository_url: 'https://api.github.com/repos/yschimke/livery' }),
    'yschimke/livery',
  );
  assert.equal(
    repoFromItem({ html_url: 'https://github.com/yschimke/rc-players/pull/983' }),
    'yschimke/rc-players',
  );
  assert.equal(repoFromItem({}), '');
});

test('filterExternalItems drops owner, bots, archived repos, closed items, and duplicates', () => {
  const items = [
    makeItem({ number: 5, user: { login: 'yschimke', type: 'User' } }),
    makeItem({ number: 6, user: { login: 'renovate[bot]', type: 'Bot' } }),
    makeItem({ number: 7, user: { login: 'copilot-swe-agent', type: 'User' } }),
    makeItem({ number: 8, state: 'closed', user: { login: 'alice', type: 'User' } }),
    makeItem({
      number: 9,
      repository_url: 'https://api.github.com/repos/yschimke/old-archived-repo',
      html_url: 'https://github.com/yschimke/old-archived-repo/issues/9',
      user: { login: 'alice', type: 'User' },
    }),
    makeItem({
      number: 11,
      repository: { archived: true },
      user: { login: 'alice', type: 'User' },
    }),
    makeItem({
      number: 20,
      repository_url: 'https://api.github.com/repos/yschimke/rc-players',
      html_url: 'https://github.com/yschimke/rc-players/pull/20',
      pull_request: { url: 'https://api.github.com/repos/yschimke/rc-players/pulls/20' },
      draft: true,
      user: { login: 'bob', type: 'User' },
    }),
    makeItem({ number: 10, user: { login: 'alice', type: 'User' } }),
    // Duplicate of #10
    makeItem({ number: 10, user: { login: 'alice', type: 'User' } }),
  ];

  const filtered = filterExternalItems(items, {
    owner: 'yschimke',
    archivedRepos: new Set(['yschimke/old-archived-repo']),
    now: NOW,
  });

  assert.equal(filtered.length, 2);
  assert.deepEqual(
    filtered.map(i => `${i.repo}#${i.number}:${i.kind}:${i.draft}`),
    ['yschimke/compose-ai-tools#10:issue:false', 'yschimke/rc-players#20:pr:true'],
  );
  assert.equal(filtered[0].ageDays, 3);
  assert.deepEqual(filtered[0].labels, ['bug']);
});

test('renderMarkdown handles empty state cleanly', () => {
  const md = renderMarkdown([], {
    owner: 'yschimke',
    generatedAt: '2026-11-26T12:00:00.000Z',
    scannedPrs: 12,
    scannedIssues: 4,
  });
  assert.match(md, /<!-- external-contributions-report -->/);
  assert.match(md, /No open external PRs or issues\./);
  assert.doesNotMatch(md, /### Pull Requests/);
  assert.doesNotMatch(md, /### Issues/);
});

test('renderMarkdown formats PR and Issue tables, escapes pipes, and embeds JSON', () => {
  const items = [
    {
      repo: 'yschimke/rc-players',
      kind: 'pr',
      number: 983,
      title: 'Fix A | B\nrendering',
      url: 'https://github.com/yschimke/rc-players/pull/983',
      author: 'ardmn',
      draft: true,
      labels: [],
      comments: 4,
      createdAt: '2026-11-20T00:00:00Z',
      updatedAt: '2026-11-25T00:00:00Z',
      ageDays: 6,
    },
    {
      repo: 'yschimke/compose-ai-tools',
      kind: 'issue',
      number: 505,
      title: 'JVM target mismatch',
      url: 'https://github.com/yschimke/compose-ai-tools/issues/505',
      author: 'contributor',
      draft: false,
      labels: ['bug'],
      comments: 1,
      createdAt: '2026-11-24T00:00:00Z',
      updatedAt: '2026-11-25T00:00:00Z',
      ageDays: 2,
    },
  ];

  const md = renderMarkdown(items, {
    owner: 'yschimke',
    generatedAt: '2026-11-26T12:00:00.000Z',
    scannedPrs: 5,
    scannedIssues: 3,
  });

  assert.match(md, /### Pull Requests \(1\)/);
  assert.match(md, /\[Draft\] Fix A \\\| B rendering/);
  assert.match(md, /`@ardmn`/);
  assert.match(md, /### Issues \(1\)/);
  assert.match(md, /\[yschimke\/compose-ai-tools#505\]/);
  assert.match(md, /<details><summary>JSON<\/summary>/);
});

test('collectExternalContributions queries public PRs and issues, paginates, and drops archived/private repos', async () => {
  const calls = [];
  const api = async path => {
    calls.push(path);
    if (path.includes('type%3Apr') && path.includes('page=1')) {
      assert.match(path, /is%3Apublic/);
      return {
        total_count: 3,
        incomplete_results: false,
        items: [
          makeItem({
            number: 1,
            repository_url: 'https://api.github.com/repos/yschimke/active-repo',
            html_url: 'https://github.com/yschimke/active-repo/pull/1',
            pull_request: {},
            user: { login: 'dev1', type: 'User' },
          }),
          makeItem({
            number: 2,
            repository_url: 'https://api.github.com/repos/yschimke/stale-archived',
            html_url: 'https://github.com/yschimke/stale-archived/pull/2',
            pull_request: {},
            user: { login: 'dev2', type: 'User' },
          }),
          makeItem({
            number: 5,
            repository_url: 'https://api.github.com/repos/yschimke/secret-repo',
            html_url: 'https://github.com/yschimke/secret-repo/pull/5',
            pull_request: {},
            user: { login: 'dev4', type: 'User' },
          }),
        ],
      };
    }
    if (path.includes('type%3Aissue') && path.includes('page=1')) {
      assert.match(path, /is%3Apublic/);
      return {
        total_count: 3,
        incomplete_results: false,
        items: [
          makeItem({
            number: 3,
            repository_url: 'https://api.github.com/repos/yschimke/active-repo',
            html_url: 'https://github.com/yschimke/active-repo/issues/3',
            user: { login: 'claude', type: 'User' },
          }),
          makeItem({
            number: 4,
            repository_url: 'https://api.github.com/repos/yschimke/active-repo',
            html_url: 'https://github.com/yschimke/active-repo/issues/4',
            user: { login: 'dev3', type: 'User' },
          }),
          makeItem({
            number: 6,
            repository_url: 'https://api.github.com/repos/yschimke/missing-repo',
            html_url: 'https://github.com/yschimke/missing-repo/issues/6',
            user: { login: 'dev5', type: 'User' },
          }),
        ],
      };
    }
    if (path === '/repos/yschimke/active-repo') {
      return { full_name: 'yschimke/active-repo', archived: false, private: false };
    }
    if (path === '/repos/yschimke/stale-archived') {
      return { full_name: 'yschimke/stale-archived', archived: true, private: false };
    }
    if (path === '/repos/yschimke/secret-repo') {
      return { full_name: 'yschimke/secret-repo', archived: false, private: true };
    }
    if (path === '/repos/yschimke/missing-repo') {
      return null;
    }
    throw new Error(`Unexpected API call: ${path}`);
  };

  const result = await collectExternalContributions({ owner: 'yschimke', api, now: NOW });

  assert.equal(result.scannedPrs, 3);
  assert.equal(result.scannedIssues, 3);
  assert.equal(result.truncated, false);
  assert.deepEqual(
    result.items.map(i => `${i.repo}#${i.number}:${i.kind}`),
    ['yschimke/active-repo#1:pr', 'yschimke/active-repo#4:issue'],
  );
  // Active repo checked only once despite having both a PR and an issue.
  assert.equal(calls.filter(c => c === '/repos/yschimke/active-repo').length, 1);
});

test('parseArgs parses key-value flags', () => {
  assert.deepEqual(
    parseArgs(['--owner', 'yschimke', '--json', 'external.json', '--markdown', 'external.md']),
    { owner: 'yschimke', json: 'external.json', markdown: 'external.md' },
  );
});
