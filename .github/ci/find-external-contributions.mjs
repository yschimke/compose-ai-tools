#!/usr/bin/env node
// Finds open pull requests and issues authored by external contributors across all non-archived
// repositories owned by an account, excluding the owner and known automation/bot accounts.
//
// Usage:
//   node .github/ci/find-external-contributions.mjs \
//     --owner yschimke [--json external.json] [--markdown external.md]

import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const DEFAULT_OWNER = 'yschimke';

export const BOT_LOGINS = new Set([
  'renovate',
  'dependabot',
  'release-please',
  'github-actions',
  'claude',
  'copilot-swe-agent',
  'codex',
]);

const REPO_API_PREFIX = 'https://api.github.com/repos/';
const MS_PER_DAY = 86_400_000;
const MAX_SEARCH_PAGES = 10;

/**
 * Returns true if the author should be excluded from external-contribution reports
 * (missing user, repository owner, GitHub Bot account type, `*[bot]` login, or known bot login).
 */
export function isExcludedAuthor(user, owner) {
  if (!user || typeof user.login !== 'string') return true;
  const login = user.login.trim().toLowerCase();
  if (!login) return true;
  if (owner && login === owner.trim().toLowerCase()) return true;
  if (typeof user.type === 'string' && user.type.toLowerCase() === 'bot') return true;
  if (login.endsWith('[bot]')) return true;
  if (BOT_LOGINS.has(login)) return true;
  return false;
}

/**
 * Extracts `owner/repo` from a GitHub Search API item.
 */
export function repoFromItem(item) {
  if (typeof item?.repository_url === 'string' && item.repository_url.startsWith(REPO_API_PREFIX)) {
    return item.repository_url.slice(REPO_API_PREFIX.length);
  }
  if (typeof item?.html_url === 'string') {
    const match = item.html_url.match(/^https?:\/\/[^/]+\/([^/]+\/[^/]+)\/(?:issues|pull)\/\d+/);
    if (match) return match[1];
  }
  return '';
}

/**
 * Normalizes a raw GitHub Search API issue/PR item into a report entry.
 */
export function normalizeItem(item, now = new Date()) {
  const repo = repoFromItem(item);
  const kind = item.pull_request ? 'pr' : 'issue';
  const createdMs = Date.parse(item.created_at);
  const ageDays = Number.isFinite(createdMs)
    ? Math.max(0, Math.floor((now.getTime() - createdMs) / MS_PER_DAY))
    : 0;
  return {
    repo,
    kind,
    number: item.number,
    title: item.title ?? '',
    url: item.html_url ?? '',
    author: item.user?.login ?? '',
    draft: kind === 'pr' ? Boolean(item.draft) : false,
    labels: Array.isArray(item.labels)
      ? item.labels.map(label => (typeof label === 'string' ? label : label?.name)).filter(Boolean)
      : [],
    comments: Number.isFinite(item.comments) ? item.comments : 0,
    createdAt: item.created_at ?? '',
    updatedAt: item.updated_at ?? '',
    ageDays,
  };
}

/**
 * Filters raw GitHub Search API items down to non-archived external contributions,
 * deduplicates by `repo#number`, and sorts deterministically by repo and number.
 */
export function filterExternalItems(items, { owner, archivedRepos = new Set(), now = new Date() }) {
  const archivedLower = new Set([...archivedRepos].map(r => r.toLowerCase()));
  const seen = new Set();
  const result = [];

  for (const item of items) {
    if (!item || (item.state && item.state !== 'open')) continue;
    if (item.archived === true || item.repository?.archived === true) continue;
    if (isExcludedAuthor(item.user, owner)) continue;

    const repo = repoFromItem(item);
    if (!repo || archivedLower.has(repo.toLowerCase())) continue;

    const key = `${repo.toLowerCase()}#${item.number}`;
    if (seen.has(key)) continue;
    seen.add(key);

    result.push(normalizeItem(item, now));
  }

  result.sort((a, b) => a.repo.localeCompare(b.repo) || a.number - b.number);
  return result;
}

function escapeTableCell(text) {
  return String(text ?? '')
    .replace(/\r?\n/g, ' ')
    .replace(/\|/g, '\\|')
    .trim();
}

function formatAge(ageDays) {
  return `${ageDays}d`;
}

/**
 * Renders the markdown summary used for both `$GITHUB_STEP_SUMMARY` and the tracking issue body.
 */
