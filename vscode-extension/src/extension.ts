import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { GradleService, GradleApi, TaskCancelledError } from './gradleService';
import { JdkImageError } from './jdkImageErrorDetector';
import { findPluginAppliedAncestor } from './pluginDetection';
import { PreviewPanel } from './previewPanel';
import { PreviewRegistry } from './previewRegistry';
import { PreviewGutterDecorations } from './previewGutterDecorations';
import { PreviewHoverProvider } from './previewHoverProvider';
import { PreviewCodeLensProvider } from './previewCodeLensProvider';
import { AndroidManifestCodeLensProvider } from './androidManifestCodeLensProvider';
import { PreviewA11yDiagnostics } from './previewA11yDiagnostics';
import { PreviewDoctorDiagnostics } from './previewDoctorDiagnostics';
import { packageQualifiedSourcePath } from './sourcePath';
import { HEAVY_COST_THRESHOLD, PreviewInfo } from './types';
import { captureLabel } from './captureLabels';
import { DaemonGate } from './daemon/daemonGate';
import { DaemonScheduler, WarmState } from './daemon/daemonScheduler';
import { buildHistorySource, HistoryPanel, HistoryScope } from './historyPanel';
import { LogFilter, parseLogLevel } from './logFilter';
import { pickRefreshModeFor, RefreshMode } from './refreshMode';
import { mergeCalibration, PhaseDurations, ProgressState } from './buildProgress';
import { CompileError, extractCompileErrors, DiagnosticLike } from './compileErrors';

const DEBOUNCE_MS = 1500;
// Edits to the currently-scoped preview file (e.g. Claude Code's Edit tool
// writing to Previews.kt) are nearly always a single discrete event, not a
// burst — cut the wait so the refresh feels responsive. The refreshInFlight
// gate still protects against stacking builds if something happens faster.
const SCOPE_DEBOUNCE_MS = 300;
const INIT_DELAY_MS = 1000;

let gradleService: GradleService | null = null;
let daemonGate: DaemonGate | null = null;
let daemonScheduler: DaemonScheduler | null = null;
let daemonStatusItem: vscode.StatusBarItem | null = null;
let daemonStatusClearTimer: NodeJS.Timeout | null = null;
let historyPanel: HistoryPanel | null = null;
/**
 * Mutable closure for the history panel's read/diff fall-back path.
 * `buildHistorySource` captures this object once at panel-construction time
 * and reads `.current` at call-time so we never need to re-instantiate the
 * source when the user navigates between modules.
 */
const historyScopeRef: { current: HistoryScope | null } = { current: null };
/** Tracks the most recent preview set per module for daemon focus computation.
 *  The daemon path doesn't re-issue `discoverPreviews` on every save — it
 *  pushes `discoveryUpdated`. We mirror the latest snapshot here so save-
 *  scoped focus signals can map "active file → preview IDs" without an
 *  extension-side discovery round-trip. */
/**
 * Returns the mtime (ms since epoch) of the oldest on-disk PNG referenced by
 * any rendered capture in [previews] across [modules], or null if none of the
 * paths exist. Cheap stat call per capture; results are not cached because
 * renders rewrite these files on every save.
 */
async function oldestRenderMtime(
    previews: PreviewInfo[],
    modules: string[],
): Promise<number | null> {
    if (!gradleService) { return null; }
    let oldest: number | null = null;
    for (const preview of previews) {
        for (const capture of preview.captures) {
            if (!capture.renderOutput) { continue; }
            // Match the path the readPreviewImage resolver builds.
            const mod = modules.find(m => previewModuleMap.get(preview.id) === m) ?? modules[0];
            const pngPath = path.join(
                gradleService.workspaceRoot, mod,
                'build', 'compose-previews', capture.renderOutput,
            );
            try {
                const stat = await fs.promises.stat(pngPath);
                const mtime = stat.mtimeMs;
                if (oldest == null || mtime < oldest) { oldest = mtime; }
            } catch {
                // File missing — discover-only pass on a never-rendered preview.
                // Don't count toward oldest; the banner reflects what the user
                // is actually seeing on screen.
            }
        }
    }
    return oldest;
}

/** Stat the source file and return its mtime, or null on any error. The
 *  banner uses this as the "freshness reference" — `.kt` newer than the
 *  oldest PNG = renders are stale relative to the source. */
async function sourceFileMtime(filePath: string): Promise<number | null> {
    try {
        const stat = await fs.promises.stat(filePath);
        return stat.mtimeMs;
    } catch {
        return null;
    }
}

/**
 * "5 minutes ago", "2 hours ago", "3 days ago". Coarse — the banner exists to
 * communicate "this is old", not to time-stamp anything.
 */
function formatRelativeAge(ageMs: number): string {
    const seconds = Math.floor(ageMs / 1000);
    if (seconds < 60) { return `${seconds}s ago`; }
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) { return `${minutes}m ago`; }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) { return `${hours}h ago`; }
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
}

/**
 * True when the panel currently shows a "Showing previews from <ago>, but
 * <file> has changed since" banner the extension posted because the active
 * source file's mtime is newer than the oldest on-disk PNG. Used so we only
 * clear the banner when one *we* set is up — not a build-error or
 * filter-empty notice. Cleared when a fresh `updateImage` arrives from the
 * daemon, or when a forceRender refresh completes.
 */
let staleBannerShown = false;

const moduleManifestCache = new Map<string, PreviewInfo[]>();
let panel: PreviewPanel | null = null;
let debounceTimer: NodeJS.Timeout | null = null;
let selectedModule: string | null = null;
let pendingRefresh: AbortController | null = null;
let hasPreviewsLoaded = false;
let lastLoadedModules: string[] = [];
/**
 * The file path the panel is currently scoped to. Updated whenever a refresh
 * successfully resolves a module. Webview-initiated refreshes reuse this
 * rather than falling back to `activeTextEditor`, which can drift when the
 * webview has focus (undefined) or resolve to an unrelated editor.
 */
let currentScopeFile: string | null = null;
const registry = new PreviewRegistry();
/** previewId → module, updated on every refresh. Used to look up the
 *  owning module when the webview posts a per-preview action. */
const previewModuleMap = new Map<string, string>();
/** Tracks files saved at least once since activation. First save on a file
 *  renders immediately; subsequent saves go through the debounce path. */
const firstSaveSeen = new Set<string>();
/** Save-driven refresh coalescing state. See {@link enqueueSaveRefresh}. */
let pendingSavePath: string | null = null;
let debounceElapsed = true;
let refreshInFlight = false;
/** Workspace-state key that suppresses the "plugin not applied" notification. */
const DISMISS_KEY = 'composePreview.dismissedMissingPluginWarning';
/** Workspace-state key for per-module phase-duration calibration. Shape:
 *  `Record<moduleId, PhaseDurations>`. Updated after every successful refresh
 *  so the progress bar's animation rate matches what each module actually
 *  takes (a Wear OS sample with Robolectric is much slower than a CMP one). */
const PROGRESS_CALIBRATION_KEY = 'composePreview.progressCalibration';
/** Captured in activate() so notification helpers can reach workspaceState. */
let extensionContext: vscode.ExtensionContext | null = null;
/** Module-scoped logger wired up in activate() so refresh() can trace state
 *  transitions into the "Compose Preview" output channel. Populate-then-blank
 *  bugs are hard to diagnose from logs unless we explicitly announce each
 *  message we send to the webview. */
let logLine: (msg: string) => void = () => { /* noop pre-activate */ };
/** Guard against firing the "plugin not applied" notification more than once
 *  per session — users shouldn't see it on every refresh tick. */
let warnedMissingPluginThisSession = false;
/** Same idea for the jlink-missing notification: save-driven refreshes would
 *  otherwise re-surface it on every build after the user dismissed it. */
let warnedJdkImageThisSession = false;
// Decide whether a file "probably wants previews" with the plugin off. Kept
// deliberately loose: the file just needs to contain the substring "Preview"
// (covers `@Preview`, `@PreviewLightDark`, preview-tooling imports, and
// custom-multipreview definition sites) and be a Composable file at all.
// False positives are cheap — an extra informational message. False
// negatives (silence when the user wanted a nudge) are the worse outcome.
const SETUP_DOCS_URL = 'https://github.com/yschimke/compose-ai-tools/tree/main/vscode-extension#readme';
const JDK_DOCS_URL = 'https://github.com/yschimke/compose-ai-tools/blob/main/docs/AGENTS.md#important-constraints';

/**
 * Show the remediation notification for a detected JdkImageError. The offered
 * "Open JDK setting" action reveals `java.import.gradle.java.home` — the
 * setting that `vscjava.vscode-gradle` actually reads when launching Gradle.
 * De-duped per session so save-driven rebuilds don't re-open it.
 */
function showJdkImageRemediation(err: JdkImageError): void {
    if (warnedJdkImageThisSession) { return; }
    warnedJdkImageThisSession = true;
    const reason = err.finding.reason ? ` (${err.finding.reason})` : '';
    const message = `Compose Preview: Gradle is using a JRE without jlink${reason}. `
        + 'Point it at a full JDK to build Android modules. '
        + `Path: ${err.finding.jlinkPath}`;
    const OPEN_SETTING = 'Open JDK setting';
    const DOCS = 'Learn more';
    void vscode.window.showErrorMessage(message, OPEN_SETTING, DOCS).then(action => {
        if (action === OPEN_SETTING) {
            void vscode.commands.executeCommand(
                'workbench.action.openSettings',
                'java.import.gradle.java.home',
            );
        } else if (action === DOCS) {
            void vscode.env.openExternal(vscode.Uri.parse(JDK_DOCS_URL));
        }
    });
}

/**
 * Test seam exposed via `activate`'s return value when the extension is
 * loaded by `@vscode/test-electron`. Production builds do NOT consume this —
 * `activate` returns void in normal use. The integration tests in
 * `src/test/electron/` reach for it through
 * `vscode.extensions.getExtension('yuri-schimke.compose-preview').exports`.
 *
 * The shape is deliberately small: enough to drive a refresh against a
 * stub Gradle API and to inspect the messages the extension posts at the
 * webview. Keeps test code from depending on internal module state.
 */
