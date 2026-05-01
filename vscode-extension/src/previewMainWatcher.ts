import * as fs from 'fs';
import * as path from 'path';

/**
 * Watch the git refs that back the "Diff vs main" anchor and fire a single
 * coalesced callback whenever any of them changes — typically after a
 * `git fetch` lands new objects, but also on local commit/branch ops.
 *
 * Targets:
 *   - `.git/refs/remotes/origin/preview_main` — post-fetch view of the
 *     remote baseline branch (the most common trigger).
 *   - `.git/refs/heads/preview_main` — local branch, for repos that
 *     publish baselines locally.
 *   - `.git/packed-refs` — git packs loose refs into this file via
 *     `git pack-refs` / `git gc`; when packed, the per-file refs above
 *     disappear and updates land here instead.
 *
 * `fs.watch` on the parent dir + filename filter catches create / change /
 * delete uniformly across platforms (`recursive: true` is Mac/Win-only).
 * A 250ms debounce coalesces fetch bursts that touch packed-refs and
 * the per-ref file in quick succession.
 */
export function watchPreviewMainRef(
    workspaceRoot: string,
    onChange: () => void,
): { dispose(): void } {
    const gitDir = path.join(workspaceRoot, '.git');
    if (!fs.existsSync(gitDir) || !fs.statSync(gitDir).isDirectory()) {
        // Worktree (`.git` is a file pointing elsewhere) or non-git
        // workspace. Fall through to a no-op; callers don't gain
        // automatic refresh, but explicit Diff vs main still works.
        return { dispose: () => { /* nothing to clean up */ } };
    }

    let debounce: NodeJS.Timeout | null = null;
    const fire = () => {
        if (debounce) { return; }
        debounce = setTimeout(() => {
            debounce = null;
            onChange();
        }, 250);
    };

    interface DirWatch {
        dir: string;
        filenames: Set<string>;
    }
    const dirs: DirWatch[] = [
        { dir: path.join(gitDir, 'refs', 'remotes', 'origin'), filenames: new Set(['preview_main']) },
        { dir: path.join(gitDir, 'refs', 'heads'), filenames: new Set(['preview_main']) },
        { dir: gitDir, filenames: new Set(['packed-refs']) },
    ];

    const watchers: fs.FSWatcher[] = [];
    for (const { dir, filenames } of dirs) {
        try {
            // The dir might not exist yet (e.g. no remotes configured).
            // We tolerate ENOENT silently — the user just doesn't get
            // automatic refresh for that path until it appears.
            if (!fs.existsSync(dir)) { continue; }
            const watcher = fs.watch(dir, (_eventType, filename) => {
                if (filename && filenames.has(filename.toString())) {
                    fire();
                }
            });
            watcher.on('error', () => { /* ignore — best-effort watcher */ });
            watchers.push(watcher);
        } catch {
            // Platform-specific watcher failures (EMFILE on busy
            // filesystems, etc.) — best-effort, don't propagate.
        }
    }

    return {
        dispose() {
            if (debounce) { clearTimeout(debounce); debounce = null; }
            for (const w of watchers) {
                try { w.close(); } catch { /* already closed */ }
            }
            watchers.length = 0;
        },
    };
}
