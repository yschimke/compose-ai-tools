import * as fs from 'fs';
import * as path from 'path';

/**
 * Pure filesystem helpers for detecting where the Compose Preview Gradle
 * plugin is applied. Lives in its own module (no `vscode` import) so plain
 * mocha tests can exercise it without a VS Code extension host.
 */

// Matches the plugin being *applied* (`id("ee.schimke.composeai.preview")` or
// `id "ee.schimke.composeai.preview"`), not the plugin's own declaration in
// gradle-plugin/build.gradle.kts (`id = "ee.schimke.composeai.preview"`).
export const APPLIES_PLUGIN_RE = /\bid\s*[(\s]\s*["']ee\.schimke\.composeai\.preview["']/;

/**
 * Walks up from `filePath`'s directory looking for an ancestor containing a
 * `build.gradle.kts` that *applies* the plugin. Returns that directory, or
 * null if no such ancestor exists before hitting the filesystem root.
 *
 * This is intentionally workspace-agnostic — unlike `GradleService.resolveModule`,
 * it doesn't care whether the match is a direct child of the VS Code workspace
 * root. That matters for git worktrees nested under the workspace (e.g.
 * `.claude/worktrees/<name>/sample-android/...`): the file is in a preview
 * module, just not one that *this* Gradle project can invoke. Used by the
 * setup-prompt logic to avoid nagging users whose file is clearly already
 * plugin-enabled somewhere in its own project tree.
 */
export function findPluginAppliedAncestor(filePath: string): string | null {
    let dir = path.dirname(filePath);
    while (true) {
        const candidate = path.join(dir, 'build.gradle.kts');
        try {
            const content = fs.readFileSync(candidate, 'utf-8');
            if (APPLIES_PLUGIN_RE.test(content)) {
                return dir;
            }
        } catch { /* no build file here, keep walking */ }
        const parent = path.dirname(dir);
        if (parent === dir) { return null; }
        dir = parent;
    }
}
