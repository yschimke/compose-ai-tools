#!/usr/bin/env node
/** Query configured repositories with `gh`, parse locator fences, and write parity/issues.json. */
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { buildIssueIndex } from "./parity-issues.mjs";

const args = process.argv.slice(2);
const value = (name) => { const i = args.indexOf(name); return i < 0 ? null : args[i + 1]; };
const out = value("--out");
const repos = args.flatMap((arg, i) => arg === "--repo" && args[i + 1] ? [args[i + 1]] : []);
if (!out || repos.length === 0) {
  console.error("usage: emit-parity-issues.mjs --out <bundle-dir> --repo <owner/name> [--repo ...]");
  process.exit(2);
}

const issues = [];
for (const repo of repos) {
  const raw = execFileSync("gh", ["api", "--paginate", "--slurp", `repos/${repo}/issues?state=all&per_page=100`], { encoding: "utf8", maxBuffer: 25 * 1024 * 1024 });
  const pages = JSON.parse(raw);
  issues.push(...pages.flat().filter((issue) => !issue.pull_request));
}
// A repository is mostly ordinary issues, and none of them carry a locator block. Only a *broken*
// locator fails the run; an absent one is skipped, and is worth a warning only when the issue is
// labelled as parity work — that combination is a report filed without its identity, which is the
// one case a human needs to go fix.
let failures = 0;
let skipped = 0;
const index = buildIssueIndex(issues, {
  onError: (issue, error) => { failures++; console.error(`::warning::parity-issues: #${issue?.number ?? "?"}: ${error}`); },
  onSkip: (issue, { labelled }) => {
    skipped++;
    if (labelled) console.error(`::warning::parity-issues: #${issue?.number ?? "?"}: labelled as parity work but carries no locator block`);
  },
});
const target = join(out, "parity", "issues.json");
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, JSON.stringify(index, null, 2) + "\n");
console.log(`parity-issues: wrote ${index.issues.length} row(s) to ${target}; ${skipped} issue(s) carry no locator; ${failures} issue(s) rejected`);
if (failures) process.exitCode = 1;
