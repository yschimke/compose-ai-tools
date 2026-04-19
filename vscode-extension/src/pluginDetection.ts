import * as fs from 'fs';
import * as path from 'path';

/**
 * Pure filesystem helpers for detecting where the Compose Preview Gradle
 * plugin is applied. Lives in its own module (no `vscode` import) so plain
 * mocha tests can exercise it without a VS Code extension host.
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

const CATALOG_PATH = path.join('gradle', 'libs.versions.toml');
/**
 * Default Gradle version-catalog accessor root. Catalogs can be renamed via
 * `versionCatalogs { create("foo") { ... } }` in settings.gradle.kts, but the
 * overwhelming convention is `libs`. We support `libs` by default; if a project
 * uses a differently-named catalog the user should apply the plugin literally
 * as a fallback.
 */
const DEFAULT_CATALOG_NAME = 'libs';

/**
 * Parses `<projectRoot>/gradle/libs.versions.toml` and returns the normalized
 * accessor paths (e.g. `"composeai.preview"`) of plugin aliases whose `id`
 * matches the Compose Preview plugin. Empty when the catalog is missing, has
 * no matching entry, or can't be read.
 *
 * Gradle normalizes `-` and `_` to `.` in accessor segments, so a TOML alias
 * `composeai-preview` becomes `libs.plugins.composeai.preview`. The returned
 * form is the post-normalization suffix (after `libs.plugins.`), ready to be
 * dropped into a match regex.
 */
export function readCatalogPluginAliases(projectRoot: string): string[] {
    const catalogPath = path.join(projectRoot, CATALOG_PATH);
    let content: string;
    try {
        content = fs.readFileSync(catalogPath, 'utf-8');
    } catch {
        return [];
    }
    return parsePluginAliases(content);
}

/**
 * TOML parser scoped to the `[plugins]` table. Returns alias names (post-
 * normalization) whose entry has `id = "ee.schimke.composeai.preview"`.
 *
 * Exposed for unit testing without touching the filesystem. Handles the
 * inline-table form used by Gradle version catalogs:
 *
 *     [plugins]
 *     composeai-preview = { id = "ee.schimke.composeai.preview", version.ref = "composeai" }
 *
 * The simpler `alias = "id:version"` string form is not valid for plugin
 * entries in Gradle catalogs, so we only look for inline tables.
 */
export function parsePluginAliases(tomlContent: string): string[] {
    const aliases: string[] = [];
    let currentTable: string | null = null;
    const lines = tomlContent.split(/\r?\n/);

    for (const rawLine of lines) {
        // Strip trailing comments (`#` outside of strings — TOML doesn't allow
        // bare `#` inside values except in quotes, which we don't need to
        // parse precisely for this narrow use case).
        const line = stripInlineComment(rawLine).trim();
        if (line.length === 0) { continue; }

        const tableMatch = /^\[([^\]]+)\]\s*$/.exec(line);
        if (tableMatch) {
            currentTable = tableMatch[1].trim();
            continue;
        }
        if (currentTable !== 'plugins') { continue; }

        // Match: alias-name = { ... id = "..." ... }
        const entryMatch = /^([A-Za-z0-9._-]+)\s*=\s*\{([^}]*)\}/.exec(line);
        if (!entryMatch) { continue; }
        const rawAlias = entryMatch[1];
        const body = entryMatch[2];
        const idMatch = /\bid\s*=\s*["']([^"']+)["']/.exec(body);
        if (!idMatch) { continue; }
        if (idMatch[1] !== PLUGIN_ID) { continue; }
        aliases.push(normalizeAliasAccessor(rawAlias));
    }
    return aliases;
}

/**
 * Gradle's version-catalog DSL treats `-` and `_` the same as `.` when
 * generating accessors: `foo-bar` and `foo_bar` both become `foo.bar`. We
 * normalize to the dotted form because that's how users write the alias in
 * `build.gradle.kts`.
 */
function normalizeAliasAccessor(rawAlias: string): string {
    return rawAlias.replace(/[-_]/g, '.');
}

function stripInlineComment(line: string): string {
    // Cheap tokenizer: respect double-quoted strings so `#` inside quotes isn't
    // mistaken for a comment. Good enough for version catalogs.
    let inString = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '"' && line[i - 1] !== '\\') { inString = !inString; }
        else if (c === '#' && !inString) { return line.slice(0, i); }
    }
    return line;
}