export interface ComposePreviewTestApi {
    /** Replace the GradleService with one wired to the supplied stub API. */
    injectGradleApi(api: GradleApi): void;
    /** Drive {@link refresh} synchronously from a test. */
    triggerRefresh(filePath: string, force?: boolean, tier?: 'fast' | 'full'): Promise<void>;
    /** Snapshot of every panel message posted since [resetMessages]. */
    getPostedMessages(): unknown[];
    /** Drop the captured-messages buffer. */
    resetMessages(): void;
}

/** Captured messages for [ComposePreviewTestApi.getPostedMessages]. Only
 *  populated when running under `COMPOSE_PREVIEW_TEST_MODE=1`. */
const postedMessageLog: unknown[] = [];

export async function activate(context: vscode.ExtensionContext): Promise<ComposePreviewTestApi | void> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) { return; }

    extensionContext = context;
    const workspaceRoot = workspaceFolders[0].uri.fsPath;
    const outputChannel = vscode.window.createOutputChannel('Compose Preview');
    context.subscriptions.push(outputChannel);
    // `composePreview.logging.level` is read on every emit so a settings.json
    // edit takes effect immediately without a window reload.
    const logFilter = new LogFilter(() =>
        parseLogLevel(
            vscode.workspace.getConfiguration('composePreview').get<string>('logging.level'),
        ),
    );
    logLine = (msg: string) => {
        const line = `[refresh] ${msg}`;
        if (logFilter.shouldEmitInformational(line)) { outputChannel.appendLine(line); }
    };

    // Startup fingerprint — answers "is the build I think I installed
    // actually loaded?" when triaging a save-loop bug. The extension version
    // comes from package.json (bumped by release-please); the path locates
    // the loaded module on disk so an old install lingering from a prior
    // session is obvious.
    const extId = 'yuri-schimke.compose-preview';
    const ext = vscode.extensions.getExtension(extId);
    const extVersion = ext?.packageJSON?.version ?? 'unknown';
    const extPath = ext?.extensionPath ?? '<unresolved>';
    outputChannel.appendLine(
        `[startup] compose-preview v${extVersion} loaded from ${extPath}`,
    );

    const isTestMode = process.env.COMPOSE_PREVIEW_TEST_MODE === '1';

    // vscjava.vscode-gradle is declared as an extensionDependency, so it's
    // guaranteed to be installed in production. Under
    // `COMPOSE_PREVIEW_TEST_MODE=1` we tolerate the dep being a stub (the
    // tests inject their own GradleApi via [ComposePreviewTestApi]).
    const gradleExt = vscode.extensions.getExtension('vscjava.vscode-gradle');
    if (!gradleExt && !isTestMode) {
        vscode.window.showErrorMessage(
            'Compose Preview requires the "Gradle for Java" extension (vscjava.vscode-gradle).',
        );
        return;
    }
    const gradleApi: GradleApi = gradleExt
        ? ((await gradleExt.activate()) as GradleApi)
        : { runTask: async () => { /* test-mode placeholder */ },
            cancelRunTask: async () => { /* test-mode placeholder */ } };

    gradleService = new GradleService(workspaceRoot, gradleApi, outputChannel, () => {
        // Read config lazily on each run so user toggles take effect without a reload.
        const args: string[] = [];
        const config = vscode.workspace.getConfiguration('composePreview');
        if (config.get<boolean>('accessibilityChecks.enabled')) {
            args.push('-PcomposePreview.accessibilityChecks.enabled=true');
        }
        return args;
    }, logFilter);

    // Daemon path is opt-in via composePreview.experimental.daemon.enabled.
    // When disabled (default) the gate's `isEnabled()` returns false and the
    // scheduler is never asked to spawn anything — the existing Gradle path
    // is the entire user-facing behaviour. When enabled, the scheduler runs
    // *alongside* the existing refresh logic: saves and viewport changes push
    // notifications to the daemon, the daemon emits renderFinished, and the
    // extension forwards PNGs to the panel as they arrive (typically ahead
    // of Gradle's `renderPreviews` on a hot sandbox). The Gradle path remains
    // the safety net; if the daemon fails we silently fall back without any
    // user-visible change.
    daemonGate = new DaemonGate(workspaceRoot, '0.1.0', outputChannel, logFilter);
    daemonScheduler = new DaemonScheduler(daemonGate, {
        onPreviewImageReady: (_moduleId, previewId, imageBase64) => {
            if (!panel) { return; }
            // Fresh daemon render arriving — if a stale banner is up, this is
            // the moment to retire it. Guarded by staleBannerShown so we only
            // clear a banner we set ourselves; legitimate showMessage banners
            // (build error, filter-empty notice) are left alone.
            if (staleBannerShown) {
                panel.postMessage({ command: 'showMessage', text: '' });
                staleBannerShown = false;
            }
            // Capture index 0 — the daemon's v1 renderFinished targets the
            // representative capture only. Multi-capture (animated) renders
            // still come through the Gradle path; the daemon's predictive
            // pre-warm focuses on the cheap interactive loop.
            panel.postMessage({
                command: 'updateImage',
                previewId,
                captureIndex: 0,
                imageData: imageBase64,
            });
        },
        onRenderFailed: (_moduleId, previewId, message) => {
            if (!panel) { return; }
            panel.postMessage({
                command: 'setImageError',
                previewId,
                captureIndex: 0,
                message,
            });
        },
        onClasspathDirty: (moduleId, detail) => {
            outputChannel.appendLine(
                `[daemon] classpath dirty for ${moduleId}: ${detail} — falling back to Gradle`,
            );
            // Daemon will exit on its own (PROTOCOL.md § 6); the channel-
            // closed handler in DaemonGate evicts the entry. Next save runs
            // Gradle, which re-bootstraps a fresh daemon when the user
            // re-enables it via composePreviewDaemonStart.
        },
        onHistoryAdded: (_moduleId, params) => {
            // Phase H7 — daemon push: a fresh render landed and was
            // archived. Forward to the History panel; the panel filters
            // by `entry.module` against the currently-scoped module so an
            // unrelated render in a background module doesn't pop the
            // timeline.
            historyPanel?.onHistoryAdded(params);
        },
    }, outputChannel);

    // Status-bar slot for daemon lifecycle. Hidden when the daemon flag is
    // off or no module is currently warming. Surfacing the cold-bootstrap
    // pause (typically 2-4 s on first scope-in) avoids the "panel is stuck"
    // perception on the experimental flag's first-time UX.
    daemonStatusItem = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Left, 90,
    );
    daemonStatusItem.command = 'workbench.action.output.toggleOutput';
    context.subscriptions.push(daemonStatusItem);

    panel = new PreviewPanel(context.extensionUri, handleWebviewMessage);
    if (isTestMode) {
        // Tap into every outgoing webview message so the test API can assert
        // on the message stream. The wrapper preserves the original
        // postMessage's behaviour (forwards to view?.webview.postMessage).
        const original = panel.postMessage.bind(panel);
        panel.postMessage = (msg) => {
            postedMessageLog.push(msg);
            original(msg);
        };
    }
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(PreviewPanel.viewId, panel),
    );

    // Phase H7 — Preview History panel (HISTORY.md § "VS Code integration").
    // Live when `composePreview.experimental.daemon.enabled` is true; falls
    // back to reading `<projectDir>/.compose-preview-history/index.jsonl` +
    // sidecars when the daemon isn't healthy. View shows up unconditionally
    // — the view container is contributed in package.json — but its content
    // is empty when no Kotlin file is in scope. The setScope() driver is
    // wired below into the active-editor change events.
    historyScopeRef.current = null;
    historyPanel = new HistoryPanel(
        context.extensionUri,
        buildHistorySource({
            isDaemonReady: (moduleId) => daemonGate?.isDaemonReady(moduleId) ?? false,
            daemonList: async (scope) => {
                const client = await daemonGate?.getOrSpawn(
                    scope.moduleId, daemonScheduler!.daemonEvents(scope.moduleId),
                );
                if (!client) { throw new Error('daemon unavailable'); }
                return client.historyList({ previewId: scope.previewId });
            },
            daemonRead: async (id) => {
                const moduleId = historyScopeRef.current?.moduleId;
                if (!moduleId) { throw new Error('no scope'); }
                const client = await daemonGate?.getOrSpawn(
                    moduleId, daemonScheduler!.daemonEvents(moduleId),
                );
                if (!client) { throw new Error('daemon unavailable'); }
                return client.historyRead({ id, inline: false });
            },
            daemonDiff: async (fromId, toId) => {
                const moduleId = historyScopeRef.current?.moduleId;
                if (!moduleId) { throw new Error('no scope'); }
                const client = await daemonGate?.getOrSpawn(
                    moduleId, daemonScheduler!.daemonEvents(moduleId),
                );
                if (!client) { throw new Error('daemon unavailable'); }
                return client.historyDiff({ from: fromId, to: toId, mode: 'metadata' });
            },
            getCurrentScope: () => historyScopeRef.current,
            logger: outputChannel,
        }),
    );
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(HistoryPanel.viewId, historyPanel),
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('composePreview.refresh', () =>
            refresh(true, currentScopeFile ?? undefined)),
        vscode.commands.registerCommand('composePreview.renderAll', () =>
            refresh(true, currentScopeFile ?? undefined)),
        vscode.commands.registerCommand('composePreview.runForFile', (filePath?: string) => {
            const target = filePath ?? currentScopeFile ?? undefined;
            if (target) { refresh(true, target); }
        }),
        vscode.commands.registerCommand('composePreview.openModuleBuildFile',
            (filePath?: string) => openModuleBuildFile(workspaceRoot, filePath)),
        vscode.commands.registerCommand('composePreview.toggleAccessibilityChecks', async () => {
            // Session-wide toggle — mutates the workspace setting so the
            // gradleService's `-P` override picks up the new value on the
            // next task run. We follow up with a refresh so the webview +
            // diagnostics update immediately; otherwise the new state
            // would only be visible after the user's next edit.
            const config = vscode.workspace.getConfiguration('composePreview');
            const current = config.get<boolean>('accessibilityChecks.enabled') ?? false;
            await config.update(
                'accessibilityChecks.enabled',
                !current,
                vscode.ConfigurationTarget.Workspace,
            );
            vscode.window.showInformationMessage(
                `Compose Preview: accessibility checks ${!current ? 'ON' : 'OFF'} for this workspace`,
            );
            // Force a fresh render — the render task's inputs changed
            // (different -P arg), so we need to re-resolve findings.
            gradleService?.invalidateCache();
            await refresh(true, currentScopeFile ?? undefined);
        }),
        vscode.commands.registerCommand('composePreview.focusPreview',
            async (functionName: string, filePath?: string) => {
                if (!panel) { return; }
                // Reveal the sidebar view. This is the stable command contributed
                // by VS Code for any registered view (`<viewId>.focus`).
                await vscode.commands.executeCommand(`${PreviewPanel.viewId}.focus`);
                // If the caller passed a file, scope the panel to it before
                // filtering — otherwise the currently-scoped module is reused.
                if (filePath && filePath !== currentScopeFile) {
                    await refresh(false, filePath);
                }
                panel.postMessage({ command: 'setFunctionFilter', functionName });
            },
        ),
        // The daemon JVM caches the renderer JAR + sandbox state across every
        // render in a session — that's the whole point of the daemon. The
        // downside is that rebuilding the daemon (`./gradlew :daemon:android:…`
        // or any change to the renderer/host code) doesn't take effect until
        // the running JVM dies. Idle timeout is 5s, but a user actively saving
        // never trips it. This command is the explicit escape hatch: shut
        // down every running daemon, clear the bootstrapped-modules memo, and
        // re-run the bootstrap task so the next save picks up the new JAR.
        vscode.commands.registerCommand('composePreview.restartDaemon', async () => {
            if (!daemonGate || !daemonScheduler) {
                vscode.window.showInformationMessage(
                    'Compose Preview: daemon gate not initialised yet.',
                );
                return;
            }
            const restarted = await daemonGate.restartAll();
            // Clear the per-session bootstrap memo so the next save re-runs
            // `composePreviewDaemonStart`, which writes a fresh
            // `daemon-launch.json` pointing at the rebuilt JAR. Without
            // clearing this the spawn would skip the bootstrap and the
            // descriptor on disk could still reference the previous build.
            daemonBootstrappedModules.clear();
            // Hide the status-bar item — its text references a specific module
            // that's no longer relevant; the next warmModule call will repopulate
            // it through its progress callback.
            daemonStatusItem?.hide();
            outputChannel.appendLine(
                `[daemon] restartDaemon: shut down ${restarted.length} running daemon(s)` +
                (restarted.length > 0 ? ` [${restarted.join(', ')}]` : '') +
                ` — next save will respawn from the on-disk JAR`,
            );
            vscode.window.showInformationMessage(
                restarted.length === 0
                    ? 'Compose Preview: no daemon was running.'
                    : `Compose Preview: restarted ${restarted.length} daemon(s). ` +
                      'Next save will respawn from the on-disk JAR.',
            );
        }),
    );

    const detectLog = (msg: string) => {
        const line = `[detect] ${msg}`;
        if (logFilter.shouldEmitInformational(line)) { outputChannel.appendLine(line); }
    };
    const gutterDecorations = new PreviewGutterDecorations(context.extensionUri, registry, detectLog);
    const hoverProvider = new PreviewHoverProvider(registry, detectLog);
    const codeLensProvider = new PreviewCodeLensProvider(registry, detectLog);
    const a11yDiagnostics = new PreviewA11yDiagnostics(registry, detectLog);
    const doctorDiagnostics = new PreviewDoctorDiagnostics(
        gradleService,
        workspaceRoot,
        (msg) => {
            const line = `[doctor] ${msg}`;
            if (logFilter.shouldEmitInformational(line)) { outputChannel.appendLine(line); }
        },
    );
    const kotlinFiles: vscode.DocumentSelector = { language: 'kotlin', scheme: 'file' };
    // AndroidManifest.xml lives at module-root, not under res/, so the existing
    // `**/res/**/*.xml` watcher misses it. Match files by name rather than
    // language id — the language id depends on the user's xml extension setup
    // and isn't a load-bearing signal.
    const androidManifestFiles: vscode.DocumentSelector = {
        scheme: 'file',
        pattern: '**/AndroidManifest.xml',
    };
    const androidManifestCodeLensProvider = new AndroidManifestCodeLensProvider(gradleService);
    context.subscriptions.push(
        vscode.languages.registerHoverProvider(kotlinFiles, hoverProvider),
        vscode.languages.registerCodeLensProvider(kotlinFiles, codeLensProvider),
        vscode.languages.registerCodeLensProvider(
            androidManifestFiles,
            androidManifestCodeLensProvider,
        ),
        codeLensProvider,
        androidManifestCodeLensProvider,
        gutterDecorations,
        a11yDiagnostics,
        doctorDiagnostics,
        { dispose: () => registry.dispose() },
    );

    // Open the rendered PNG / GIF in VS Code's default editor. `vscode.open`
    // routes PNGs to the built-in image viewer and GIFs to the same viewer
    // (animated playback included). Non-existent paths surface as a standard
    // "Unable to open file" error — useful signal that the consumer hasn't
    // run `:<module>:renderAndroidResources` yet.
    context.subscriptions.push(
        vscode.commands.registerCommand(
            'composePreview.previewResource',
            (pngPath: string, _resourceId: string) => {
                void vscode.commands.executeCommand('vscode.open', vscode.Uri.file(pngPath));
            },
        ),
    );

    // Refresh doctor diagnostics on first load, on-demand from the command,
    // and whenever we discover new modules. Each refresh kicks the
    // `composePreviewDoctor` task per module — cheap (no render), but we
    // don't want to spam on every keystroke, hence the explicit trigger
    // points rather than a document-change hook.
    const refreshDoctor = async () => {
        // gradleService is non-null inside this activation scope (initialised
        // a few lines up), but the module-scope nullable declaration forces
        // a local alias for the type-checker.
        if (!gradleService) { return; }
        await doctorDiagnostics.refresh(gradleService.findPreviewModules());
    };
    context.subscriptions.push(
        vscode.commands.registerCommand('composePreview.runDoctor', () => {
            void vscode.window.withProgress(
                { location: vscode.ProgressLocation.Window, title: 'Running compose-preview doctor…' },
                refreshDoctor,
            );
        }),
    );
    // Refresh applied-markers in the background, then replay the doctor refresh
    // so it picks up any modules that only become visible via the authoritative
    // `applied.json` path (e.g. a module whose build.gradle.kts uses a
    // non-standard catalog accessor our scan doesn't recognise). First-activation
    // doctor run still fires immediately off the scan result — we don't want to
    // gate startup diagnostics on a Gradle configuration.
    void refreshDoctor();
    void gradleService.bootstrapAppliedMarkers().then(() => {
        void refreshDoctor();
    });

    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(editor => {
            if (editor?.document.languageId === 'kotlin') {
                const filePath = editor.document.uri.fsPath;
                // Focus toggling (editor ↔ webview/terminal ↔ back) fires this
                // event with the same Kotlin file. Re-running refresh there
                // just cancels any in-flight render, flashes spinners, and
                // burns a Gradle invocation — all for a no-op.
                if (filePath === currentScopeFile) { return; }
                refresh(false, filePath);
                // Pre-warm the daemon for this file's module so the first
                // save in the session collapses to "kotlinc + render"
                // instead of "Gradle bootstrap + JVM spawn + sandbox init
                // + render". No-op when the daemon flag is off.
                void warmDaemonForFile(filePath);
                return;
            }
            // New active isn't a Kotlin editor (webview focus, Agent plan,
            // output pane, no active editor at all). If our sticky `.kt` is
            // still visible in a split, keep the panel as-is; otherwise the
            // sticky got covered/closed and we need to re-resolve (which may
            // blank the panel) — issue #145.
            if (currentScopeFile && !isFileVisibleInEditor(currentScopeFile)) {
                refresh(false);
            }
        }),
    );

    // Fires on tab close and on editor-group layout changes. Catches the edge
    // case where the sticky `.kt` was open in a split (not active) and the
    // user closed that tab — onDidChangeActiveTextEditor doesn't fire because
    // the active editor didn't change.
    context.subscriptions.push(
        vscode.window.onDidChangeVisibleTextEditors(() => {
            if (currentScopeFile && !isFileVisibleInEditor(currentScopeFile)) {
                refresh(false);
            }
        }),
    );

    // Editor saves (Ctrl+S, auto-save). The first save of a given file since
    // activation refreshes immediately so the user sees their change right
    // away; subsequent saves coalesce through a debounced + in-flight-aware
    // queue so we never stack builds on top of each other.
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument(doc => {
            if (!isSourceFile(doc.uri.fsPath)) { return; }
            // The daemon-vs-Gradle decision is made inside runRefreshExclusive
            // — either path runs, never both. See `pickRefreshMode` for the
            // health gate. When the daemon flag is off (default) the gate
            // always returns 'gradle' so behaviour is byte-identical to today.
            if (!firstSaveSeen.has(doc.uri.fsPath) && !refreshInFlight && pendingSavePath === null) {
                firstSaveSeen.add(doc.uri.fsPath);
                invalidateModuleCache(doc.uri.fsPath);
                void runRefreshExclusive(doc.uri.fsPath);
            } else {
                firstSaveSeen.add(doc.uri.fsPath);
                enqueueSaveRefresh(doc.uri.fsPath);
            }
        }),
    );

    // External file system changes (git, refactor tools). Gated by
    // isSourceFile so Gradle-generated files under `<module>/build/**` don't
    // feed every render back into the refresh queue — that loop is what made
    // the panel look "jumpy" with spinners reappearing over cards after each
    // build completed.
    const onWatcherEvent = (uri: vscode.Uri) => {
        if (isSourceFile(uri.fsPath)) { enqueueSaveRefresh(uri.fsPath); }
    };
    for (const glob of ['**/*.kt', '**/res/**/*.xml']) {
        const watcher = vscode.workspace.createFileSystemWatcher(glob);
        watcher.onDidChange(onWatcherEvent);
        watcher.onDidCreate(onWatcherEvent);
        watcher.onDidDelete(onWatcherEvent);
        context.subscriptions.push(watcher);
    }

    context.subscriptions.push({ dispose: () => gradleService?.dispose() });

    if (!isTestMode) {
        // Suppressed under tests so the activation-time auto-refresh doesn't
        // race with whatever the test driver wants to set up via the test
        // API. Tests call triggerRefresh themselves at known points.
        setTimeout(() => {
            const active = vscode.window.activeTextEditor;
            if (active?.document.languageId === 'kotlin') {
                void runActivationRefresh(active.document.uri.fsPath);
            } else {
                // No Kotlin file in focus — let refresh() emit the empty-state
                // message without trying to load anything.
                refresh(false);
            }
        }, INIT_DELAY_MS);
    }

    if (isTestMode) {
        const testApi: ComposePreviewTestApi = {
            injectGradleApi(api: GradleApi): void {
                gradleService = new GradleService(workspaceRoot, api, outputChannel, () => {
                    const args: string[] = [];
                    const config = vscode.workspace.getConfiguration('composePreview');
                    if (config.get<boolean>('accessibilityChecks.enabled')) {
                        args.push('-PcomposePreview.accessibilityChecks.enabled=true');
                    }
                    return args;
                }, logFilter);
            },
            triggerRefresh(filePath: string, force = false, tier: 'fast' | 'full' = 'full'): Promise<void> {
                return refresh(force, filePath, tier).then(() => {});
            },
            getPostedMessages(): unknown[] {
                return [...postedMessageLog];
            },
            resetMessages(): void {
                postedMessageLog.length = 0;
            },
        };
        return testApi;
    }
}

