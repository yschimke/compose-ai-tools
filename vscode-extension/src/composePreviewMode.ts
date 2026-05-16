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
 *
 * The legacy `"auto"` mode is gone. The bundled `--init-script` applies the
 * preview plugin to every workspace that applies a host plugin
 * (`com.android.application`, `com.android.library`, `org.jetbrains.compose`),
 * so `"full"` is safe to default to. Users on workspaces where the daemon
 * overhead isn't wanted flip to `"minimal"` explicitly.
 */
export type ComposePreviewMode = "minimal" | "full";

/**
 * Why [resolveModeFromSettings] picked the backend it picked. Today this is
 * always `"user-setting"` — `auto-*` reasons disappeared with auto-mode. The
 * field is preserved so the activation log + post-sync re-evaluation paths
 * have a stable shape; if a future heuristic mode ever returns, add reasons
 * here rather than reshaping `ResolvedMode`.
 */
export type ModeReason = "user-setting";

export interface ResolvedMode {
    mode: ComposePreviewMode;
    reason: ModeReason;
}

/**
 * Pure mode-selection predicate. Takes the user's settings; `gradleService` is
 * accepted for forward compatibility with any future heuristic (e.g. a "force
 * minimal for non-Compose workspaces" guard) but unused today.
 */
export function resolveModeFromSettings(
    settings: {
        mode: "minimal" | "full";
    },
    _gradleService?: {
        findPreviewModules(): { modulePath: string }[];
    },
): ResolvedMode {
    return { mode: settings.mode, reason: "user-setting" };
}