export function renderMarkdown(
  items,
  { owner, generatedAt, scannedPrs = 0, scannedIssues = 0, truncated = false } = {},
) {
  const lines = [
    '<!-- external-contributions-report -->',
    `Open pull requests and issues from external contributors across \`${owner ?? DEFAULT_OWNER}\` repositories.`,
    `Generated ${generatedAt} from ${scannedPrs} open PRs and ${scannedIssues} open issues.`,
    '',
  ];

  if (truncated) {
    lines.push(
      '> Warning: GitHub Search API results were truncated or incomplete; rerun or narrow the query to verify.',
      '',
    );
  }

  if (!items.length) {
    lines.push('No open external PRs or issues.');
    return lines.join('\n') + '\n';
  }

  const prs = items.filter(item => item.kind === 'pr');
  const issues = items.filter(item => item.kind === 'issue');

  const renderSection = (heading, kindLabel, entries) => {
    lines.push(
      `### ${heading} (${entries.length})`,
      '',
      `| ${kindLabel} | Title | Author | Age | Comments |`,
      '| --- | --- | --- | --- | --- |',
    );
    for (const entry of entries) {
      const title = entry.draft
        ? `[Draft] ${escapeTableCell(entry.title)}`
        : escapeTableCell(entry.title);
      const author = entry.author ? `\`@${entry.author}\`` : '';
      lines.push(
        `| [${entry.repo}#${entry.number}](${entry.url}) | ${title} | ${author} | ${formatAge(entry.ageDays)} | ${entry.comments} |`,
      );
    }
    lines.push('');
  };

  if (prs.length) renderSection('Pull Requests', 'PR', prs);
  if (issues.length) renderSection('Issues', 'Issue', issues);

  lines.push(
    '<details><summary>JSON</summary>',
    '',
    '```json',
    JSON.stringify(items, null, 2),
    '```',
    '',
    '</details>',
  );
  return lines.join('\n') + '\n';
}

/**
 * Parses CLI flags (`--owner`, `--json`, `--markdown`).
 */
export function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith('--')) {
      args[key] = next;
      i++;
    } else {
      args[key] = 'true';
    }
  }
  return args;
}

async function searchAll(api, query) {
  const items = [];
  let truncated = false;
  for (let page = 1; page <= MAX_SEARCH_PAGES; page++) {
    const encoded = encodeURIComponent(query);
    const res = await api(`/search/issues?q=${encoded}&per_page=100&page=${page}`);
    if (res.incomplete_results) truncated = true;
    const batch = Array.isArray(res.items) ? res.items : [];
    items.push(...batch);
    if (batch.length < 100 || items.length >= (res.total_count ?? 0)) {
      if ((res.total_count ?? 0) > MAX_SEARCH_PAGES * 100) truncated = true;
      break;
    }
    if (page === MAX_SEARCH_PAGES && (res.total_count ?? 0) > items.length) {
      truncated = true;
    }
  }
  return { items, truncated };
}

/**
 * Queries GitHub Search API for open PRs and issues under `owner`, verifies repo archive status,
 * and returns filtered external contributions plus scan metadata.
 */
export async function collectExternalContributions({ owner = DEFAULT_OWNER, api, now = new Date() }) {
  const prQuery = `user:${owner} state:open type:pr archived:false -author:${owner}`;
  const issueQuery = `user:${owner} state:open type:issue archived:false -author:${owner}`;

  const [prSearch, issueSearch] = await Promise.all([
    searchAll(api, prQuery),
    searchAll(api, issueQuery),
  ]);

  const rawItems = [...prSearch.items, ...issueSearch.items];
  const candidates = filterExternalItems(rawItems, { owner, now });

  const archivedRepos = new Set();
  const checkedRepos = new Set();
  for (const entry of candidates) {
    if (checkedRepos.has(entry.repo)) continue;
    checkedRepos.add(entry.repo);
    const repoInfo = await api(`/repos/${entry.repo}`, { allow404: true });
    if (repoInfo?.archived) archivedRepos.add(entry.repo);
  }

  const items = archivedRepos.size
    ? candidates.filter(entry => !archivedRepos.has(entry.repo))
    : candidates;

  return {
    items,
    scannedPrs: prSearch.items.length,
    scannedIssues: issueSearch.items.length,
    truncated: prSearch.truncated || issueSearch.truncated,
  };
}

function createGitHubApi(token) {
  return async (path, { allow404 = false } = {}) => {
    const res = await fetch(`https://api.github.com${path}`, {
      headers: {
        accept: 'application/vnd.github+json',
        'x-github-api-version': '2022-11-28',
        ...(token && { authorization: `Bearer ${token}` }),
      },
    });
    if (allow404 && res.status === 404) return null;
    if (!res.ok) {
      throw Object.assign(new Error(`${res.status} ${path}: ${await res.text()}`), {
        status: res.status,
      });
    }
    return res.json();
  };
}

export async function main(argv) {
  const args = parseArgs(argv);
  const owner = args.owner ?? DEFAULT_OWNER;
  const token = process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN;
  const api = createGitHubApi(token);

  const now = new Date();
  const { items, scannedPrs, scannedIssues, truncated } = await collectExternalContributions({
    owner,
    api,
    now,
  });

  const markdown = renderMarkdown(items, {
    owner,
    generatedAt: now.toISOString(),
    scannedPrs,
    scannedIssues,
    truncated,
  });

  if (args.json) writeFileSync(args.json, JSON.stringify(items, null, 2) + '\n');
  if (args.markdown) writeFileSync(args.markdown, markdown);
  if (!args.json && !args.markdown) process.stdout.write(markdown);

  const prCount = items.filter(i => i.kind === 'pr').length;
  const issueCount = items.filter(i => i.kind === 'issue').length;
  console.error(
    `${items.length} external contribution(s) (${prCount} PRs, ${issueCount} issues) across ${owner}`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(err => {
    console.error(err);
    process.exit(1);
  });
}