export function deactivate() {
    if (debounceTimer) { clearTimeout(debounceTimer); }
    pendingRefresh?.abort();
    // Drain any live daemon JVMs so the user doesn't end up with orphaned
    // processes after a window close. Fire-and-forget — VS Code won't wait
    // for an async deactivate beyond a few seconds anyway.
    void daemonGate?.dispose();
}

function sameScope(a: string[], b: string[]): boolean {
    if (a.length !== b.length) { return false; }
    const set = new Set(b);
    return a.every(m => set.has(m));
}

function isSourceFile(filePath: string): boolean {
    if (filePath.includes(`${path.sep}build${path.sep}`)) { return false; }
    return /\.(kt|xml|json|properties)$/i.test(filePath);
}

/** True iff this is a Kotlin source file (.kt) — not a Gradle build script. */
function isPreviewSourceFile(filePath: string): boolean {
    return filePath.endsWith('.kt') && !filePath.endsWith('.gradle.kts');
}

/**
 * Resolve which file the panel should scope to, in priority order:
 *   1. Caller-provided path (explicit user action).
 *   2. The active text editor, if it's a Kotlin source file.
 *   3. The last file a refresh successfully scoped to — **only if it's still
 *      rendered in a visible editor**. Keeps the panel stable when focus moves
 *      to a non-editor view (Explorer, SCM, Agent plan) and the `.kt` remains
 *      visible in a split. When the tab gets covered or closed, the sticky
 *      fallback goes away and the panel blanks (issue #145). Matches the
 *      pattern VS Code's own Outline / Timeline views use.
 *   4. Any other visible Kotlin editor (fallback for when the sticky file is
 *      gone but the user has another `.kt` open elsewhere).
 * Returns the resolved path and a short tag describing which fallback was used,
 * for logging.
 */
