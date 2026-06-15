// Enumerates pushed render-history reporting branches (#1872) for the History
// panel's source picker. The daemon's reporting-branch writer publishes history
// under `preview/<source-branch>` refs (see docs/daemon/REPORTING-BRANCH.md); the
// picker lets the user point the panel at one of them via the daemon's on-demand
// `ref` history param.

import { execFile } from "child_process";
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
