/**
 * The emitter's branch-read posture — the half the publish step depends on.
 *
 * `emit-parity-findings.mjs` exits through "nothing to publish" two different ways, and the
 * workflow treats them oppositely: a branch that WAS read and reports nothing is authoritative and
 * should retire the served verdict, while a branch that could not be READ must not be allowed to
 * delete one. The publish snapshots `out/` wholesale, so the difference is a good
 * `parity/findings.json` surviving or silently disappearing.
 *
 * The signal is `PARITY_FINDINGS_UNREADABLE=1` appended to `$GITHUB_ENV`, which the publish step
 * turns into a `CARRY_FORWARD_PATHS` entry. These tests pin that contract from the emitter's side.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const EMITTER = fileURLToPath(new URL("./emit-parity-findings.mjs", import.meta.url));
const SYSTEM = "demo-system";

const git = (cwd, ...args) =>
  execFileSync("git", args, { cwd, stdio: "ignore", env: { ...process.env, HOME: cwd } });

/** A minimal repo the emitter will accept: a catalog naming a system, and a design map. */
function fixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "parity-emit-"));
  const out = path.join(dir, "out");
  fs.mkdirSync(out);
  fs.writeFileSync(path.join(out, "catalog.json"), JSON.stringify({ system: SYSTEM }));
  fs.writeFileSync(path.join(dir, "design-map.json"), JSON.stringify({ components: [] }));

  git(dir, "init", "-q", "-b", "main");
  git(dir, "config", "user.email", "t@example.com");
  git(dir, "config", "user.name", "T");
  git(dir, "add", "-A");
  git(dir, "commit", "-q", "-m", "fixture");
  return { dir, out };
}

/** Run the emitter with a scratch `$GITHUB_ENV`, and return what it appended. */
function run({ dir, out }) {
  const envFile = path.join(dir, "github-env");
  fs.writeFileSync(envFile, "");
  execFileSync(process.execPath, [EMITTER, "--out", out, "--repo", dir], {
    cwd: dir,
    stdio: "ignore",
    env: { ...process.env, HOME: dir, GITHUB_ENV: envFile, GITHUB_TOKEN_INLINE: "", GITHUB_REPOSITORY: "" },
  });
  return fs.readFileSync(envFile, "utf8");
}

test("an unreadable parity branch asks for the served verdict to be kept", () => {
  // No `design-parity/<system>` branch and no reachable remote — indistinguishable, from here,
  // from a private caller whose credential-less `origin` cannot fetch one that does exist.
  const repo = fixture();
  assert.match(run(repo), /^PARITY_FINDINGS_UNREADABLE=1$/m);
});

test("a branch that was read and reports nothing does not ask for it", () => {
  // The authoritative case: the branch is there and publishes no findings, so the catalog has
  // reached parity and the stale verdict SHOULD be retired. Carrying it forward here is the bug
  // that froze the panel at its first published value.
  const repo = fixture();
  git(repo.dir, "checkout", "-q", "--orphan", `design-parity/${SYSTEM}`);
  git(repo.dir, "rm", "-rqf", ".");
  fs.writeFileSync(path.join(repo.dir, "run.json"), JSON.stringify({ entries: [] }));
  git(repo.dir, "add", "run.json");
  git(repo.dir, "commit", "-q", "-m", "parity run");
  git(repo.dir, "checkout", "-q", "main");

  // The emitter reads `origin/<branch>` or the local branch name; this fixture has the latter.
  assert.doesNotMatch(run(repo), /PARITY_FINDINGS_UNREADABLE/);
});