function resolveScopeFile(forFilePath?: string): { file?: string; source: string } {
    if (forFilePath) { return { file: forFilePath, source: 'caller' }; }

    const active = vscode.window.activeTextEditor?.document;
    if (active && active.languageId === 'kotlin' && isPreviewSourceFile(active.uri.fsPath)) {
        return { file: active.uri.fsPath, source: 'active' };
    }

    if (currentScopeFile && isPreviewSourceFile(currentScopeFile)
        && isFileVisibleInEditor(currentScopeFile)) {
        return { file: currentScopeFile, source: 'sticky' };
    }

    for (const editor of vscode.window.visibleTextEditors) {
        const doc = editor.document;
        if (doc.languageId === 'kotlin' && isPreviewSourceFile(doc.uri.fsPath)) {
            return { file: doc.uri.fsPath, source: 'visible' };
        }
    }

    return { source: 'none' };
}

/**
 * True iff the given file is currently rendered in at least one text editor.
 * ``visibleTextEditors`` reflects the editors the user can actually see — an
 * open-but-covered tab (e.g. obscured by an Agent-plan webview in the same
 * group) is *not* visible.
 */
function isFileVisibleInEditor(filePath: string): boolean {
    return vscode.window.visibleTextEditors.some(
        editor => editor.document.uri.fsPath === filePath,
    );
}

function invalidateModuleCache(filePath: string): void {
    if (!gradleService) { return; }
    const module = gradleService.resolveModule(filePath);
    if (module) { gradleService.invalidateCache(module); }
}

/**
 * Side-channel save handler that pushes a `fileChanged` + focus-scoped
 * `renderNow` to the daemon when the daemon path is enabled and a daemon
 * exists for this file's module. Runs alongside the Gradle refresh, never
 * instead of it; the daemon's faster updateImage simply lands sooner. When
 * the daemon is disabled or unhealthy, this is a complete no-op — the
 * existing Gradle path is the entire user-facing behaviour.
 */
async function notifyDaemonOfSave(filePath: string): Promise<boolean> {
    if (!daemonGate?.isEnabled() || !daemonScheduler || !gradleService) { return false; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return false; }

    // Bootstrap (Gradle task + JVM spawn) happens once per module per
    // session. Normally it has already fired from the active-editor warm
    // path; on the rare case where the user saves before scope-in (e.g.
    // external file save, another editor split) we cover ourselves here.
    if (!daemonBootstrappedModules.has(module)) {
        daemonBootstrappedModules.add(module);
        await daemonScheduler.warmModule(
            gradleService, module,
            (state) => updateDaemonStatus(module, state),
        );
    }

    const ok = await daemonScheduler.ensureModule(module);
    if (!ok) { return false; }
    await daemonScheduler.fileChanged(module, filePath);

    // Focus scope = the saved file's previews, derived from the most recent
    // manifest snapshot we got from Gradle's discoverPreviews. The daemon
    // reads this for queue ordering — focused first. If we don't yet have a
    // manifest for this module the focus call is skipped; the next refresh
    // will populate moduleManifestCache. Returning true with no ids is
    // intentional — the daemon already saw `fileChanged` so its internal
    // discovery + render will catch any newly-discovered previews on its
    // own; the caller just shouldn't escalate to a Gradle render in that
    // case (the panel will repopulate via discover + the daemon's
    // discoveryUpdated push).
    const filterFile = packageQualifiedSourcePath(filePath);
    const manifest = moduleManifestCache.get(module) ?? [];
    const ids = manifest.filter(p => p.sourceFile === filterFile).map(p => p.id);
    if (ids.length === 0) { return true; }
    await daemonScheduler.setFocus(module, ids);
    return await daemonScheduler.renderNow(module, ids, 'fast', 'save');
}

/**
 * Activation-time refresh sequence. Three phases, one after the other:
 *
 *   1. `refresh(false, filePath)` — populates the panel from on-disk PNGs so
 *      the user sees *something* immediately (cards with cached images, plus
 *      the "Showing previews from <ago>" banner if those PNGs are stale
 *      relative to the source file).
 *   2. `warmDaemonForFile(filePath)` — runs `composePreviewDaemonStart` and
 *      spawns the daemon JVM. No-op if the daemon flag is off.
 *   3. If the daemon is healthy by the time it's done warming, fire a
 *      `notifyDaemonOfSave`-equivalent so the panel gets a fresh render even
 *      without the user touching the file. Catches the "edited a file in
 *      another module / `git pull`'d / agent rewrote a Composable" case
 *      where the on-disk PNGs are stale relative to source even though the
 *      source file the user is currently looking at is itself unchanged.
 *      The banner from phase 1 stays up only long enough for phase 3's
 *      first updateImage to clear it; on a hot daemon that's a couple of
 *      seconds, on a cold spawn 5-10s.
 *
 * Failures in phases 2 or 3 silently fall back: phase 1 already gave the
 * user something to look at, and the next save will re-trigger the whole
 * pipeline through `runRefreshExclusive`.
 */
async function runActivationRefresh(filePath: string): Promise<void> {
    await refresh(false, filePath);
    await warmDaemonForFile(filePath);
    if (!gradleService || !daemonGate) { return; }
    const module = gradleService.resolveModule(filePath);
    if (!module || !daemonGate.isDaemonReady(module)) { return; }
    // Phase 3 — kick off a fresh render through the daemon. Reuses the same
    // notify path the save-driven flow uses; the daemon does the swap +
    // renderNow + the panel updates via the existing onPreviewImageReady
    // wiring. No need for a separate code path.
    await notifyDaemonOfSave(filePath);
}

/**
 * Eager pre-warm path: when the user navigates to a Kotlin file in a
 * daemon-enabled module, kick off `composePreviewDaemonStart` + JVM spawn
 * in the background so the first save in the session collapses to
 * "kotlinc + render" instead of paying the cold-bootstrap latency on the
 * user's interactive path. No-op when the daemon flag is off, when the
 * file isn't in a preview module, or when the daemon is already up.
 */
async function warmDaemonForFile(filePath: string): Promise<void> {
    if (!daemonGate?.isEnabled() || !daemonScheduler || !gradleService) { return; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return; }
    if (daemonBootstrappedModules.has(module)) { return; }
    daemonBootstrappedModules.add(module);
    await daemonScheduler.warmModule(
        gradleService, module,
        (state) => updateDaemonStatus(module, state),
    );
}

