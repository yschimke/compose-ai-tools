// Enumerates pushed render-history reporting branches (#1872) for the History
// panel's source picker. The daemon's reporting-branch writer publishes history
// under `preview/<source-branch>` refs (see docs/daemon/REPORTING-BRANCH.md); the
// picker lets the user point the panel at one of them via the daemon's on-demand
// `ref` history param.

import { execFile, execFileSync } from "child_process";
import { promisify } from "util";

const execFileAsync = promisify(execFile);

/** A reporting branch the user can view in the History panel. */
export interface ReportingBranch {
    /** Full git ref, e.g. `refs/heads/preview/main` — passed to the daemon. */
    ref: string;
    /** Short, friendly label for the picker, e.g. `preview/main` or
     *  `origin/preview/main`. */
    label: string;
}

/**
 * Lists `preview/*` reporting branches under local heads and every remote,
 * de-duplicated by ref and sorted by label. Returns full ref names so the
 * daemon resolves them unambiguously.
 *
 * Best-effort: returns `[]` when git is missing, `repoRoot` isn't a repo, or
 * there are no preview branches — callers degrade to "Local only".
 */
export async function listReportingBranches(
    repoRoot: string,
): Promise<ReportingBranch[]> {
    let stdout: string;
    try {
        ({ stdout } = await execFileAsync(
            "git",
            [
                "for-each-ref",
                "--format=%(refname)",
                "refs/heads",
                "refs/remotes",
            ],
            { cwd: repoRoot, timeout: 5000 },
        ));
    } catch {
        return [];
    }
    const seen = new Set<string>();
    const branches: ReportingBranch[] = [];
    for (const raw of stdout.split("\n")) {
        const ref = raw.trim();
        if (!ref || seen.has(ref)) {
            continue;
        }
        const label = reportingBranchLabel(ref);
        if (!label) {
            continue;
        }
        seen.add(ref);
        branches.push({ ref, label });
    }
    branches.sort((a, b) => a.label.localeCompare(b.label));
    return branches;
}

/**
 * Maps a full ref to a picker label iff it's a `preview/*` reporting branch:
 * - `refs/heads/preview/main` → `preview/main`
 * - `refs/remotes/origin/preview/main` → `origin/preview/main`
 *
 * Returns null for any ref that isn't a preview branch (so non-reporting refs
 * are filtered out).
 */
export function reportingBranchLabel(ref: string): string | null {
    const local = ref.match(/^refs\/heads\/(preview\/.+)$/);
    if (local) {
        return local[1];
    }
    const remote = ref.match(/^refs\/remotes\/([^/]+\/preview\/.+)$/);
    if (remote) {
        return remote[1];
    }
    return null;
}

// Characters kept verbatim in a sanitised preview-id directory name: Unicode letters/digits plus
// `.`, `_`, `-`. Mirrors the daemon's Kotlin `PreviewIdSanitiser` (Char.isLetterOrDigit, Unicode).
const SANITISE_KEEP = /[\p{L}\p{N}._-]/u;

/**
 * Sanitises a `previewId` into the reporting-branch directory name, mirroring the daemon's
 * `PreviewIdSanitiser`: kept characters pass through, everything else collapses to `_`, and an
 * empty id yields `_`. The reporting branch stores each preview under `<sanitised>/`.
 */
export function sanitisePreviewId(previewId: string): string {
    if (previewId.length === 0) {
        return "_";
    }
    let out = "";
    for (const ch of previewId) {
        out += SANITISE_KEEP.test(ch) ? ch : "_";
    }
    return out;
}

/** A reporting-branch data-product field embedded in `entry.json`. */
export type GitRefEntryField = "semantics" | "theme" | "a11yHierarchy";

/**
 * Reads a single data-product field embedded in a reporting-branch entry, addressed by a
 * `<shortCommit>:<previewId>` id (#1868), via `git show <commit>:<dir>/entry.json`. The branch's
 * `entry.json` carries the full `semantics` / `theme` / `a11yHierarchy` payloads, so the History
 * panel can diff a reporting-branch entry without the data living on the local FS.
 *
 * Synchronous — this runs on a user-driven "diff vs previous" click. Best-effort: returns `null`
 * for a malformed id, a missing entry/field, or any git/parse failure.
 */
export function readGitRefEntryField(
    repoRoot: string,
    entryId: string,
    field: GitRefEntryField,
): unknown {
    const sep = entryId.indexOf(":");
    if (sep <= 0) {
        return null;
    }
    const shortCommit = entryId.slice(0, sep);
    const dir = sanitisePreviewId(entryId.slice(sep + 1));
    let text: string;
    try {
        text = execFileSync(
            "git",
            ["show", `${shortCommit}:${dir}/entry.json`],
            {
                cwd: repoRoot,
                encoding: "utf8",
                timeout: 5000,
                stdio: ["ignore", "pipe", "ignore"],
                maxBuffer: 32 * 1024 * 1024,
            },
        );
    } catch {
        return null;
    }
    try {
        const entry = JSON.parse(text) as Record<string, unknown>;
        return entry[field] ?? null;
    } catch {
        return null;
    }
}
