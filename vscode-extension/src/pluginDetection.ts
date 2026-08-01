/**
 * Pure filesystem helpers for detecting where the Compose Preview Gradle
 * plugin is applied. Lives in its own module (no `vscode` import) so plain
 * mocha tests can exercise it without a VS Code extension host.
 *
 * The authoritative signal is the `applied.json` marker written by the
 * Gradle `composePreviewApplied` task (see [GradleService.findPreviewModules]).
 * This module covers the literal-`id("ee.schimke.composeai.preview")` case
 * in a `build.gradle.kts` before the first Gradle run has had a chance to
 * write markers — avoids an "empty state" hiccup on freshly opened workspaces.
 */

export const PLUGIN_ID = "ee.schimke.composeai.preview";

/**
 * Module build-script filenames we scan, in preference order. Kotlin DSL
 * (`build.gradle.kts`) is checked first because that's what every sample in
 * this repo uses, but Groovy DSL (`build.gradle`) is still common in
 * AGP-bootstrapped consumer projects and must not produce a false-negative
 * "plugin not applied" signal.
 */
export const BUILD_SCRIPT_NAMES = ["build.gradle.kts", "build.gradle"] as const;

// Matches the plugin being *applied* literally (`id("ee.schimke.composeai.preview")`
// or `id "ee.schimke.composeai.preview"`), not the plugin's own declaration in
// gradle-plugin/build.gradle.kts (`id = "ee.schimke.composeai.preview"`). The
// `apply false` exclusion is applied by [appliesPlugin] at the line level — the
// raw regex is the primary plugin-reference matcher only.
export const APPLIES_PLUGIN_RE =
    /\bid\s*[(\s]\s*["']ee\.schimke\.composeai\.preview["']/;

const APPLY_FALSE_RE = /\bapply\s+false\b/;

export const COMPOSE_HOST_PLUGIN_RE =
    /\bid\s*[(\s]\s*["'](?:com\.android\.application|com\.android\.library|org\.jetbrains\.compose)["']/;

export const POTENTIAL_COMPOSE_HOST_PLUGIN_RE =
    /\b(?:id\s*[(\s]\s*["'][^"']*(?:android|compose)[^"']*["']|alias\s*\(\s*libs\.plugins\.[^) ]*(?:android|compose)[^) ]*\))/i;

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
        if (!APPLIES_PLUGIN_RE.test(line)) {
            continue;
        }
        if (APPLY_FALSE_RE.test(line)) {
            continue;
        }
        return true;
    }
    return false;
}

export function hasComposeHostPlugin(content: string): boolean {
    const lines = content.split(/\r?\n/);
    for (const line of lines) {
        if (!COMPOSE_HOST_PLUGIN_RE.test(line)) {
            continue;
        }
        if (APPLY_FALSE_RE.test(line)) {
            continue;
        }
        return true;
    }
    return false;
}

export function hasPotentialComposeHostPlugin(content: string): boolean {
    const lines = content.split(/\r?\n/);
    for (const line of lines) {
        if (!POTENTIAL_COMPOSE_HOST_PLUGIN_RE.test(line)) {
            continue;
        }
        if (APPLY_FALSE_RE.test(line)) {
            continue;
        }
        return true;
    }
    return false;
}