/**
 * Drives the status-bar item through the warm-up state machine. The
 * "ready" state holds for a few seconds so the user sees the transition
 * from "warming" before the indicator fades; "fallback" holds longer so
 * the user can see why their file was rendered via Gradle. Hidden any
 * time no module is in flight.
 */
function updateDaemonStatus(module: string, state: WarmState): void {
    if (!daemonStatusItem) { return; }
    if (daemonStatusClearTimer) {
        clearTimeout(daemonStatusClearTimer);
        daemonStatusClearTimer = null;
    }
    switch (state) {
        case 'bootstrapping':
            daemonStatusItem.text = `$(loading~spin) Daemon: bootstrapping ${module}…`;
            // Cold-build context: composePreviewDaemonStart itself is a
            // small JSON-emit task, but it depends on the consumer's
            // compileKotlin / variant resolution. On a fresh checkout
            // (or after `gradlew clean`, or after a Compose version
            // bump) this can take minutes while Gradle builds the
            // renderer's classpath. Subsequent runs are cacheable and
            // collapse to ~1 s on a warm Gradle daemon.
            daemonStatusItem.tooltip = 'Running composePreviewDaemonStart. '
                + 'On a cold build (fresh checkout, after clean, or after a '
                + 'Compose version bump) this may take a few minutes while '
                + 'Gradle compiles the renderer classpath. Cacheable on '
                + 'subsequent runs.';
            daemonStatusItem.show();
            break;
        case 'spawning':
            daemonStatusItem.text = `$(loading~spin) Daemon: spawning ${module}…`;
            daemonStatusItem.tooltip = 'Launching the preview daemon JVM and running initialize';
            daemonStatusItem.show();
            break;
        case 'ready':
            daemonStatusItem.text = `$(check) Daemon: ${module}`;
            daemonStatusItem.tooltip = 'Preview daemon is up and serving renders';
            daemonStatusItem.show();
            daemonStatusClearTimer = setTimeout(() => daemonStatusItem?.hide(), 4000);
            break;
        case 'fallback':
            daemonStatusItem.text = `$(warning) Daemon: ${module} (using Gradle)`;
            daemonStatusItem.tooltip = 'Daemon spawn failed — using the Gradle render path. See Output → Compose Preview.';
            daemonStatusItem.show();
            daemonStatusClearTimer = setTimeout(() => daemonStatusItem?.hide(), 8000);
            break;
    }
}

/** Per-extension-session memo so bootstrap runs once per module. */
const daemonBootstrappedModules = new Set<string>();

/**
 * Read Error-severity diagnostics for `filePath` from whichever language
 * server is active and adapt them to the vscode-free shape that
 * [extractCompileErrors] consumes. Returns an empty array when no LSP is
 * attached (`vscode.languages.getDiagnostics` returns `[]`), so workspaces
 * without a Kotlin LSP fall through to the existing Gradle-only path with
 * no visible difference.
 *
 * Cross-file errors (e.g. `Theme.kt` broken, `Previews.kt` clean but uses
 * it) are NOT detected here — that's a deliberate trade-off to keep the
 * gate cheap (one URI lookup, no import graph walk). Gradle still catches
 * them on the slow path.
 */
function readCompileErrors(filePath: string): CompileError[] {
    const uri = vscode.Uri.file(filePath);
    const diagnostics = vscode.languages.getDiagnostics(uri);
    // vscode.Diagnostic structurally matches DiagnosticLike — the field
    // names and severity numeric values are the same. Cast through unknown
    // to avoid pulling vscode types into the pure module.
    return extractCompileErrors(filePath, diagnostics as unknown as readonly DiagnosticLike[]);
}

/** Read calibrated phase durations for `module` from workspace state. Empty
 *  on first run; the tracker falls back to its built-in phase defaults. */
function readCalibration(module: string): PhaseDurations {
    if (!extensionContext) { return {}; }
    const all = extensionContext.workspaceState.get<Record<string, PhaseDurations>>(
        PROGRESS_CALIBRATION_KEY, {});
    return all[module] ?? {};
}

/** Persist updated calibration for `module`, blending into prior samples via
 *  EMA so a single anomalous run doesn't dominate. */
function writeCalibration(module: string, latest: PhaseDurations): void {
    if (!extensionContext) { return; }
    const all = extensionContext.workspaceState.get<Record<string, PhaseDurations>>(
        PROGRESS_CALIBRATION_KEY, {});
    all[module] = mergeCalibration(all[module] ?? {}, latest);
    void extensionContext.workspaceState.update(PROGRESS_CALIBRATION_KEY, all);
}

/**
 * Coalesce save-driven refreshes. The next refresh fires when BOTH:
 *   1. `DEBOUNCE_MS` has elapsed since the last save (absorbs bursts), and
 *   2. any in-flight refresh has finished (never stacks builds).
 * Whichever takes longer wins — effectively `max(1.5s, in-flight completion)`.
 * Rapid saves collapse into a single final refresh scoped to the latest file.
 */
function enqueueSaveRefresh(filePath: string): void {
    // Prefer the saved file path, but fall back to the active editor when the
    // saved file isn't a Kotlin source (e.g. a resource XML changed).
    const target = filePath.endsWith('.kt')
        ? filePath
        : (vscode.window.activeTextEditor?.document.languageId === 'kotlin'
            ? vscode.window.activeTextEditor.document.uri.fsPath
            : filePath);
    pendingSavePath = target;
    invalidateModuleCache(target);

    const delay = target === currentScopeFile ? SCOPE_DEBOUNCE_MS : DEBOUNCE_MS;
    if (debounceTimer) { clearTimeout(debounceTimer); }
    debounceElapsed = false;
    debounceTimer = setTimeout(() => {
        debounceTimer = null;
        debounceElapsed = true;
        maybeFirePendingRefresh();
    }, delay);
}

/** Fires the pending refresh only when the debounce window has elapsed AND
 *  no other refresh is running. Called from the debounce timer and from the
 *  tail of {@link runRefreshExclusive}. */
function maybeFirePendingRefresh(): void {
    if (refreshInFlight || !debounceElapsed || pendingSavePath === null) { return; }
    const target = pendingSavePath;
    pendingSavePath = null;
    void runRefreshExclusive(target);
}

/** Runs {@link refresh} with the `refreshInFlight` gate so the debounce queue
 *  can tell whether to defer. On completion picks up anything that arrived
 *  during the run, re-applying the debounce-elapsed check.
 *
 *  Picks daemon-vs-Gradle deliberately — never runs both for the same save.
 *  When the daemon is healthy for the file's module, the save uses the
 *  daemon's hot sandbox (sub-second updateImage via `renderFinished`); the
 *  refresh itself is `forceRender=false` so Gradle does a cheap cached
 *  discovery for the panel manifest only. When the daemon is disabled or
 *  unhealthy, the refresh is `forceRender=true` and Gradle does a full
 *  render as today.
 *
 *  **Recompile-before-notify invariant.** In the daemon path the discover
 *  refresh runs *first*, then the daemon is notified. The daemon's
 *  `fileChanged({kind:source})` swaps its `URLClassLoader` and `renderNow`
 *  reads `.class` files from `build/intermediates/.../classes/` — so those
 *  files must be fresh when the swap happens. `compileKotlin` runs as an
 *  upstream of `discoverPreviews` (we let Gradle drive Kotlin compilation
 *  per `docs/daemon/DESIGN.md` § "Out of scope"); inverting the order would
 *  let the daemon render stale bytecode matching the previous save and the
 *  user would see the loading overlay flash without any actual content
 *  change.
 *
 *  Save-driven: always `tier='fast'`. Heavy captures (LONG / GIF / animated)
 *  keep their previous PNG/GIF on disk and surface as stale in the panel —
 *  the user re-renders them on demand via the refresh command, which uses
 *  `tier='full'`. Keeps every save in the cheap interactive loop. */
async function runRefreshExclusive(filePath: string): Promise<void> {
    refreshInFlight = true;
    try {
        const mode = pickRefreshMode(filePath);
        if (mode === 'daemon') {
            // Run discover first so `compileKotlin` (an upstream of
            // discoverPreviews) updates the on-disk `.class` files the
            // daemon's child URLClassLoader reads. Notifying the daemon
            // before the recompile would race the swap against stale
            // bytecode and the panel would flicker without updating.
            const outcome = await refresh(false, filePath);
            if (outcome !== 'completed') {
                // Discover didn't finish to completion. Three cases, all
                // equally bad for daemon notify:
                //   - 'cancelled': a newer save aborted us mid-flight, so
                //     compileKotlin was killed too — `.class` files are
                //     half-written. The newer save's runRefreshExclusive
                //     will own the daemon notify.
                //   - 'failed': Gradle failed (build error, missing jlink).
                //     `.class` files weren't refreshed; stale bytecode.
                //   - 'no-module': nothing happened.
                // In all three, sending fileChanged + renderNow now would
                // re-render against stale bytes and produce the
                // flicker-without-update symptom — exactly the bug this
                // ordering fix was meant to close.
                logLine(`daemon: skipped notify (refresh outcome=${outcome})`);
                return;
            }
            const accepted = await notifyDaemonOfSave(filePath);
            if (accepted) {
                return;
            }
            // Daemon was healthy at decision time but the actual call
            // failed — race with channel close, sandbox died mid-save,
            // gate unexpectedly returned null. Deliberate switch: run the
            // Gradle render so the user still sees fresh previews.
            logLine('daemon: rejected the work — falling back to Gradle render');
        }
        await refresh(true, filePath, 'fast');
    } finally {
        refreshInFlight = false;
        maybeFirePendingRefresh();
    }
}

/**
 * Decides which path handles a save. See `refreshMode.ts` for the pure
 * predicate; this is the production wrapper that reads the live
 * module-level state from the gate / gradle service.
 */
