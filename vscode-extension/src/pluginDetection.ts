import * as fs from 'fs';
import * as path from 'path';

/**
 * Pure filesystem helpers for detecting where the Compose Preview Gradle
 * plugin is applied. Lives in its own module (no `vscode` import) so plain
 * mocha tests can exercise it without a VS Code extension host.
 *
 * The authoritative signal is the `applied.json` marker written by the
 * Gradle `composePreviewApplied` task (see [GradleService.findPreviewModules]).
 * This module covers two cases the marker doesn't:
 *
 *   1. Literal `id("ee.schimke.composeai.preview")` in a `build.gradle.kts`
 *      before the first Gradle run has had a chance to write markers —
 *      avoids an "empty state" hiccup on freshly opened workspaces.
 *   2. Walking up from an arbitrary file path to find *any* ancestor
 *      applying the plugin, regardless of whether the match sits inside
 *      the current Gradle project or a nested worktree's sibling layout.
 */

export const PLUGIN_ID = 'ee.schimke.composeai.preview';

// Matches the plugin being *applied* literally (`id("ee.schimke.composeai.preview")`
// or `id "ee.schimke.composeai.preview"`), not the plugin's own declaration in
// gradle-plugin/build.gradle.kts (`id = "ee.schimke.composeai.preview"`). The
// `apply false` exclusion is applied by [appliesPlugin] at the line level — the
// raw regex is the primary plugin-reference matcher only.
export const APPLIES_PLUGIN_RE =
    /\bid\s*[(\s]\s*["']ee\.schimke\.composeai\.preview["']/;

const APPLY_FALSE_RE = /\bapply\s+false\b/;

/**
 * Returns true if `content` applies the plugin literally. Matches on a line
 * with `apply false` are excluded — that's the root-build.gradle pattern
 * where a plugin is declared for subprojects but *not* applied in the
 * current module.
 *
 * The version-catalog alias form (`alias(libs.plugins.<name>)`) is NOT
 * handled here — we rely on the `applied.json` marker written by the
 * `composePreviewApplied` Gradle task to cover that authoritatively. The
 * marker path sidesteps build-script parsing entirely, which matters
 * because catalog-alias detection would require parsing
 * `gradle/libs.versions.toml` to know which accessor segments map to our
 * plugin id — fragile and easy to get wrong.
 */
export function appliesPlugin(content: string): boolean {
    const lines = content.split(/\r?\n/);
    for (const line of lines) {
        if (!APPLIES_PLUGIN_RE.test(line)) { continue; }
        if (APPLY_FALSE_RE.test(line)) { continue; }
        return true;
    }
    return false;
}

/**
 * Walks up from `filePath`'s directory looking for an ancestor containing a
 * `build.gradle.kts` that literally applies the plugin. Returns that
 * directory, or null if no such ancestor exists before hitting the
 * filesystem root.
 *
 * This is intentionally workspace-agnostic — unlike `GradleService.resolveModule`,
 * it doesn't care whether the match is a direct child of the VS Code workspace
 * root. That matters for git worktrees nested under the workspace (e.g.
 * `.claude/worktrees/<name>/sample-android/...`): the file is in a preview
 * module, just not one that *this* Gradle project can invoke. Used by the
 * setup-prompt logic to avoid nagging users whose file is clearly already
 * plugin-enabled somewhere in its own project tree.
 *
 * Only matches the literal `id("…")` application form. Projects that apply
 * via a version catalog are picked up via the `applied.json` marker path in
 * [GradleService.findPreviewModules] once Gradle has run — this helper is a
 * purely static file-walk fallback, used where we don't have a marker.
 */
export function findPluginAppliedAncestor(filePath: string): string | null {
    let dir = path.dirname(filePath);
    while (true) {
        const candidate = path.join(dir, 'build.gradle.kts');
        try {
            const content = fs.readFileSync(candidate, 'utf-8');
            if (appliesPlugin(content)) {
                return dir;
            }
        } catch { /* no build file here, keep walking */ }
        const parent = path.dirname(dir);
        if (parent === dir) { return null; }
        dir = parent;
    }
}