/**
 * Compiles a regex that matches any `alias(libs.plugins.<alias>)` usage for
 * the supplied accessor paths. Returns null when the list is empty — callers
 * should fall through to the literal-id check only.
 *
 * The resulting regex rejects `... apply false` trailers so the root build
 * script's `alias(libs.plugins.foo) apply false` pattern (declare for
 * subprojects, don't apply here) isn't counted.
 */
export function buildAliasAppliesRegex(
    aliases: string[],
    catalogName: string = DEFAULT_CATALOG_NAME,
): RegExp | null {
    if (aliases.length === 0) { return null; }
    // Escape regex metacharacters in alias segments — dots are literal here.
    const escapedAliases = aliases.map(a => a.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    const alt = escapedAliases.join('|');
    const catalog = catalogName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(
        `\\balias\\s*\\(\\s*${catalog}\\.plugins\\.(?:${alt})\\s*\\)`,
    );
}

/**
 * Returns true if `content` applies the plugin — either via the literal
 * `id("ee.schimke.composeai.preview")` form or a `alias(libs.plugins.<name>)`
 * that resolves through the version catalog.
 *
 * Matches that sit on a line with `apply false` are excluded. That's the
 * root-build.gradle pattern where a plugin is declared for subprojects but
 * *not* applied in the current module (the shape Confetti uses at the root:
 * `alias(libs.plugins.composeai.preview) apply false`). Multi-line plugin
 * blocks where `apply false` lives on a separate line are vanishingly rare
 * in practice, so single-line scoping is the pragmatic cutoff — if we start
 * seeing false positives we can revisit with a real parser.
 */
export function appliesPlugin(content: string, aliasRegex: RegExp | null): boolean {
    const lines = content.split(/\r?\n/);
    for (const line of lines) {
        if (!APPLIES_PLUGIN_RE.test(line) && !(aliasRegex && aliasRegex.test(line))) { continue; }
        if (APPLY_FALSE_RE.test(line)) { continue; }
        return true;
    }
    return false;
}

/**
 * Walks up from `filePath`'s directory looking for an ancestor containing a
 * `build.gradle.kts` that *applies* the plugin — literally or via a version-
 * catalog alias. Returns that directory, or null if no such ancestor exists
 * before hitting the filesystem root.
 *
 * This is intentionally workspace-agnostic — unlike `GradleService.resolveModule`,
 * it doesn't care whether the match is a direct child of the VS Code workspace
 * root. That matters for git worktrees nested under the workspace (e.g.
 * `.claude/worktrees/<name>/sample-android/...`): the file is in a preview
 * module, just not one that *this* Gradle project can invoke. Used by the
 * setup-prompt logic to avoid nagging users whose file is clearly already
 * plugin-enabled somewhere in its own project tree.
 *
 * Catalog discovery lags build-file discovery during the walk — the catalog
 * typically sits at the Gradle root, which is an *ancestor* of the module
 * build files we inspect on the way up. So we record build files as we walk,
 * note the catalog when we pass it, and after the walk re-check any non-
 * literal matches against the alias regex. Literal matches short-circuit as
 * before (preserving the old behavior when no catalog is involved).
 */
export function findPluginAppliedAncestor(filePath: string): string | null {
    const pending: Array<{ dir: string; content: string }> = [];
    let catalogAliases: string[] | null = null;

    let dir = path.dirname(filePath);
    while (true) {
        if (catalogAliases === null) {
            const catalogPath = path.join(dir, CATALOG_PATH);
            if (fs.existsSync(catalogPath)) {
                catalogAliases = readCatalogPluginAliases(dir);
            }
        }
        const buildFile = path.join(dir, 'build.gradle.kts');
        try {
            const content = fs.readFileSync(buildFile, 'utf-8');
            if (appliesPlugin(content, null)) {
                return dir;
            }
            pending.push({ dir, content });
        } catch { /* no build file here */ }

        const parent = path.dirname(dir);
        if (parent === dir) { break; }
        dir = parent;
    }

    if (catalogAliases && catalogAliases.length > 0) {
        const aliasRe = buildAliasAppliesRegex(catalogAliases);
        if (aliasRe) {
            for (const candidate of pending) {
                if (appliesPlugin(candidate.content, aliasRe)) { return candidate.dir; }
            }
        }
    }
    return null;
}