function pickRefreshMode(filePath: string): RefreshMode {
    if (!daemonGate || !gradleService) { return 'gradle'; }
    return pickRefreshModeFor(
        filePath,
        daemonGate.isEnabled(),
        gradleService.resolveModule(filePath),
        (m) => daemonGate!.isDaemonReady(m),
    );
}

function sendModuleList() {
    if (!gradleService || !panel) { return; }
    const modules = gradleService.findPreviewModules();
    panel.postMessage({ command: 'setModules', modules, selected: selectedModule || '' });
}

/**
 * Modules where the most recent render was `tier='fast'` — heavy captures
 * (LONG / GIF / animated) are stale on disk relative to the user's source.
 * The webview reads this to decorate heavy cards with a "stale, click to
 * refresh" badge. Cleared per module on a successful `tier='full'` render.
 */
const fastTierModules = new Set<string>();

/**
 * Main refresh entry point.
 * @param forceRender  If true, runs renderAllPreviews (not just discover).
 * @param forFilePath  If set, scopes to the module owning this file.
 * @param tier         Which render tier to request when `forceRender` is true.
 *                     Defaults to `'full'` so explicit user-triggered refreshes
 *                     produce up-to-date heavy captures; the save-driven path
 *                     overrides to `'fast'` (see {@link runRefreshExclusive}).
 *                     Ignored when `forceRender` is false (discover-only).
 */
/**
 * Outcome of a {@link refresh} call. Callers (notably {@link runRefreshExclusive})
 * use this to decide whether to take follow-up steps that depend on the
 * Gradle work having actually run to completion — e.g. notifying the daemon
 * after a discover, which only makes sense when `compileKotlin` (an
 * upstream of `discoverPreviews`) finished and the on-disk `.class` files
 * are fresh.
 *
 * - `'completed'` — the requested Gradle task ran end-to-end and the panel
 *   is up to date.
 * - `'cancelled'` — a newer refresh aborted this one (or Gradle reported
 *   `CANCELLED`). Whoever superseded us owns the panel state from here;
 *   no follow-up should run.
 * - `'no-module'` — no preview module resolved for the requested file
 *   (file outside the workspace, build script, etc.). Panel was reset to
 *   the empty state; no Gradle work happened.
 * - `'failed'` — Gradle threw a non-cancellation error (build failure,
 *   missing `jlink`, etc.). The panel surfaces the error; no follow-up.
 * - `'gated'` — LSP reported Error-severity diagnostics for the active
 *   file, so the build was skipped. Panel shows the compile-error banner
 *   over the previous (now stale) cards; the next save with a clean buffer
 *   will run a real refresh.
 */
type RefreshOutcome = 'completed' | 'cancelled' | 'no-module' | 'failed' | 'gated';

