/**
 * Backend-selection types + the pure mode-resolution predicate. Lives in its
 * own module so plain mocha tests can exercise the logic without a VS Code
 * extension host (extension.ts imports `vscode`).
 */

/**
 * Backend mode selected by `composePreview.mode`.
 *
 * - `"minimal"` — gradle-only, manual renders, no daemon / data extensions /
 *   live previews. Drives `GradleOnlyDaemonGate` + `GradleOnlyDaemonScheduler`.
 * - `"full"` — daemon backend with all features. Drives `LiveDaemonGate` +
 *   `LiveDaemonScheduler`.
 */
export type ComposePreviewMode = "minimal" | "full";

/**
 * Why [resolveModeFromSettings] picked the backend it picked. Surfaced
 * through [ResolvedMode.reason] so activation + post-Gradle-sync re-evaluation
 * can decide whether to show a "switch to full mode?" reload notification.
 */
export type ModeReason =
    | "user-setting"
    | "auto-no-plugin-applied"
    | "auto-plugin-applied";

export interface ResolvedMode {
    mode: ComposePreviewMode;
    reason: ModeReason;
}

/**
 * Pure mode-selection predicate. Takes the user's settings + a minimal
 * gradle-service slice. Auto-mode picks `"full"` only when at least one
 * workspace module already applies `ee.schimke.composeai.preview` (text scan
 * or `applied.json` marker); otherwise it falls back to `"minimal"`. Auto-inject
 * onto an Android / Compose host doesn't preemptively flip the mode — the
 * plugin isn't actually applied until Gradle runs the init script, so spawning
 * a daemon at activation would fail (no `daemon-launch.json` yet). The
 * post-Gradle-sync re-evaluation handles the upgrade: once `applied.json`
 * markers exist, the extension offers a one-click reload into full mode.
 */
export function resolveModeFromSettings(
    settings: {
        mode: "auto" | "minimal" | "full";
    },
    gradleService: {
        findPreviewModules(): { modulePath: string }[];
    },
): ResolvedMode {
    if (settings.mode === "minimal") {
        return { mode: "minimal", reason: "user-setting" };
    }
    if (settings.mode === "full") {
        return { mode: "full", reason: "user-setting" };
    }
    if (gradleService.findPreviewModules().length > 0) {
        return { mode: "full", reason: "auto-plugin-applied" };
    }
    return { mode: "minimal", reason: "auto-no-plugin-applied" };
}