async function refresh(
    forceRender: boolean,
    forFilePath?: string,
    tier: 'fast' | 'full' = 'full',
): Promise<RefreshOutcome> {
    if (!gradleService || !panel) { return 'no-module'; }

    // Cancel any in-flight refresh
    pendingRefresh?.abort();
    const abort = new AbortController();
    pendingRefresh = abort;

    // The panel is always scoped to exactly one Kotlin source file. If no
    // suitable file is available (webview has focus → activeTextEditor is
    // undefined, build script, Log/Output pane has focus) the panel shows the
    // empty state rather than falling through to an ambiguous multi-file view.
    // Picks, in priority: caller > active editor > any visible Kotlin editor >
    // last-scoped file. See resolveScopeFile for the full chain.
    const { file: activeFile, source: scopeSource } = resolveScopeFile(forFilePath);
    const module = activeFile && isPreviewSourceFile(activeFile)
        ? gradleService.resolveModule(activeFile)
        : null;
    if (!activeFile || !module) {
        logLine(`no module — activeFile=${activeFile ?? '<none>'} (${scopeSource})`);
        panel.postMessage({ command: 'clearAll' });
        panel.postMessage({ command: 'showMessage', text: emptyStateMessage(activeFile) });
        lastLoadedModules = [];
        hasPreviewsLoaded = false;
        currentScopeFile = null;
        historyScopeRef.current = null;
        historyPanel?.setScope(null);
        if (activeFile && isPreviewSourceFile(activeFile)) {
            maybeShowSetupPrompt(activeFile);
        }
        return 'no-module';
    }
    logLine(`start forceRender=${forceRender} file=${path.basename(activeFile)} (${scopeSource}) module=${module}`);

    // LSP gate. Saves a 5–30 s round-trip through compileKotlin when the
    // user is mid-typo-fix — the active file's diagnostics already tell us
    // the build will fail, so surface those instead of starting Gradle.
    // Cards from the previous successful render stay visible (just dimmed)
    // so the user keeps a reference while they fix the error.
    //
    // Gate fires before currentScopeFile assignment so a later refresh from
    // a different file can still drop the banner via clearCompileErrors.
    const compileErrors = readCompileErrors(activeFile);
    if (compileErrors.length > 0) {
        panel.postMessage({
            command: 'setCompileErrors',
            errors: compileErrors,
            sourceFile: activeFile,
        });
        // No build is starting — make sure no stale progress bar lingers
        // from a prior in-flight refresh that was just cancelled by the
        // pendingRefresh.abort() at the top of this function.
        panel.postMessage({ command: 'clearProgress' });
        currentScopeFile = activeFile;
        logLine(`gated — ${compileErrors.length} compile error(s) in ${path.basename(activeFile)}`);
        return 'gated';
    }
    // Errors cleared since the previous gate fire — drop the banner before
    // we begin the actual build so the user sees the panel return to its
    // normal "working" state.
    panel.postMessage({ command: 'clearCompileErrors' });

    currentScopeFile = activeFile;
    // Phase H7 — re-scope the History panel alongside the live panel.
    // `projectDir` is the consumer module's absolute path; we synthesize
    // it from workspaceRoot + module here because GradleService keeps
    // modules as relative slash-paths.
    const projectDir = path.join(gradleService.workspaceRoot, module);
    // Preserve the previewId narrow across same-module refreshes so a
    // save-driven refresh doesn't briefly widen the History panel to the
    // module before the webview re-publishes the narrow on next layout.
    // On a module switch the narrow no longer applies — the previewId is
    // owned by the previous module's preview set.
    const prior = historyScopeRef.current;
    const sameModule = prior?.moduleId === module && prior.projectDir === projectDir;
    const newScope: HistoryScope = sameModule
        ? { moduleId: module, projectDir, previewId: prior!.previewId, previewLabel: prior!.previewLabel }
        : { moduleId: module, projectDir };
    historyScopeRef.current = newScope;
    historyPanel?.setScope(newScope);
    const modules = [module];
    // Package-qualified path (e.g. `com/example/samplewear/Previews.kt`) so
    // files with the same basename in different packages don't collide.
    // Must match what DiscoverPreviewsTask emits into manifest.sourceFile.
    const filterFile = packageQualifiedSourcePath(activeFile);

    // When the module scope changes (user switched files to a different
    // module, or went from "all modules" to a single one) the old cards are
    // from a different context and should be discarded up front rather than
    // left visible until the diff in setPreviews prunes them — which won't
    // happen if the new refresh cancels before setPreviews.
    const scopeChanged = !sameScope(modules, lastLoadedModules);
    if (scopeChanged) {
        panel.postMessage({ command: 'clearAll' });
        hasPreviewsLoaded = false;
    }

    // If we already have previews on screen, use a stealth refresh:
    // keep the current cards visible and show per-card spinners rather than
    // clearing the view. Only show a full "Building..." message on first load.
    if (hasPreviewsLoaded) {
        panel.postMessage({ command: 'markAllLoading' });
    } else {
        panel.postMessage({ command: 'setLoading' });
    }
    lastLoadedModules = modules;
    gradleService.cancel();

    // Forward progress-bar updates to the webview. Single tracker per refresh
    // — same instance feeds the Gradle phase signals (compile/discover/render)
    // and the post-task `loading` phase the extension drives itself once
    // Gradle returns. The webview hides the bar on its own once percent=1.
    const onProgress = (state: ProgressState) => {
        if (abort.signal.aborted) { return; }
        panel?.postMessage({
            command: 'setProgress',
            phase: state.phase,
            label: state.label,
            percent: state.percent,
            slow: state.slow,
        });
    };
    const taskOpts = {
        progressCalibration: readCalibration(module),
        onProgress,
        onCalibration: (durations: PhaseDurations) => writeCalibration(module, durations),
    };

    try {
        const allPreviews: PreviewInfo[] = [];
        previewModuleMap.clear();

        for (const mod of modules) {
            if (abort.signal.aborted) { return 'cancelled'; }

            const manifest = forceRender
                ? await gradleService.renderPreviews(mod, tier, taskOpts)
                : await gradleService.discoverPreviews(mod, taskOpts);

            // Track tier so the webview can mark heavy cards as stale after a
            // fast save. A successful full render clears the flag for this
            // module (heavy captures are now fresh on disk).
            if (forceRender && manifest) {
                if (tier === 'fast') {
                    fastTierModules.add(mod);
                } else {
                    fastTierModules.delete(mod);
                }
            }

            const perModule: PreviewInfo[] = [];
            if (manifest) {
                for (const p of manifest.previews) {
                    // Pre-compute carousel labels once so the webview doesn't
                    // have to know how to render dimension summaries.
                    for (const capture of p.captures) {
                        capture.label = captureLabel(capture);
                    }
                    allPreviews.push(p);
                    previewModuleMap.set(p.id, mod);
                    perModule.push(p);
                }
            }
            registry.replaceModule(mod, perModule);
            // Mirror per-module previews for the daemon scheduler — the
            // save side-channel uses this snapshot to translate "active
            // file" into a list of preview IDs without an extension-side
            // discovery round-trip. Cleared when the module's render
            // returns no manifest (preview-set went empty).
            if (manifest) {
                moduleManifestCache.set(mod, perModule);
            } else {
                moduleManifestCache.delete(mod);
            }
        }

        if (abort.signal.aborted) { return 'cancelled'; }

        if (allPreviews.length === 0) {
            // Module has no previews at all. Wipe any stale cards so the
            // message isn't overlaid on old content from a prior scope.
            panel.postMessage({ command: 'clearAll' });
            panel.postMessage({ command: 'showMessage', text: 'No @Preview functions found in this module' });
            panel.postMessage({ command: 'clearProgress' });
            hasPreviewsLoaded = false;
            logLine('done — module has no previews');
            return 'completed';
        }

        // Scope strictly to the active file. If the file has no @Preview
        // functions, the panel shows an empty state — never the whole module.
        const visiblePreviews = allPreviews.filter(p => p.sourceFile === filterFile);

        if (visiblePreviews.length === 0) {
            // The module has previews but this file doesn't. An empty
            // setPreviews would get silently wiped by applyFilters in the
            // webview — send an explicit, persistent message instead and
            // clear the grid so the user sees *why* it's blank.
            panel.postMessage({ command: 'clearAll' });
            const otherFiles = allPreviews.length;
            panel.postMessage({
                command: 'showMessage',
                text: `No @Preview functions in this file (${otherFiles} in other files in this module).`,
            });
            panel.postMessage({ command: 'clearProgress' });
            hasPreviewsLoaded = false;
            logLine(`done — 0 visible previews in ${path.basename(activeFile)}, module has ${otherFiles}`);
            return 'completed';
        }

        // A preview is "heavyStale" when this module's most recent render was
        // tier=fast AND the preview has at least one heavy capture. The
        // webview decorates these cards with a stale badge so the user knows
        // the GIF/long-scroll image is from a previous full render.
        const moduleIsFastTier = modules.some(mod => fastTierModules.has(mod));
        const heavyStaleIds = moduleIsFastTier
            ? visiblePreviews
                .filter(p => p.captures.some(c => (c.cost ?? 1) > HEAVY_COST_THRESHOLD))
                .map(p => p.id)
            : [];

        panel.postMessage({
            command: 'setPreviews',
            previews: visiblePreviews,
            moduleDir: modules.join(','),
            heavyStaleIds,
        });
        hasPreviewsLoaded = true;
        logLine(`rendered ${visiblePreviews.length} preview(s) for ${path.basename(activeFile)}`);

        // Gradle is done; the rest of the work (reading PNGs, base64-encoding,
        // pushing to webview) is extension-side. Push the bar into a "Loading
        // images" phase with a known weight so the user sees the bar continue
        // to advance instead of sitting stuck at the end of `rendering` while
        // images stream in.
        if (!abort.signal.aborted) {
            onProgress({ phase: 'loading', label: 'Loading images', percent: 0.92, slow: false });
        }

        // Load images in parallel. Animated previews have multiple captures
        // in `preview.captures`; one updateImage message per capture. The
        // registry (used for CodeLens / hover) keeps only the representative
        // (first) capture's PNG.
        //
        // Sequential awaits here used to be the long pole on a 16-preview
        // module with @AnimatedPreview GIFs — each capture is a 1-5MB read +
        // base64 encode. Streaming them concurrently lets each card paint
        // as soon as its bytes arrive rather than waiting for an arbitrary
        // serial position.
        const imageJobs: Promise<void>[] = [];
        for (const preview of visiblePreviews) {
            const captures = preview.captures;
            if (captures.length === 0) { continue; }

            const mod = previewModuleMap.get(preview.id);
            if (!mod) { continue; }

            for (let captureIndex = 0; captureIndex < captures.length; captureIndex++) {
                const capture = captures[captureIndex];
                if (!capture.renderOutput) { continue; }
                const idx = captureIndex;
                imageJobs.push((async () => {
                    if (abort.signal.aborted) { return; }
                    const imageData = await gradleService!.readPreviewImage(mod, capture.renderOutput);
                    if (abort.signal.aborted || !panel) { return; }

                    if (imageData) {
                        if (idx === 0) {
                            registry.setImage(preview.id, imageData);
                        }
                        panel.postMessage({
                            command: 'updateImage',
                            previewId: preview.id,
                            captureIndex: idx,
                            imageData,
                        });
                    } else if (forceRender) {
                        // Render task completed but produced no PNG for this
                        // capture — a per-capture failure that didn't fail the
                        // whole task. Surface it on the card; root-cause log is
                        // in Output ▸ Compose Preview.
                        panel.postMessage({
                            command: 'setImageError',
                            previewId: preview.id,
                            captureIndex: idx,
                            message: 'Render failed — see Output ▸ Compose Preview',
                        });
                    }
                    // else: discover-only pass, PNG not produced yet. Leave
                    // the skeleton in place; the next save-triggered render
                    // will populate it.
                })());
            }
        }
        await Promise.all(imageJobs);
        if (!abort.signal.aborted) {
            onProgress({ phase: 'done', label: 'Done', percent: 1, slow: false });
        }

        // NOTE: intentionally do NOT send `showMessage: ''` here. The webview's
        // renderPreviews() clears the 'loading' Building… banner the moment
        // cards arrive, so the happy path is already covered. Posting a blank
        // showMessage would also clobber legitimate extension-set messages
        // (filter empty notice, build errors), which was the original
        // "populate then go blank" regression.
        //
        // Stale-banner posting / clearing. Compare the active source file's
        // mtime against the oldest visible PNG: if the source has been edited
        // since the renders were written, the user is looking at a render
        // that doesn't reflect their current source. An absolute "older than
        // X" threshold is wrong because a steady-state save loop produces
        // PNGs seconds old that are nonetheless current — what matters is
        // "did the source move since".
        //
        //   - forceRender=true → user explicitly re-rendered, anything stale
        //     was just rewritten; clear any prior banner.
        //   - forceRender=false → check source vs PNG; banner if source is
        //     newer. Cleared on the first daemon `updateImage` (see
        //     daemonScheduler events) or on the next forceRender.
        if (forceRender && staleBannerShown) {
            panel.postMessage({ command: 'showMessage', text: '' });
            staleBannerShown = false;
        } else if (!forceRender) {
            const [sourceMtime, oldestMtime] = await Promise.all([
                sourceFileMtime(activeFile),
                oldestRenderMtime(visiblePreviews, modules),
            ]);
            const sourceIsNewer =
                sourceMtime != null && oldestMtime != null && sourceMtime > oldestMtime;
            if (sourceIsNewer) {
                const ago = formatRelativeAge(Date.now() - oldestMtime!);
                panel.postMessage({
                    command: 'showMessage',
                    text:
                        `Showing previews from ${ago}, but ${path.basename(activeFile)} ` +
                        'has changed since (save to render fresh, or run "Compose Preview: Refresh").',
                });
                staleBannerShown = true;
            } else if (staleBannerShown) {
                panel.postMessage({ command: 'showMessage', text: '' });
                staleBannerShown = false;
            }
        }
        return abort.signal.aborted ? 'cancelled' : 'completed';
    } catch (err: unknown) {
        if (abort.signal.aborted) { return 'cancelled'; }
        // Cancellation = a follow-up refresh superseded this one. The new
        // refresh owns the panel state from here; surfacing a "FAILED" toast
        // would be misleading and noisy.
        if (err instanceof TaskCancelledError) {
            logLine(`cancelled — superseded by a newer refresh`);
            panel.postMessage({ command: 'clearProgress' });
            return 'cancelled';
        }
        if (err instanceof JdkImageError) {
            logLine(`FAILED (jlink missing): ${err.finding.jlinkPath}`);
            panel.postMessage({
                command: 'showMessage',
                text: 'Gradle is running on a JRE without jlink. Configure a full JDK to render previews.',
            });
            panel.postMessage({ command: 'clearProgress' });
            showJdkImageRemediation(err);
            return 'failed';
        }
        const message = err instanceof Error ? err.message.slice(0, 300) : 'Build failed';
        logLine(`FAILED: ${message}`);
        panel.postMessage({
            command: 'showMessage',
            text: message,
        });
        panel.postMessage({ command: 'clearProgress' });
        return 'failed';
    } finally {
        if (pendingRefresh === abort) { pendingRefresh = null; }
    }
}

function handleWebviewMessage(msg: WebviewToExtensionMessage) {
    switch (msg.command) {
        case 'openFile':
            if (msg.className && msg.functionName) {
                openPreviewSource(msg.className, msg.functionName);
            }
            break;
        case 'selectModule':
            selectedModule = msg.value || null;
            sendModuleList();
            if (selectedModule) { refresh(false); }
            break;
        case 'refreshHeavy':
            // Click on the stale badge → full-tier render of the owning
            // module. Once we have a `-PcomposePreview.previewIds=` filter
            // we can scope this to just the requested capture; for now the
            // user pays a full-module render for the freshness guarantee.
            if (msg.previewId) {
                const mod = previewModuleMap.get(msg.previewId);
                if (mod) {
                    void refresh(true, currentScopeFile ?? undefined, 'full');
                }
            }
            break;
        case 'viewportUpdated':
            // Daemon-only: route geometric visibility + scroll-ahead
            // predictions to the scheduler so it can `setVisible` and
            // queue speculative renders. When the daemon is disabled
            // (default) `notifyDaemonViewport` is a no-op.
            if (Array.isArray(msg.visible) && Array.isArray(msg.predicted)) {
                void notifyDaemonViewport(msg.visible, msg.predicted);
            }
            break;
        case 'previewScopeChanged': {
            // Live panel has narrowed to a single preview (focus mode, or
            // filters reduced visible cards to one). Re-scope the History
            // panel's previewId filter so it only lists entries for that
            // preview. `previewId` null means widen the scope back to the
            // module — the panel shows every preview's history.
            if (!historyScopeRef.current) { break; }
            const requested = msg.previewId ?? undefined;
            const requestedLabel = requested ? lookupPreviewLabel(requested) : undefined;
            if (historyScopeRef.current.previewId === requested
                && historyScopeRef.current.previewLabel === requestedLabel) {
                break;
            }
            const newScope: HistoryScope = {
                ...historyScopeRef.current,
                previewId: requested,
                previewLabel: requestedLabel,
            };
            historyScopeRef.current = newScope;
            historyPanel?.setScope(newScope);
            break;
        }
        case 'openCompileError':
            if (msg.sourceFile && typeof msg.line === 'number'
                && typeof msg.column === 'number') {
                void openSourcePosition(msg.sourceFile, msg.line, msg.column);
            }
            break;
    }
}

/**
 * Open `filePath` and reveal the given (1-based) position. Used by the
 * compile-error banner — clicking a row jumps to the offending location.
 * 1-based input here matches what we render in the banner; vscode's API
 * is 0-based, so we subtract before constructing the Position.
 */
async function openSourcePosition(filePath: string, line: number, column: number): Promise<void> {
    try {
        const doc = await vscode.workspace.openTextDocument(filePath);
        const editor = await vscode.window.showTextDocument(doc);
        const pos = new vscode.Position(Math.max(0, line - 1), Math.max(0, column - 1));
        editor.selection = new vscode.Selection(pos, pos);
        editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
    } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        logLine(`openSourcePosition failed for ${filePath}:${line}:${column} — ${message}`);
    }
}

function lookupPreviewLabel(previewId: string): string | undefined {
    const mod = previewModuleMap.get(previewId);
    if (!mod) { return undefined; }
    const manifest = moduleManifestCache.get(mod);
    const info = manifest?.find(p => p.id === previewId);
    if (!info) { return undefined; }
    return info.params.name
        ? `${info.functionName} — ${info.params.name}`
        : info.functionName;
}

async function notifyDaemonViewport(visible: string[], predicted: string[]): Promise<void> {
    if (!daemonGate?.isEnabled() || !daemonScheduler) { return; }
    // Group by owning module — viewports cross module boundaries only when
    // the user is paging across the all-modules view (rare today; the
    // panel is module-scoped). Each module's daemon gets its own slice.
    const visibleByModule = new Map<string, string[]>();
    const predictedByModule = new Map<string, string[]>();
    for (const id of visible) {
        const mod = previewModuleMap.get(id);
        if (!mod) { continue; }
        if (!visibleByModule.has(mod)) { visibleByModule.set(mod, []); }
        visibleByModule.get(mod)!.push(id);
    }
    for (const id of predicted) {
        const mod = previewModuleMap.get(id);
        if (!mod) { continue; }
        if (!predictedByModule.has(mod)) { predictedByModule.set(mod, []); }
        predictedByModule.get(mod)!.push(id);
    }
    const modules = new Set([...visibleByModule.keys(), ...predictedByModule.keys()]);
    for (const mod of modules) {
        await daemonScheduler.setVisible(
            mod,
            visibleByModule.get(mod) ?? [],
            predictedByModule.get(mod) ?? [],
        );
    }
}

// Loose type for incoming webview messages (validated per-case above).
// Field union accommodates every case in handleWebviewMessage so no cast
// gymnastics are needed at the use site.
interface WebviewToExtensionMessage {
    command: string;
    className?: string;
    functionName?: string;
    value?: string;
    previewId?: string | null;
    visible?: string[];
    predicted?: string[];
    sourceFile?: string;
    line?: number;
    column?: number;
}

/**
 * Picks the right empty-state message for the webview based on why the panel
 * couldn't resolve a preview-enabled module for the active file:
 *
 *   - No Kotlin file active / build script in focus: generic hint.
 *   - Plugin isn't applied in any module in the workspace: call that out
 *     explicitly. Users who've just installed the extension hit this one and
 *     the generic "open a Kotlin file" message is misleading there.
 *   - Plugin is applied somewhere, but not in this file's module, AND the
 *     file contains `@Preview` annotations: the user almost certainly wants
 *     previews here — nudge them toward adding the plugin to this module.
 *   - Plugin is applied somewhere, but this file has no `@Preview` usage:
 *     stay quiet. The file probably just isn't a preview file (false alarm
 *     for case C).
 */
function emptyStateMessage(activeFile: string | undefined): string {
    if (!gradleService) { return ''; }
    if (!activeFile || !isPreviewSourceFile(activeFile)) {
        return 'Open a Kotlin source file in a module that applies ee.schimke.composeai.preview.';
    }
    const previewModules = gradleService.findPreviewModules();
    if (previewModules.length === 0) {
        return 'The Compose Preview Gradle plugin isn\'t applied in this workspace. '
            + 'Add id("ee.schimke.composeai.preview") to a module\'s build.gradle.kts to enable previews.';
    }
    if (fileHasPreviewAnnotation(activeFile)) {
        // File is inside a preview-enabled module, but outside this Gradle
        // project — typical for a file in a git worktree opened from the main
        // checkout's workspace. Nothing the user needs to "fix" in the build
        // script; steer them toward opening the right root instead.
        if (findPluginAppliedAncestor(activeFile)) {
            return 'This file is in a preview-enabled module, but outside this VS Code '
                + 'workspace root (e.g. a git worktree). Open that project root in VS Code '
                + 'to see its previews.';
        }
        const topDir = topLevelDirOf(activeFile) ?? '(this module)';
        return `'${topDir}' doesn't apply ee.schimke.composeai.preview. `
            + `Modules with previews in this workspace: ${previewModules.join(', ')}.`;
    }
    // No @Preview references in the active file — stay out of the way.
    return 'No @Preview functions in this file.';
}

/**
 * Show a one-shot VS Code notification with remediation actions for the
 * "plugin not applied" cases. De-duped per session and dismissable per
 * workspace. Skips the nudge entirely when the file has no `@Preview` usage
 * and the workspace already has other preview-enabled modules (case C'),
 * because that's almost certainly a false alarm.
 */
function maybeShowSetupPrompt(activeFile: string): void {
    if (warnedMissingPluginThisSession || !gradleService || !extensionContext) { return; }
    if (extensionContext.workspaceState.get<boolean>(DISMISS_KEY)) { return; }

    const previewModules = gradleService.findPreviewModules();
    const missingAnywhere = previewModules.length === 0;
    const missingForThisModule = !missingAnywhere && fileHasPreviewAnnotation(activeFile);
    if (!missingAnywhere && !missingForThisModule) { return; }
    // The file is already inside a plugin-applied module somewhere up its own
    // path — it just isn't part of *this* Gradle project (e.g. a git worktree
    // nested under the workspace root). No build-script fix is needed; skip
    // the nudge rather than point at a module they'd have to invent.
    if (findPluginAppliedAncestor(activeFile)) { return; }

    warnedMissingPluginThisSession = true;
    const message = missingAnywhere
        ? 'Compose Preview: the Gradle plugin isn\'t applied in this workspace yet.'
        : 'Compose Preview: this module doesn\'t apply the Gradle plugin, but the file uses @Preview.';
    const OPEN = 'Open build.gradle.kts';
    const DOCS = 'View setup docs';
    const NEVER = 'Don\'t show again';
    void vscode.window.showInformationMessage(message, OPEN, DOCS, NEVER).then(action => {
        if (action === OPEN) {
            void vscode.commands.executeCommand('composePreview.openModuleBuildFile', activeFile);
        } else if (action === DOCS) {
            void vscode.env.openExternal(vscode.Uri.parse(SETUP_DOCS_URL));
        } else if (action === NEVER) {
            void extensionContext?.workspaceState.update(DISMISS_KEY, true);
        }
    });
}

/**
 * Opens the nearest ancestor `build.gradle.kts` of the given file — the
 * likely target for adding `id("ee.schimke.composeai.preview")`. Walks up
 * from the file's directory; if nothing is found before the workspace root,
 * falls back to the root's own build script. This handles both top-level
 * modules (the only kind findPreviewModules scans for) and nested layouts.
 */
async function openModuleBuildFile(workspaceRoot: string, filePath?: string): Promise<void> {
    const target = filePath
        ?? currentScopeFile
        ?? vscode.window.activeTextEditor?.document.uri.fsPath
        ?? workspaceRoot;

    let dir = target === workspaceRoot ? workspaceRoot : path.dirname(target);
    const root = path.resolve(workspaceRoot);
    while (path.resolve(dir).startsWith(root)) {
        const candidate = path.join(dir, 'build.gradle.kts');
        if (fs.existsSync(candidate)) {
            const doc = await vscode.workspace.openTextDocument(candidate);
            await vscode.window.showTextDocument(doc);
            return;
        }
        const parent = path.dirname(dir);
        if (parent === dir) { break; }
        dir = parent;
    }
    vscode.window.showWarningMessage('No build.gradle.kts found for this file.');
}

function fileHasPreviewAnnotation(filePath: string): boolean {
    // Prefer the already-loaded editor buffer over a disk read so unsaved
    // edits (the user just typed `@Preview`) are picked up.
    const doc = vscode.workspace.textDocuments.find(d => d.uri.fsPath === filePath);
    const text = doc
        ? doc.getText()
        : (() => { try { return fs.readFileSync(filePath, 'utf-8'); } catch { return ''; } })();
    return text.includes('Preview') && text.includes('@Composable');
}

function topLevelDirOf(filePath: string): string | null {
    const folders = vscode.workspace.workspaceFolders;
    if (!folders || folders.length === 0) { return null; }
    const rel = path.relative(folders[0].uri.fsPath, filePath);
    const first = rel.split(path.sep)[0];
    return first && first !== '..' ? first : null;
}

async function openPreviewSource(className: string, functionName: string) {
    const classFile = className.replace(/Kt$/, '').replace(/\./g, '/') + '.kt';
    const files = await vscode.workspace.findFiles(`**/${classFile}`, '**/build/**', 1);
    if (files.length === 0) {
        vscode.window.showWarningMessage(`Could not find source for ${className}.${functionName}`);
        return;
    }

    const doc = await vscode.workspace.openTextDocument(files[0]);
    const editor = await vscode.window.showTextDocument(doc);

    const text = doc.getText();
    const escaped = functionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = new RegExp(`fun\\s+${escaped}\\s*\\(`).exec(text);
    if (match) {
        const pos = doc.positionAt(match.index);
        editor.selection = new vscode.Selection(pos, pos);
        editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
    }
}
