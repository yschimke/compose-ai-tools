import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as crypto from 'crypto';
import { GradleService, GradleApi, TaskCancelledError } from './gradleService';
import { JdkImageError } from './jdkImageErrorDetector';
import { KotlinCompileError } from './kotlinCompileErrorDetector';
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
import {
    AccessibilityFinding,
    AccessibilityNode,
    HEAVY_COST_THRESHOLD,
    PreviewInfo,
    WebviewToExtension,
} from './types';
import { formatRenderErrorMessage } from './renderError';
import { captureLabel } from './captureLabels';
import { DaemonGate } from './daemon/daemonGate';
import { DataProductAttachment } from './daemon/daemonProtocol';
import { A11Y_OVERLAY_KINDS, DaemonScheduler, WarmState } from './daemon/daemonScheduler';
import { buildHistorySource, HistoryPanel, HistoryScope, HistorySource } from './historyPanel';
import { disposePreviewMainBatches, readPreviewMainPng } from './previewMainSource';
import { watchPreviewMainRef } from './previewMainWatcher';
import { LogFilter, parseLogLevel } from './logFilter';
import { pickRefreshModeFor, RefreshMode } from './refreshMode';
import { BuildProgressTracker, mergeCalibration, PhaseDurations } from './buildProgress';
import { CompileError, extractCompileErrors, DiagnosticLike } from './compileErrors';
import {
    buildPreviewActivityAmStartArgs,
    collectAndroidApplicationModules,
    findAndroidSdkRoot,
    resolveAdbPath,
    runAdb,
} from './launchOnDevice';

const DEBOUNCE_MS = 1500;
// Edits to the currently-scoped preview file (e.g. Claude Code's Edit tool
// writing to Previews.kt) are nearly always a single discrete event, not a
// burst — cut the wait so the refresh feels responsive. The refreshInFlight
// gate still protects against stacking builds if something happens faster.
const SCOPE_DEBOUNCE_MS = 300;
const INIT_DELAY_MS = 1000;
// LSP republish lag. The Kotlin LSP typically republishes diagnostics
// within 30–80 ms of a save; this is a conservative cap before the gate
// trusts that what it reads is the post-save state. Only paid when the
// initial read showed errors — happy path is unaffected.
const GATE_REREAD_DELAY_MS = 150;
const DAEMON_BOOTSTRAP_PROGRESS_MS = 18_000;
const DAEMON_SPAWN_PROGRESS_MS = 7_000;

let gradleService: GradleService | null = null;
let daemonGate: DaemonGate | null = null;
let daemonScheduler: DaemonScheduler | null = null;
let daemonStatusItem: vscode.StatusBarItem | null = null;
let interactiveStatusItem: vscode.StatusBarItem | null = null;
let daemonStatusClearTimer: NodeJS.Timeout | null = null;
let historyPanel: HistoryPanel | null = null;
/** Owned by activate(); reused by both the History panel and the live
 *  panel's diff handler. */
let historySource: HistorySource | null = null;
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

/** Stat the source file and return its mtime, or null on any error.
 *  Used to detect "user edited since the rendered PNGs were written". */
async function sourceFileMtime(filePath: string): Promise<number | null> {
    try {
        const stat = await fs.promises.stat(filePath);
        return stat.mtimeMs;
    } catch {
        return null;
    }
}

/**
 * True when the active source file's mtime is newer than the oldest visible
 * preview's PNG. Cheap signal that the on-disk renders don't reflect the
 * current buffer — used both to suppress reading those stale bytes into the
 * webview and to schedule an automatic re-render. Conservative: any
 * unreadable file (missing PNG, source-stat failure) yields `false` so we
 * default to "use what's on disk" rather than spuriously trigger renders.
 */
async function isSourceNewerThanRenders(
    activeFile: string,
    previews: PreviewInfo[],
    modules: string[],
): Promise<boolean> {
    const [sourceMtime, oldestMtime] = await Promise.all([
        sourceFileMtime(activeFile),
        oldestRenderMtime(previews, modules),
    ]);
    return sourceMtime != null && oldestMtime != null && sourceMtime > oldestMtime;
}

const moduleManifestCache = new Map<string, PreviewInfo[]>();
/**
 * Heavy previews the user explicitly asked to keep fresh for the current
 * editor focus scope. Cleared when focus leaves the backing Kotlin file.
 */
const heavyRefreshOptIns = new Map<string, Set<string>>();
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
let currentScopeModule: string | null = null;
/**
 * True when the panel currently shows a compile-error banner the
 * extension posted (either from the LSP gate or from a kotlinc parse
 * failure). The onDidChangeDiagnostics listener uses this to decide
 * whether a "diagnostics now empty" event for `currentScopeFile`
 * should auto-retry the refresh — pointless when no banner is up.
 * Cleared whenever the gate clears the banner or a refresh completes.
 */
let compileGateActive = false;
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
/** Module/file scopes that already received a daemon view-open pre-render in
 *  this extension session. Keeps "show this file's previews" warm-up from
 *  re-rendering the same cards on every focus bounce. */
const daemonShownPreviewWarmScopes = new Set<string>();
const daemonStartupProgressTimers = new Map<string, NodeJS.Timeout>();
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

/**
 * D2 — routes daemon-attached `a11y/atf` + `a11y/hierarchy` data products into the
 * [PreviewRegistry]. Inline payloads are decoded directly; path-transport kinds are
 * read off disk (the daemon writes them under `<outputDir.parent>/data/<previewId>/`).
 *
 * Failure mode: malformed JSON or a missing path file logs to the daemon channel and
 * leaves the registry untouched — the previous render's a11y state stays valid until
 * the next attachment arrives.
 *
 * Exported for unit-testability; the module-scoped scheduler wiring above is the
 * production caller.
 */
export function applyDataProductsToRegistry(
    registry: PreviewRegistry,
    previewId: string,
    dataProducts: DataProductAttachment[],
    log: { appendLine(value: string): void },
): { findings?: AccessibilityFinding[]; nodes?: AccessibilityNode[] } | null {
    let findings: AccessibilityFinding[] | undefined;
    let nodes: AccessibilityNode[] | undefined;
    for (const dp of dataProducts) {
        try {
            if (dp.kind === 'a11y/atf') {
                const payload = (dp.payload ?? readJsonPath(dp.path, log)) as
                    | { findings?: AccessibilityFinding[] }
                    | undefined;
                if (payload?.findings) { findings = payload.findings; }
            } else if (dp.kind === 'a11y/hierarchy') {
                const payload = (dp.payload ?? readJsonPath(dp.path, log)) as
                    | { nodes?: AccessibilityNode[] }
                    | undefined;
                if (payload?.nodes) { nodes = payload.nodes; }
            }
        } catch (err) {
            log.appendLine(
                `[daemon] data product ${dp.kind} for ${previewId} failed: ${(err as Error).message}`,
            );
        }
    }
    if (findings !== undefined || nodes !== undefined) {
        registry.setA11y(previewId, { findings, nodes });
        return { findings, nodes };
    }
    return null;
}

function readJsonPath(p: string | undefined, log: { appendLine(value: string): void }): unknown {
    if (!p) { return undefined; }
    try {
        return JSON.parse(fs.readFileSync(p, 'utf-8'));
    } catch (err) {
        log.appendLine(`[daemon] could not read data-product JSON at ${p}: ${(err as Error).message}`);
        return undefined;
    }
}

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

    gradleService = new GradleService(workspaceRoot, gradleApi, outputChannel, () => [], logFilter);

    // Daemon path is controlled by composePreview.daemon.enabled (true by default). When disabled
    // the gate's `isEnabled()` returns false and the scheduler is never asked to spawn anything —
    // the Gradle path is the temporary escape hatch. When enabled, daemon failures surface as
    // errors instead of silently falling back to Gradle.
    daemonGate = new DaemonGate(workspaceRoot, '0.1.0', outputChannel, logFilter);
    daemonScheduler = new DaemonScheduler(daemonGate, {
        onPreviewImageReady: (_moduleId, previewId, imageBase64) => {
            if (!panel) { return; }
            if (activeInteractiveStreams.has(previewId)) {
                logLine(`[interactive] frame ${previewId} bytes=${imageBase64.length}`);
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
                replaceExisting: false,
            });
        },
        onDataProductsAttached: (_moduleId, previewId, dataProducts) => {
            // D2 — route the data products attached to this render. For path-transport kinds
            // (`a11y/hierarchy`) we read the JSON off disk; inline kinds (`a11y/atf`) carry
            // their payload in `dp.payload`. The registry update fires `onDidChange`, which
            // the diagnostics provider already listens to. The panel receives a targeted
            // `updateA11y` post so its cached overlays repaint without re-emitting the entire
            // preview list.
            const decoded = applyDataProductsToRegistry(
                registry,
                previewId,
                dataProducts,
                outputChannel,
            );
            if (decoded && panel) {
                panel.postMessage({
                    command: 'updateA11y',
                    previewId,
                    findings: decoded.findings ?? undefined,
                    nodes: decoded.nodes ?? undefined,
                });
            }
        },
        onClasspathDirty: (moduleId, detail) => {
            outputChannel.appendLine(
                `[daemon] classpath dirty for ${moduleId}: ${detail} — falling back to Gradle`,
            );
            clearDaemonShownPreviewWarmScopes(moduleId);
            // Daemon will exit on its own (PROTOCOL.md § 6); the channel-
            // closed handler in DaemonGate evicts the entry. Next save runs
            // Gradle, which re-bootstraps a fresh daemon when the user
            // re-enables it via composePreviewDaemonStart.
            // Drop the interactive-mode availability so any open LIVE chip
            // disables itself instead of streaming stale frames from a
            // soon-to-die daemon.
            publishInteractiveAvailability(moduleId);
        },
        onDiscoveryUpdated: (moduleId, params) => {
            // Daemon emits this only when the in-memory preview index drifted
            // (added / removed / changed against the snapshot it held before
            // the save). Identity-only saves are silent. Apply the diff to
            // the extension-side mirror used for daemon focus computation —
            // we deliberately don't post a "loading" or progress message;
            // the user already saw the new PNG arrive via `renderFinished`,
            // and a webview reshape is only needed when the preview set
            // actually changed.
            applyDiscoveryDiff(moduleId, params);
        },
        onHistoryAdded: (_moduleId, params) => {
            // Phase H7 — daemon push: a fresh render landed and was
            // archived. Forward to the History panel; the panel filters
            // by `entry.module` against the currently-scoped module so an
            // unrelated render in a background module doesn't pop the
            // timeline.
            historyPanel?.onHistoryAdded(params);
        },
        onChannelClosed: (moduleId) => {
            clearDaemonShownPreviewWarmScopes(moduleId);
            // Daemon's stdio channel closed (process exit, classpath dirty,
            // spawn died). frameStreamIds don't survive a JVM restart, so
            // drop every entry in `activeInteractiveStreams` whose previewId
            // belongs to this module. Without this, a click landing after
            // the daemon respawned would carry a stale streamId the new
            // JVM never minted, and v2 dispatch would silently drop. The
            // extension's `previewModuleMap` still resolves correctly post-
            // respawn so the lookup uses today's mapping.
            const stale: string[] = [];
            for (const previewId of activeInteractiveStreams.keys()) {
                if (previewModuleMap.get(previewId) === moduleId) {
                    stale.push(previewId);
                }
            }
            for (const previewId of stale) {
                activeInteractiveStreams.delete(previewId);
            }
            updateInteractiveStatus();
            if (stale.length > 0) {
                logLine(
                    `[interactive] daemon channel closed for ${moduleId}; ` +
                    `dropped ${stale.length} stale stream(s): ${stale.join(', ')}`,
                );
            }
        },
    }, outputChannel,
        // D2 — read the user setting freshly on each `setVisible` so toggling
        // `composePreview.a11y.alwaysSubscribe` doesn't need a window reload.
        () =>
            vscode.workspace
                .getConfiguration('composePreview')
                .get<boolean>('a11y.alwaysSubscribe', false));

    // Status-bar slot for daemon lifecycle. Hidden when the daemon flag is
    // off or no module is currently warming. Surfacing the cold-bootstrap
    // pause (typically 2-4 s on first scope-in) avoids the "panel is stuck"
    // perception on the daemon flag's first-time UX.
    daemonStatusItem = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Left, 90,
    );
    daemonStatusItem.command = 'workbench.action.output.toggleOutput';
    context.subscriptions.push(daemonStatusItem);

    // INTERACTIVE.md § 9 / #425 — status-bar hint surfaced only when the user has LIVE active
    // on a daemon backend that DOESN'T speak v2 (`InitializeResult.capabilities.interactive`
    // is false or absent). Today that's any module on the Robolectric/Android backend; the
    // panel's LIVE chip lights up regardless, but the user sees clicks not mutate state and
    // would otherwise have no signal as to why. Hidden when v2 IS supported (the LIVE chip
    // alone is sufficient feedback) and when no streams are active.
    interactiveStatusItem = vscode.window.createStatusBarItem(
        vscode.StatusBarAlignment.Left, 89,
    );
    interactiveStatusItem.command = 'workbench.action.output.toggleOutput';
    context.subscriptions.push(interactiveStatusItem);

    panel = new PreviewPanel(
        context.extensionUri,
        handleWebviewMessage,
        () => historyPanel?.isVisible() ?? false,
    );
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

    // Watch the compose-preview/main ref for fetch-driven changes. When the ref
    // moves, any open "Diff vs main" overlay in the live panel needs to
    // re-issue against the new bytes. The watcher coalesces fetch bursts
    // internally; we just message the live panel and let it reissue.
    context.subscriptions.push(
        watchPreviewMainRef(workspaceRoot, () => {
            panel?.postMessage({ command: 'previewMainRefChanged' });
        }),
    );

    // Phase H7 — Preview History panel (HISTORY.md § "VS Code integration").
    // Live when `composePreview.daemon.enabled` is true; falls
    // back to reading `<projectDir>/.compose-preview-history/index.jsonl` +
    // sidecars when the daemon isn't healthy. View shows up unconditionally
    // — the view container is contributed in package.json — but its content
    // is empty when no Kotlin file is in scope. The setScope() driver is
    // wired below into the active-editor change events.
    historyScopeRef.current = null;
    historySource = buildHistorySource({
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
    });
    historyPanel = new HistoryPanel(context.extensionUri, historySource);
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
        vscode.commands.registerCommand('composePreview.diffAllVsMain', () => diffAllVsMain()),
        vscode.commands.registerCommand('composePreview.launchOnDevice',
            (previewId?: string) => launchOnDevice(previewId)),
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
                if (!isPreviewSourceFile(filePath)) { return; }
                // Focus toggling (editor ↔ webview/terminal ↔ back) fires this
                // event with the same Kotlin file. Re-running refresh there
                // just cancels any in-flight render, flashes spinners, and
                // burns a Gradle invocation — all for a no-op.
                if (filePath === currentScopeFile) { return; }
                // INTERACTIVE.md § 3 — drop active interactive streams when
                // the user moves focus to a different file. Daemon-side
                // streams flushed here, panel cleared via clearInteractive
                // below; the panel's own setPreviews flow would otherwise
                // race this on the new file's manifest arrival.
                void flushInteractiveStreams();
                panel?.postMessage({ command: 'clearInteractive' });
                clearHeavyRefreshOptIns();
                // Reconcile the panel first, then pre-warm the daemon for
                // this file's module. Once daemon startup finishes we run a
                // second discover pass and pre-render the shown previews so
                // the first edit doesn't have to pay JVM/sandbox startup.
                void (async () => {
                    await refresh(false, filePath);
                    await warmDaemonForFile(filePath, { refreshAfterReady: true });
                })();
                return;
            }
            // New active isn't a Kotlin editor (webview focus, Agent plan,
            // output pane, no active editor at all). If our sticky `.kt` is
            // still visible in a split, keep the panel as-is; otherwise the
            // sticky got covered/closed and we need to re-resolve (which may
            // blank the panel) — issue #145.
            if (currentScopeFile && !isFileVisibleInEditor(currentScopeFile)) {
                // The sticky scope file is no longer on screen — same UX flush as the
                // different-Kotlin-file branch above. The user can't see the source code
                // backing the live preview; stop the stream so the daemon doesn't keep
                // rendering into an invisible panel.
                void flushInteractiveStreams();
                panel?.postMessage({ command: 'clearInteractive' });
                clearHeavyRefreshOptIns();
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
                clearHeavyRefreshOptIns();
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

    // Auto-retry when the LSP clears errors on the currently-gated file.
    // Closes the loop on the typo-fix flow: today the user has to save
    // again to release the banner; here, the moment the LSP republishes
    // empty diagnostics for the saved buffer, we kick a fresh render.
    //
    // Guard rails:
    //   1. compileGateActive — no point retrying when the panel isn't
    //      currently showing an error banner.
    //   2. event affects currentScopeFile — diagnostic changes for
    //      unrelated files mustn't pull a render of the active one.
    //   3. buffer is saved (!isDirty) — Gradle reads the on-disk file,
    //      so retrying against an unsaved fix would still render against
    //      the bad version. Wait for the next save.
    //   4. errors actually clear — the event also fires on diagnostic
    //      content changes, e.g. severity bump from Warning to Error.
    context.subscriptions.push(
        vscode.languages.onDidChangeDiagnostics(e => onDiagnosticsChanged(e)),
    );

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
                gradleService = new GradleService(workspaceRoot, api, outputChannel, () => [], logFilter);
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
    stopAllDaemonStartupProgress();
    pendingRefresh?.abort();
    // Tell the daemon to release every interactive stream this session opened
    // before the JVMs go away. Without this, a soft-leak window exists for
    // streams the user toggled on but never explicitly toggled off — the
    // daemon's slot would point at the orphan previewId until the JVM exits.
    // Today's slot doesn't gate any background work so the leak is benign,
    // but flushing here keeps things tidy for v2 where `interactive/start`
    // pins a warm sandbox. Fire-and-forget — `flushInteractiveStreams` itself
    // races against `daemonGate.dispose()` on a tight deadline.
    void flushInteractiveStreams();
    // Drain any live daemon JVMs so the user doesn't end up with orphaned
    // processes after a window close. Fire-and-forget — VS Code won't wait
    // for an async deactivate beyond a few seconds anyway.
    void daemonGate?.dispose();
    disposePreviewMainBatches();
}

/**
 * Sends `interactive/stop` for every entry in [activeInteractiveStreams] and
 * clears the map. Called from {@link deactivate} so a window close doesn't
 * leave the daemon thinking it has live targets. Idempotent — safe to call
 * multiple times. Best-effort: failures (channel already closed, stop
 * notification can't be written, daemon doesn't speak interactive RPC) are
 * silently swallowed because we're on the way out anyway.
 */
async function flushInteractiveStreams(): Promise<void> {
    if (activeInteractiveStreams.size === 0) { return; }
    const entries = [...activeInteractiveStreams.entries()];
    activeInteractiveStreams.clear();
    updateInteractiveStatus();
    if (!daemonGate?.isEnabled() || !daemonScheduler) { return; }
    await Promise.all(entries.map(async ([previewId, streamId]) => {
        try {
            const moduleId = previewModuleMap.get(previewId);
            if (!moduleId) { return; }
            const client = await daemonGate?.getOrSpawn(
                moduleId, daemonScheduler!.daemonEvents(moduleId),
            );
            client?.interactiveStop({ frameStreamId: streamId });
        } catch {
            /* deactivate path — best-effort */
        }
    }));
}

function sameScope(a: string[], b: string[]): boolean {
    if (a.length !== b.length) { return false; }
    const set = new Set(b);
    return a.every(m => set.has(m));
}

function isSourceFile(filePath: string): boolean {
    if (isGeneratedOutputPath(filePath)) { return false; }
    return /\.(kt|xml|json|properties)$/i.test(filePath);
}

function isGeneratedOutputPath(filePath: string): boolean {
    const segments = filePath.split(/[\\/]+/);
    return segments.includes('build') || segments.includes('bin');
}

/** True iff this is a Kotlin source file (.kt), not generated output or a Gradle build script. */
function isPreviewSourceFile(filePath: string): boolean {
    return filePath.endsWith('.kt')
        && !filePath.endsWith('.gradle.kts')
        && !isGeneratedOutputPath(filePath);
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
    if (forFilePath && isPreviewSourceFile(forFilePath)) {
        return { file: forFilePath, source: 'caller' };
    }

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
 * Applies a daemon-emitted `discoveryUpdated` diff silently. Only fires on a
 * non-empty diff (the daemon stays quiet when the in-memory preview index
 * matches a fresh scan), so reaching this code path always means the panel
 * needs to reshape.
 *
 * The daemon's lightweight `DiscoveryPreviewInfo` doesn't carry capture paths,
 * device dimensions, or a11y data — only id / className / functionName /
 * sourceFile / displayName / group. We need full `PreviewInfo` to repaint
 * cards, so we run a silent `discoverPreviews` here (no progress bar, no
 * "Building..." banner, no setLoading message). The user already saw the new
 * PNG via `renderFinished`; the panel reshape lands behind it.
 *
 * Identity-only saves never reach this function — the daemon's
 * `discoveryDiffEmpty(diff)` guard short-circuits before emitting. So we don't
 * pay for the gradle discover round-trip on every keystroke; only when the
 * preview set genuinely drifted.
 *
 * Errors are logged and dropped — the next user-driven refresh will reconcile.
 */
async function applyDiscoveryDiff(moduleId: string, params: { added: unknown[]; removed: string[]; changed: unknown[]; totalPreviews: number }): Promise<void> {
    if (!gradleService || !panel) { return; }

    const removedIds = params.removed ?? [];
    const addedCount = params.added?.length ?? 0;
    const changedCount = params.changed?.length ?? 0;
    if (removedIds.length === 0 && addedCount === 0 && changedCount === 0) {
        return;
    }

    // Always reconcile caches, even when no preview editor is visible. Only
    // repaint the panel when its current scope still belongs to this module;
    // daemon save/discovery events can also arrive for background files.
    const repaintFile = currentScopeFile && gradleService.resolveModule(currentScopeFile) === moduleId
        ? currentScopeFile
        : undefined;
    await reconcilePreviewManifest(moduleId, repaintFile);
}

/**
 * Side-channel save handler that pushes a `fileChanged` + focus-scoped `renderNow` to the daemon
 * when the daemon path is enabled. Explicit daemon opt-out returns `'disabled'` so the caller can
 * use the Gradle render path; daemon failures return `'failed'` and do not fall back.
 */
type DaemonSaveResult = 'accepted' | 'disabled' | 'failed';

async function notifyDaemonOfSave(filePath: string): Promise<DaemonSaveResult> {
    if (!daemonGate?.isEnabled() || !daemonScheduler || !gradleService) { return 'disabled'; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return 'failed'; }

    // Bootstrap (Gradle task + JVM spawn) happens once per module per
    // session. Normally it has already fired from the active-editor warm
    // path; on the rare case where the user saves before scope-in (e.g.
    // external file save, another editor split) we cover ourselves here.
    if (!daemonBootstrappedModules.has(module)) {
        daemonBootstrappedModules.add(module);
        try {
            const warmed = await daemonScheduler.warmModule(
                gradleService, module,
                (state) => updateDaemonStatus(module, state),
            );
            if (!warmed) {
                daemonBootstrappedModules.delete(module);
                return daemonGate.isBuildDisabled(module) ? 'disabled' : 'failed';
            }
        } catch (err) {
            daemonBootstrappedModules.delete(module);
            logLine(`daemon: ${String((err as Error).message ?? err)}`);
            return daemonGate.isBuildDisabled(module) ? 'disabled' : 'failed';
        }
    }

    try {
        const ok = await daemonScheduler.ensureModule(module);
        if (!ok) { return daemonGate.isBuildDisabled(module) ? 'disabled' : 'failed'; }
        await daemonScheduler.fileChanged(module, filePath);
    } catch (err) {
        logLine(`daemon: ${String((err as Error).message ?? err)}`);
        return daemonGate.isBuildDisabled(module) ? 'disabled' : 'failed';
    }

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
    let filePreviews = manifest.filter(p => p.sourceFile === filterFile);
    if (await sourceMayHaveDroppedCachedPreviews(filePath, filePreviews)) {
        logLine(`daemon: preview declarations changed in ${path.basename(filePath)}; reconciling before render`);
        const repaintFile = currentScopeFile && gradleService.resolveModule(currentScopeFile) === module
            ? currentScopeFile
            : undefined;
        const fresh = await reconcilePreviewManifest(module, repaintFile);
        filePreviews = fresh?.filter(p => p.sourceFile === filterFile) ?? filePreviews;
    }
    const ids = filePreviews.map(p => p.id);
    if (ids.length === 0) { return 'accepted'; }
    try {
        await daemonScheduler.setFocus(module, ids);
        const fullIds = heavyOptInsFor(module, ids);
        const fastIds = fullIds.length === 0
            ? ids
            : ids.filter(id => !fullIds.includes(id));

        if (fastIds.length > 0) {
            const ok = await daemonScheduler.renderNow(module, fastIds, 'fast', 'save');
            if (!ok) { return 'failed'; }
        }
        if (fullIds.length > 0) {
            const ok = await daemonScheduler.renderNow(module, fullIds, 'full', 'save-heavy-opt-in');
            if (!ok) { return 'failed'; }
        }
        return 'accepted';
    } catch (err) {
        logLine(`daemon: ${String((err as Error).message ?? err)}`);
        return daemonGate.isBuildDisabled(module) ? 'disabled' : 'failed';
    }
}

/**
 * Paint the panel from the previously-rendered `previews.json` + PNGs on disk
 * before kicking the activation Gradle round-trip. Without this the panel
 * spends the first ~1–2 s of every session on a "Loading Compose previews…"
 * placeholder while `discoverPreviews` configures Gradle — even when a perfect
 * set of cached cards is sitting in `build/compose-previews/`.
 *
 * On activation the cached cards intentionally look fresh. Until the user
 * edits or explicitly refreshes, a stale/loading overlay reads like a broken
 * startup rather than useful state; the top progress line carries the
 * background work instead.
 *
 * Returns `true` when something was painted (caller can short-circuit any
 * "first launch" empty-state logic), `false` when no usable manifest existed.
 */
async function preloadCachedPreviews(filePath: string): Promise<boolean> {
    if (!gradleService || !panel) { return false; }
    if (!isPreviewSourceFile(filePath)) { return false; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return false; }
    const manifest = gradleService.readManifest(module);
    if (!manifest || manifest.previews.length === 0) { return false; }

    const filterFile = packageQualifiedSourcePath(filePath);
    const visiblePreviews = manifest.previews.filter(p => p.sourceFile === filterFile);
    if (visiblePreviews.length === 0) { return false; }

    for (const p of visiblePreviews) {
        for (const capture of p.captures) {
            capture.label = captureLabel(capture);
        }
        previewModuleMap.set(p.id, module);
    }

    panel.postMessage({
        command: 'setPreviews',
        previews: visiblePreviews,
        moduleDir: module,
        heavyStaleIds: [],
    });

    const imageJobs: Promise<void>[] = [];
    for (const preview of visiblePreviews) {
        for (let idx = 0; idx < preview.captures.length; idx++) {
            const capture = preview.captures[idx];
            if (!capture.renderOutput) { continue; }
            const captureIndex = idx;
            imageJobs.push((async () => {
                const imageData = await gradleService!
                    .readPreviewImage(module, capture.renderOutput);
                if (!imageData || !panel) { return; }
                if (captureIndex === 0) {
                    registry.setImage(preview.id, imageData);
                }
                panel.postMessage({
                    command: 'updateImage',
                    previewId: preview.id,
                    captureIndex,
                    imageData,
                });
            })());
        }
    }
    await Promise.all(imageJobs);

    // Sync the extension-side state the upcoming refresh() reads so it
    // takes the stealth-refresh path (no clearAll) rather
    // than tearing down what we just painted.
    hasPreviewsLoaded = true;
    lastLoadedModules = [module];
    moduleManifestCache.set(module, visiblePreviews);
    setCurrentScopeFile(filePath);
    return true;
}

/**
 * Activation-time refresh sequence. Phases run in order:
 *
 *   0. `preloadCachedPreviews(filePath)` — paints the previously-rendered
 *      cards from `build/compose-previews/` as normal cards so the panel
 *      never opens onto an empty screen while Gradle warms up. Skipped
 *      silently when no usable manifest exists.
 *   1. `refresh(false, filePath)` — runs `discoverPreviews` and reconciles
 *      the panel with whatever's currently on disk. With phase 0 having
 *      painted, this stage updates metadata in place without adding stale
 *      overlays or a full-screen takeover.
 *   2. `warmDaemonForFile(filePath)` — runs `composePreviewDaemonStart` and
 *      spawns the daemon JVM. No-op if the daemon flag is off.
 *   3. Once daemon warm-up completes, run a post-warm discover pass so the
 *      panel reconciles immediately instead of waiting for an editor-focus
 *      bounce. Then pre-render the shown previews through the daemon and fire
 *      the save-equivalent path so the current file gets a fresh daemon render
 *      even without the user touching it.
 *
 * Failures in phases 2 or 3 silently fall back: phases 0/1 already gave the
 * user something to look at, and the next save will re-trigger the whole
 * pipeline through `runRefreshExclusive`.
 */
async function runActivationRefresh(filePath: string): Promise<void> {
    await preloadCachedPreviews(filePath);
    await refresh(false, filePath, 'full', { showLoadingOverlay: false });
    await warmDaemonForFile(filePath, { refreshAfterReady: true });
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
async function warmDaemonForFile(
    filePath: string,
    opts: { refreshAfterReady?: boolean } = {},
): Promise<boolean> {
    if (!daemonGate?.isEnabled() || !daemonScheduler || !gradleService) { return false; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return false; }
    if (daemonBootstrappedModules.has(module)) {
        if (daemonGate.isDaemonReady(module) && opts.refreshAfterReady) {
            await refreshAfterDaemonReady(filePath, 'view-open');
        }
        return daemonGate.isDaemonReady(module);
    }
    daemonBootstrappedModules.add(module);
    try {
        const warmed = await daemonScheduler.warmModule(
            gradleService, module,
            (state) => {
                updateDaemonStatus(module, state);
                publishDaemonStartupProgress(module, state);
            },
        );
        if (!warmed) {
            daemonBootstrappedModules.delete(module);
        }
        finishDaemonStartupProgress(module, filePath, warmed);
        if (warmed && opts.refreshAfterReady) {
            await refreshAfterDaemonReady(filePath, 'view-open');
        }
        return warmed;
    } catch (err) {
        daemonBootstrappedModules.delete(module);
        stopDaemonStartupProgress(module);
        panel?.postMessage({ command: 'clearProgress' });
        logLine(`daemon: warm failed for ${module}: ${String((err as Error).message ?? err)}`);
        vscode.window.showErrorMessage(
            'Compose Preview daemon is enabled but failed to start. Preview saves will not fall back to Gradle until the daemon is fixed or disabled.',
        );
        return false;
    }
}

/**
 * The first activation path runs preview discovery before daemon warm-up.
 * On a cold daemon start that means an empty discovery result can otherwise
 * sit in the panel as "No @Preview functions…" while Gradle is still
 * bootstrapping the daemon. Surface the daemon phase in the panel itself so
 * the user can tell the extension is still doing useful work.
 */
function publishDaemonStartupProgress(module: string, state: WarmState): void {
    if (!panel) { return; }
    if (!isDaemonStartupScopeActive(module)) {
        stopDaemonStartupProgress(module);
        return;
    }
    switch (state) {
        case 'bootstrapping':
            if (!hasPreviewsLoaded) {
                panel.postMessage({
                    command: 'showMessage',
                    text: `Preparing Compose Preview daemon for ${module}…`,
                });
            }
            startDaemonStartupProgress(
                module,
                'Preparing preview daemon',
                0.08,
                0.62,
                DAEMON_BOOTSTRAP_PROGRESS_MS,
            );
            break;
        case 'spawning':
            if (!hasPreviewsLoaded) {
                panel.postMessage({
                    command: 'showMessage',
                    text: `Starting Compose Preview daemon for ${module}…`,
                });
            }
            startDaemonStartupProgress(
                module,
                'Starting preview daemon',
                0.62,
                0.94,
                DAEMON_SPAWN_PROGRESS_MS,
            );
            break;
        case 'ready':
            stopDaemonStartupProgress(module);
            if (!isDaemonStartupScopeActive(module)) { return; }
            if (!hasPreviewsLoaded) {
                panel.postMessage({
                    command: 'showMessage',
                    text: 'Compose Preview daemon is ready. Rendering previews…',
                });
            }
            panel.postMessage({
                command: 'setProgress',
                phase: 'daemon',
                label: 'Preview daemon ready',
                percent: 1,
                slow: false,
            });
            break;
        case 'fallback':
            stopDaemonStartupProgress(module);
            if (!isDaemonStartupScopeActive(module)) { return; }
            if (!hasPreviewsLoaded) {
                panel.postMessage({
                    command: 'showMessage',
                    text: `Compose Preview daemon is disabled for ${module}; using Gradle previews.`,
                });
            }
            panel.postMessage({ command: 'clearProgress' });
            break;
    }
}

function startDaemonStartupProgress(
    module: string,
    label: string,
    start: number,
    end: number,
    durationMs: number,
): void {
    stopDaemonStartupProgress(module);
    const startedAt = Date.now();
    const tick = () => {
        if (!panel) { return; }
        if (!isDaemonStartupScopeActive(module)) {
            stopDaemonStartupProgress(module);
            return;
        }
        const elapsed = Date.now() - startedAt;
        const ratio = Math.min(0.98, 1 - Math.exp(-elapsed / durationMs));
        panel.postMessage({
            command: 'setProgress',
            phase: 'daemon',
            label,
            percent: start + ((end - start) * ratio),
            slow: false,
        });
    };
    tick();
    daemonStartupProgressTimers.set(module, setInterval(tick, 250));
}

function isDaemonStartupScopeActive(module: string): boolean {
    return currentScopeModule === module;
}

function setCurrentScopeFile(filePath: string | null, module?: string | null): void {
    currentScopeFile = filePath;
    currentScopeModule = module ?? (filePath && gradleService ? gradleService.resolveModule(filePath) : null);
}

function stopDaemonStartupProgress(module: string): void {
    const timer = daemonStartupProgressTimers.get(module);
    if (!timer) { return; }
    clearInterval(timer);
    daemonStartupProgressTimers.delete(module);
}

function stopAllDaemonStartupProgress(): void {
    for (const module of daemonStartupProgressTimers.keys()) {
        stopDaemonStartupProgress(module);
    }
}

function finishDaemonStartupProgress(module: string, filePath: string, warmed: boolean): void {
    if (!panel || hasPreviewsLoaded || !warmed) { return; }
    const visibleCount = visiblePreviewCount(module, filePath);
    if (visibleCount > 0) { return; }
    panel.postMessage({ command: 'clearProgress' });
    const allPreviews = moduleManifestCache.get(module)?.length ?? 0;
    panel.postMessage({
        command: 'showMessage',
        text: allPreviews > 0
            ? `No @Preview functions in this file (${allPreviews} in other files in this module).`
            : 'No @Preview functions found in this module',
    });
}

function visiblePreviewCount(module: string, filePath: string): number {
    const previews = moduleManifestCache.get(module) ?? [];
    const filterFile = packageQualifiedSourcePath(filePath);
    return previews.filter(p => p.sourceFile === filterFile).length;
}

async function reconcilePreviewManifest(
    module: string,
    repaintFilePath?: string,
): Promise<PreviewInfo[] | null> {
    if (!gradleService) { return null; }
    gradleService.invalidateCache(module);
    let manifest;
    try {
        manifest = await gradleService.discoverPreviews(module);
    } catch (err) {
        logLine(`[daemon] silent discover failed for ${module}: ${(err as Error).message}`);
        return null;
    }
    if (!manifest) { return null; }

    const fresh = manifest.previews;
    for (const [id, owner] of [...previewModuleMap.entries()]) {
        if (owner === module) { previewModuleMap.delete(id); }
    }
    for (const p of fresh) {
        for (const capture of p.captures) {
            capture.label = captureLabel(capture);
        }
        previewModuleMap.set(p.id, module);
    }
    moduleManifestCache.set(module, fresh);
    registry.replaceModule(module, fresh);

    if (!panel || !repaintFilePath) { return fresh; }

    const filterFile = packageQualifiedSourcePath(repaintFilePath);
    const visiblePreviews = fresh.filter(p => p.sourceFile === filterFile);
    const heavyStaleIds = fastTierModules.has(module)
        ? visiblePreviews
            .filter(hasHeavyCapture)
            .map(p => p.id)
        : [];
    panel.postMessage({
        command: 'setPreviews',
        previews: visiblePreviews,
        moduleDir: module,
        heavyStaleIds,
    });
    return fresh;
}

async function refreshAfterDaemonReady(filePath: string, reason: string): Promise<void> {
    if (!daemonGate || !gradleService || !panel) { return; }
    const module = gradleService.resolveModule(filePath);
    if (!module || !daemonGate.isDaemonReady(module)) { return; }
    if (currentScopeFile && currentScopeFile !== filePath) { return; }
    if (pendingRefresh || refreshInFlight) {
        logLine(`daemon: skip post-warm refresh for ${module}; refresh already active`);
        return;
    }
    const reconciled = await reconcilePreviewManifestAfterDaemonReady(module, filePath);
    if (!reconciled) { return; }
    await warmShownPreviewsForFile(filePath, reason);
    panel.postMessage({
        command: 'setProgress',
        phase: 'daemon',
        label: 'Preview daemon ready',
        percent: 1,
        slow: false,
    });
}

async function reconcilePreviewManifestAfterDaemonReady(
    module: string,
    filePath: string,
): Promise<boolean> {
    if (!gradleService || !panel) { return false; }
    try {
        panel.postMessage({
            command: 'setProgress',
            phase: 'daemon',
            label: 'Checking preview list',
            percent: 0.86,
            slow: false,
        });
        gradleService.invalidateCache(module);
        const manifest = await gradleService.discoverPreviews(module);
        if (!manifest) {
            panel.postMessage({ command: 'clearProgress' });
            return false;
        }
        if (currentScopeFile && currentScopeFile !== filePath) {
            panel.postMessage({ command: 'clearProgress' });
            return false;
        }

        const fresh = manifest.previews;
        const freshIds = new Set(fresh.map(p => p.id));
        for (const [id, owner] of [...previewModuleMap.entries()]) {
            if (owner === module && !freshIds.has(id)) {
                previewModuleMap.delete(id);
            }
        }
        for (const p of fresh) {
            for (const capture of p.captures) {
                capture.label = captureLabel(capture);
            }
            previewModuleMap.set(p.id, module);
        }
        moduleManifestCache.set(module, fresh);
        registry.replaceModule(module, fresh);

        const filterFile = packageQualifiedSourcePath(filePath);
        const visiblePreviews = fresh.filter(p => p.sourceFile === filterFile);
        if (visiblePreviews.length === 0) {
            if (!hasPreviewsLoaded) {
                panel.postMessage({
                    command: 'showMessage',
                    text: fresh.length > 0
                        ? `No @Preview functions in this file (${fresh.length} in other files in this module).`
                        : 'No @Preview functions found in this module',
                });
            }
            panel.postMessage({ command: 'clearProgress' });
            return false;
        }

        const heavyStaleIds = fastTierModules.has(module)
            ? visiblePreviews
                .filter(hasHeavyCapture)
                .map(p => p.id)
            : [];
        panel.postMessage({
            command: 'setPreviews',
            previews: visiblePreviews,
            moduleDir: module,
            heavyStaleIds,
        });
        panel.postMessage({ command: 'clearCompileErrors' });
        compileGateActive = false;
        hasPreviewsLoaded = true;
        return true;
    } catch (err) {
        logLine(`daemon: post-warm discover failed for ${module}: ${(err as Error).message}`);
        panel.postMessage({ command: 'clearProgress' });
        return false;
    }
}

async function warmShownPreviewsForFile(filePath: string, reason: string): Promise<void> {
    if (!daemonGate?.isEnabled() || !daemonScheduler || !gradleService) { return; }
    const module = gradleService.resolveModule(filePath);
    if (!module || !daemonGate.isDaemonReady(module)) { return; }

    const filterFile = packageQualifiedSourcePath(filePath);
    const scopeKey = `${module}::${filterFile}`;
    if (daemonShownPreviewWarmScopes.has(scopeKey)) { return; }

    const ids = (moduleManifestCache.get(module) ?? [])
        .filter(p => p.sourceFile === filterFile)
        .map(p => p.id);
    if (ids.length === 0) { return; }

    daemonShownPreviewWarmScopes.add(scopeKey);
    try {
        await daemonScheduler.setFocus(module, ids);
        await daemonScheduler.setVisible(module, ids, []);
        await daemonScheduler.renderNow(module, ids, 'fast', reason);
    } catch (err) {
        daemonShownPreviewWarmScopes.delete(scopeKey);
        logLine(`daemon: view-open warmup failed for ${module}: ${String((err as Error).message ?? err)}`);
    }
}

function clearDaemonShownPreviewWarmScopes(moduleId: string): void {
    const prefix = `${moduleId}::`;
    for (const key of [...daemonShownPreviewWarmScopes]) {
        if (key.startsWith(prefix)) {
            daemonShownPreviewWarmScopes.delete(key);
        }
    }
}

/**
 * Drives the status-bar item through the warm-up state machine. The
 * "ready" state holds for a few seconds so the user sees the transition
 * from "warming" before the indicator fades; "fallback" holds longer so
 * the user can see why their file was rendered via Gradle. Hidden any
 * time no module is in flight.
 */
function updateDaemonStatus(module: string, state: WarmState): void {
    // Push availability for the interactive (live-stream) toggle on every
    // state transition. Done outside the !daemonStatusItem early-return so
    // tests that drive activate() without a status bar still see the
    // panel-side state propagate. Cheap: a no-op when panel isn't open.
    if (state === 'ready' || state === 'fallback') {
        publishInteractiveAvailability(module);
    }
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


/**
 * Auto-retry the refresh when the LSP republishes diagnostics that
 * clear the currently-gated file. See the listener registration site
 * for the four guard rails (compileGateActive, scope match, !isDirty,
 * errors actually cleared).
 *
 * Pulled out of the listener call site so it can early-return cheaply
 * without nesting; the event fires for every diagnostic change in the
 * workspace, so the body needs to bail fast in the common case where
 * we don't care.
 */
function onDiagnosticsChanged(e: vscode.DiagnosticChangeEvent): void {
    if (!compileGateActive || !currentScopeFile) { return; }
    const scopeFile = currentScopeFile;
    if (!e.uris.some(u => u.fsPath === scopeFile)) { return; }
    const doc = vscode.workspace.textDocuments.find(d => d.uri.fsPath === scopeFile);
    if (doc?.isDirty) { return; }
    const errors = readCompileErrors(scopeFile);
    if (errors.length > 0) { return; }
    logLine(`auto-retry: LSP diagnostics cleared on ${path.basename(scopeFile)}`);
    compileGateActive = false;
    void refresh(true, scopeFile, 'fast');
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
 *  When the daemon is enabled for the file's module, the save uses the daemon path. If the daemon
 *  is unavailable while enabled, we surface an error instead of rendering via Gradle. Explicitly
 *  disabling the daemon keeps the Gradle render path available for now.
 *
 *  **Recompile-before-notify invariant.** In the daemon path the compile
 *  step runs *first*, then the daemon is notified. The daemon's
 *  `fileChanged({kind:source})` swaps its `URLClassLoader` and `renderNow`
 *  reads `.class` files from `build/intermediates/.../classes/` — so those
 *  files must be fresh when the swap happens. We invoke
 *  `composePreviewCompile` (the same `compileKotlin*` upstream that
 *  `discoverPreviews` depends on, minus the ClassGraph scan over every
 *  dependency JAR) — the heavy classpath walk is now the daemon's job via
 *  `IncrementalDiscovery`, surfaced through `discoveryUpdated` only when
 *  the preview set actually drifted.
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
            const compileOk = await runDaemonCompileOnly(filePath);
            if (!compileOk) {
                vscode.window.showErrorMessage(
                    'Compose Preview daemon refresh failed because compile failed. Fix the compile error and save again.',
                );
                return;
            }
            const result = await notifyDaemonOfSave(filePath);
            if (result === 'accepted') {
                return;
            }
            if (result === 'disabled') {
                await refresh(true, filePath, 'fast');
                return;
            }
            vscode.window.showErrorMessage(
                'Compose Preview daemon is enabled but unavailable. Restart the preview daemon after fixing the daemon issue.',
            );
            return;
        }
        await refresh(true, filePath, 'fast');
    } finally {
        refreshInFlight = false;
        maybeFirePendingRefresh();
    }
}

/**
 * Daemon-mode save: compile the upstream Kotlin task only — no `discoverPreviews`
 * round-trip, no progress UI, no spinner overlays. The `.class` files land on
 * disk fresh; the daemon then runs incremental discovery internally and emits
 * `discoveryUpdated` when (and only when) the preview set changed.
 *
 * Returns `false` if the file resolves to no module or the compile failed —
 * caller falls back to the Gradle render path. Errors are logged silently;
 * the user-visible LSP gate (`compileErrors.ts`) still surfaces compile
 * failures, this just keeps the panel quiet during the happy path.
 */
async function runDaemonCompileOnly(filePath: string): Promise<boolean> {
    if (!gradleService) { return false; }
    const module = gradleService.resolveModule(filePath);
    if (!module) { return false; }

    // Honour the existing LSP-error gate — same predicate `refresh()` uses to
    // skip Gradle when the active buffer has Error-severity diagnostics. We
    // don't need to surface the banner here (the gradle path handles that on
    // an explicit refresh); just skip the compile so we don't burn cycles
    // recompiling a file the LSP already says is broken.
    const compileErrors = readCompileErrors(filePath);
    if (compileErrors.length > 0) {
        logLine(`daemon: gated — ${compileErrors.length} compile error(s) in ${path.basename(filePath)}`);
        return false;
    }

    invalidateModuleCache(filePath);
    try {
        await gradleService.compileOnly(module);
    } catch (err) {
        if (err instanceof TaskCancelledError) {
            logLine(`daemon: compileOnly cancelled for ${module}`);
        } else {
            logLine(`daemon: compileOnly failed for ${module}: ${(err as Error).message}`);
        }
        return false;
    }
    return true;
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

function hasHeavyCapture(preview: PreviewInfo): boolean {
    return preview.captures.some(c => (c.cost ?? 1) > HEAVY_COST_THRESHOLD);
}

function optInHeavyRefresh(moduleId: string, previewId: string): void {
    const current = heavyRefreshOptIns.get(moduleId) ?? new Set<string>();
    current.add(previewId);
    heavyRefreshOptIns.set(moduleId, current);
}

function clearHeavyRefreshOptIns(): void {
    heavyRefreshOptIns.clear();
}

function heavyOptInsFor(moduleId: string, previewIds: string[]): string[] {
    const opted = heavyRefreshOptIns.get(moduleId);
    if (!opted || opted.size === 0) { return []; }
    return previewIds.filter(id => opted.has(id));
}

async function sourceMayHaveDroppedCachedPreviews(
    filePath: string,
    previews: PreviewInfo[],
): Promise<boolean> {
    if (previews.length === 0) { return false; }
    let source: string;
    try {
        source = await fs.promises.readFile(filePath, 'utf-8');
    } catch {
        return false;
    }
    return previews.some(preview => !sourceLooksLikePreviewDeclaration(source, preview.functionName));
}

function sourceLooksLikePreviewDeclaration(source: string, functionName: string): boolean {
    const escaped = functionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = new RegExp(`\\bfun\\s+${escaped}\\s*\\(`).exec(source);
    if (!match) { return false; }

    const lines = source.slice(0, match.index).split(/\r?\n/);
    const annotationLines: string[] = [];
    for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i].trim();
        if (line.length === 0) {
            if (annotationLines.length === 0) { continue; }
            break;
        }
        if (line.startsWith('@') || line.startsWith('//') || line.startsWith('/*') || line.startsWith('*')) {
            annotationLines.unshift(line);
            continue;
        }
        break;
    }
    return annotationLines.some(line => line.includes('Preview'));
}

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
    opts: { showLoadingOverlay?: boolean } = {},
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
        setCurrentScopeFile(null);
        clearHeavyRefreshOptIns();
        historyScopeRef.current = null;
        historyPanel?.setScope(null);
        if (activeFile && isPreviewSourceFile(activeFile)) {
            maybeShowSetupPrompt(activeFile);
        }
        return 'no-module';
    }
    if (currentScopeFile && currentScopeFile !== activeFile) {
        clearHeavyRefreshOptIns();
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
    //
    // Save-edge debounce. The Kotlin LSP can take 50–200 ms to republish
    // diagnostics for the post-save buffer. The first-save path skips
    // the save debounce, so without this re-read we'd happily gate on
    // pre-save errors that were just fixed. When the initial read shows
    // errors, wait briefly and re-check; if they cleared we proceed
    // straight to a normal refresh.
    const initialErrors = readCompileErrors(activeFile);
    if (initialErrors.length > 0) {
        await new Promise(resolve => setTimeout(resolve, GATE_REREAD_DELAY_MS));
        if (abort.signal.aborted) { return 'cancelled'; }
    }
    const compileErrors = initialErrors.length > 0
        ? readCompileErrors(activeFile)
        : initialErrors;
    if (compileErrors.length > 0) {
        panel.postMessage({
            command: 'setCompileErrors',
            errors: compileErrors,
        });
        // No build is starting — make sure no stale progress bar lingers
        // from a prior in-flight refresh that was just cancelled by the
        // pendingRefresh.abort() at the top of this function.
        panel.postMessage({ command: 'clearProgress' });
        setCurrentScopeFile(activeFile, module);
        compileGateActive = true;
        logLine(`gated — ${compileErrors.length} compile error(s) in ${path.basename(activeFile)}`);
        return 'gated';
    }
    // Errors cleared since the previous gate fire — drop the banner before
    // we begin the actual build so the user sees the panel return to its
    // normal "working" state.
    panel.postMessage({ command: 'clearCompileErrors' });
    compileGateActive = false;

    setCurrentScopeFile(activeFile, module);
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
    const showLoadingOverlay = opts.showLoadingOverlay !== false;
    if (hasPreviewsLoaded && showLoadingOverlay) {
        panel.postMessage({ command: 'markAllLoading' });
    } else {
        if (!hasPreviewsLoaded) {
            panel.postMessage({ command: 'setLoading' });
        }
    }
    lastLoadedModules = modules;
    gradleService.cancel();

    // Forward progress-bar updates to the webview. Single tracker per refresh
    // — single instance feeds the Gradle phase signals (compile/discover/
    // render) AND the post-Gradle `loading` phase that the tracker
    // auto-detects from the `BUILD SUCCESSFUL` line. Lifecycle is owned
    // here, not by gradleService, so the tick interval can survive past
    // `runTask` returning while we read manifests + base64-encode PNGs,
    // and so failure / cancellation paths can shut it down deterministically.
    // Without local ownership the tracker's tick kept emitting setProgress
    // messages after Gradle finished, freezing the bar on whatever state
    // it last computed (typically "(slow) · 99%").
    const tracker = new BuildProgressTracker({
        onProgress: (state) => {
            if (abort.signal.aborted) { return; }
            panel?.postMessage({
                command: 'setProgress',
                phase: state.phase,
                label: state.label,
                percent: state.percent,
                slow: state.slow,
            });
        },
        calibration: readCalibration(module),
    });
    tracker.start();
    const taskOpts = {
        onTaskOutput: (chunk: string) => tracker.consume(chunk),
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
                .filter(hasHeavyCapture)
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
        // pushing to webview) is extension-side. The tracker has already
        // transitioned to its `loading` phase via the `BUILD SUCCESSFUL`
        // marker in Gradle's stdout — it'll keep ticking the bar through
        // that phase asymptotically while the imageJobs run, with no
        // explicit signal needed from this side. tracker.finish() at the
        // end of the happy path drives the bar to 100% and stops the tick.
        if (!abort.signal.aborted) {
            tracker.enterPhase('loading');
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
        //
        // Skip the imageJobs entirely when discover-only AND the source is
        // newer than the PNGs on disk: those bytes are stale relative to
        // the user's current buffer, and pushing them via updateImage would
        // remove the loading overlay and surface yesterday's render as if
        // it were the current state. Cards keep their cached imageData
        // (last known good) under the loading overlay until the auto-render
        // we kick off below replaces them with fresh bytes.
        const sourceIsStale = !forceRender
            && await isSourceNewerThanRenders(activeFile, visiblePreviews, modules);
        if (!sourceIsStale) {
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
                            // capture. Look for the per-preview error sidecar
                            // the renderer drops next to the would-be PNG; if
                            // found, surface the structured exception detail
                            // (class, message, top app frame). Falls back to
                            // the generic "see Output" message when the
                            // sidecar is absent — that's the case for the
                            // Android Robolectric path which doesn't yet
                            // write sidecars (planned follow-up).
                            const renderError = await gradleService!.readPreviewRenderError(
                                mod, capture.renderOutput,
                            );
                            if (abort.signal.aborted || !panel) { return; }
                            const message = renderError
                                ? formatRenderErrorMessage(renderError)
                                : 'Render failed — see Output ▸ Compose Preview';
                            panel.postMessage({
                                command: 'setImageError',
                                previewId: preview.id,
                                captureIndex: idx,
                                message,
                                renderError,
                                replaceExisting: true,
                            });
                        }
                        // else: discover-only pass, PNG not produced yet. Leave
                        // the skeleton in place; the next save-triggered render
                        // will populate it.
                    })());
                }
            }
            await Promise.all(imageJobs);
        }
        if (!abort.signal.aborted) {
            // Drive the bar to 100% and stop the tick. The webview holds
            // the completed state for ~600ms then fades the strip away.
            // The finally block's tracker.abort() is a no-op once we've
            // called finish().
            tracker.finish();
            writeCalibration(module, tracker.phaseDurations);
        }

        // Source-newer-than-renders auto-refresh. Replaces the old "Showing
        // previews from <ago>" banner — the banner pushed the work onto the
        // user, this just re-renders. Scheduled via setTimeout(0) so the
        // current refresh resolves cleanly first; the new refresh's
        // pendingRefresh.abort() then takes over.
        //
        // Only fires from the discover-only path: forceRender=true paths
        // just rendered, so PNGs are fresh by definition. Without that
        // gate we'd loop on every save-driven refresh.
        if (sourceIsStale) {
            logLine(`auto-render: ${path.basename(activeFile)} newer than rendered PNGs — kicking fresh render`);
            setTimeout(() => { void refresh(true, activeFile, 'fast'); }, 0);
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
        if (err instanceof KotlinCompileError) {
            logLine(`FAILED — ${err.errors.length} Kotlin compile error(s) in ${err.task}`);
            // Reuse the same banner the LSP gate populates — the user
            // sees identical UX whether the gate fired or Gradle's
            // compile actually failed. Cards stay visible and dimmed so
            // the previous successful render is still on screen as a
            // reference while the error gets fixed.
            panel.postMessage({
                command: 'setCompileErrors',
                errors: err.errors,
            });
            panel.postMessage({ command: 'clearProgress' });
            compileGateActive = true;
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
        // Idempotent — happy-path tracker.finish() already flipped the
        // tracker's `finished` flag, so this is a no-op there. Failure /
        // cancellation / abort paths come through here too and need the
        // tick interval shut down so the bar doesn't keep emitting after
        // we've moved on.
        tracker.abort();
        if (pendingRefresh === abort) { pendingRefresh = null; }
    }
}

function handleWebviewMessage(msg: WebviewToExtension) {
    // Discriminated-union dispatch: inside each `case` TypeScript narrows
    // `msg` to the matching variant, so the field accesses below are
    // type-checked. Defensive runtime checks remain only where the
    // webview's untyped postMessage could deliver a structurally-broken
    // payload (e.g. arrays that aren't actually arrays).
    switch (msg.command) {
        case 'openFile':
            openPreviewSource(msg.className, msg.functionName);
            break;
        case 'selectModule':
            selectedModule = msg.value || null;
            sendModuleList();
            if (selectedModule) { refresh(false); }
            break;
        case 'refreshHeavy': {
            // Click on a faded heavy card opts it into full-tier renders for
            // this editor focus scope. Future saves keep that preview fresh;
            // changing focus clears the opt-in set.
            const mod = previewModuleMap.get(msg.previewId);
            if (mod) {
                optInHeavyRefresh(mod, msg.previewId);
                if (daemonGate?.isEnabled() && daemonScheduler) {
                    void daemonScheduler.renderNow(
                        mod,
                        [msg.previewId],
                        'full',
                        'heavy-opt-in',
                    );
                } else {
                    void refresh(true, currentScopeFile ?? undefined, 'full');
                }
            }
            break;
        }
        case 'viewportUpdated':
            // Daemon-only: route geometric visibility + scroll-ahead
            // predictions to the scheduler so it can `setVisible` and
            // queue speculative renders. When the daemon is disabled
            // (default) `notifyDaemonViewport` is a no-op. Array.isArray
            // is the one defensive check we keep — postMessage's typing
            // doesn't survive across the bridge so a buggy webview build
            // could send a string here.
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
            void openSourcePosition(msg.sourceFile, msg.line, msg.column);
            break;
        case 'openSourceFile':
            void openSourceByFileName(msg.fileName, msg.line, msg.className);
            break;
        case 'requestPreviewDiff':
            void runLivePreviewDiff(msg.previewId, msg.against);
            break;
        case 'requestLaunchOnDevice':
            if (msg.previewId) {
                void launchOnDevice(msg.previewId);
            }
            break;
        case 'setInteractive':
            void handleSetInteractive(msg.previewId, msg.enabled);
            break;
        case 'recordInteractiveInput':
            logLine(
                `[interactive] ${msg.kind} ${msg.previewId} px=${msg.pixelX},${msg.pixelY} `
                + `image=${msg.imageWidth}x${msg.imageHeight}`
                + (msg.scrollDeltaY != null ? ` deltaY=${msg.scrollDeltaY}` : ''),
            );
            void forwardInteractiveInput(msg);
            break;
        case 'setA11yOverlay':
            void handleSetA11yOverlay(msg.previewId, msg.enabled);
            break;
    }
}

/**
 * D2 — focus-mode a11y-overlay toggle. Resolves the focused preview's owning module, then
 * subscribes to `a11y/atf` + `a11y/hierarchy` (or unsubscribes) via the scheduler. When
 * disabling, also clears the cached payloads in the panel so the existing overlay tears down
 * even if the next render takes a beat. No-op when the preview's module isn't known yet
 * (panel rebuild race) or when the daemon scheduler isn't wired (Gradle-only mode).
 */
async function handleSetA11yOverlay(previewId: string, enabled: boolean): Promise<void> {
    if (!daemonScheduler) { return; }
    const moduleId = previewModuleMap.get(previewId);
    if (!moduleId) { return; }
    await daemonScheduler.setDataProductSubscription(
        moduleId,
        previewId,
        A11Y_OVERLAY_KINDS,
        enabled,
    );
    if (!enabled) {
        // Tear down the panel-side overlay caches immediately so the visual layer clears
        // without waiting on the next render. Deliberately does NOT touch the registry —
        // diagnostic squigglies sourced from the Gradle sidecar are independent of the
        // daemon overlay subscription and shouldn't disappear when the user hides the
        // visual overlay.
        panel?.postMessage({
            command: 'updateA11y',
            previewId,
            findings: [],
            nodes: [],
        });
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

/**
 * Resolve a stack-trace `fileName` (a basename like `Previews.kt` from
 * `StackTraceElement.fileName`) to an absolute path, then open it at
 * [line]. The JVM stack trace doesn't carry the absolute path of the
 * source file — only the basename — so we have to search.
 *
 * Two-pass resolution:
 *
 *  1. **Class-derived path** when [className] is supplied (the preview's
 *     compiled class FQN, e.g. `com.example.app.PreviewsKt`) AND the
 *     basename of that path matches [fileName]. Maps `com.example.app.
 *     PreviewsKt` → `com/example/app/Previews.kt`, then globs for that
 *     suffix. Disambiguates same-named files across modules: a click on
 *     a frame that's IN the preview's own class file lands on the right
 *     `Previews.kt` rather than the first one workspace-wide.
 *  2. **Basename glob** as fallback. The top app frame can be in a
 *     different file from the preview's class (cross-file throws —
 *     `Theme.kt` referenced from `Previews.kt`'s preview function), so
 *     a class-FQN-derived path won't match. Falls back to the same
 *     `**\/<fileName>` glob the resolver originally used.
 *
 * Silently does nothing when no match is found — the user sees no
 * editor change and a log line, since "open my Previews.kt" failing
 * isn't worth a toast.
 */
async function openSourceByFileName(
    fileName: string,
    line: number,
    className?: string,
): Promise<void> {
    if (!fileName) { return; }
    try {
        // Class-derived path. The Kotlin compiler emits one .class per
        // top-level Kotlin file, named `<File>Kt`; strip the trailing
        // "Kt" and convert package-dots to slashes to recover the
        // original `.kt` source path. Skip when the FQN's basename
        // doesn't match the stack-trace's basename — the throw isn't in
        // this preview's own file, so the class-derived path would point
        // at the wrong source.
        if (className) {
            const classFile = className.replace(/Kt$/, '').replace(/\./g, '/') + '.kt';
            if (path.posix.basename(classFile) === fileName) {
                const exact = await vscode.workspace.findFiles(`**/${classFile}`, '**/build/**', 1);
                if (exact.length > 0) {
                    await openAt(exact[0], line);
                    return;
                }
            }
        }
        // Basename fallback for cross-file throws.
        const matches = await vscode.workspace.findFiles(`**/${fileName}`, '**/build/**', 1);
        if (matches.length === 0) {
            logLine(`openSourceByFileName: no match for ${fileName}`);
            return;
        }
        await openAt(matches[0], line);
    } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        logLine(`openSourceByFileName failed for ${fileName}:${line} — ${message}`);
    }
}

/** Shared open-at-line helper — same shape as the inline body in
 *  openSourcePosition but takes a Uri instead of a string path. */
async function openAt(uri: vscode.Uri, line: number): Promise<void> {
    const doc = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(doc);
    const pos = new vscode.Position(Math.max(0, line - 1), 0);
    editor.selection = new vscode.Selection(pos, pos);
    editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
}

async function runLivePreviewDiff(
    previewId: string,
    against: 'head' | 'main',
): Promise<void> {
    if (!panel || !historySource) { return; }
    const moduleId = previewModuleMap.get(previewId);
    const manifest = moduleId ? moduleManifestCache.get(moduleId) : undefined;
    const info = manifest?.find(p => p.id === previewId);
    if (!moduleId || !info || !gradleService) {
        panel.postMessage({
            command: 'previewDiffError', previewId, against,
            message: 'Preview not found in the current scope.',
        });
        return;
    }
    const capture = info.captures?.[0];
    if (!capture?.renderOutput) {
        panel.postMessage({
            command: 'previewDiffError', previewId, against,
            message: 'No live render available for this preview.',
        });
        return;
    }
    const moduleDir = path.join(gradleService.workspaceRoot, moduleId);
    const livePath = path.join(moduleDir, capture.renderOutput);
    const liveBytes = await fs.promises.readFile(livePath).catch(() => null);
    if (!liveBytes) {
        panel.postMessage({
            command: 'previewDiffError', previewId, against,
            message: 'Live render not on disk yet — save the file once first.',
        });
        return;
    }

    const projectDir = path.join(gradleService.workspaceRoot, moduleId);
    const scope: HistoryScope = { moduleId, projectDir, previewId };
    let entries: unknown[];
    try {
        const result = await historySource.list(scope);
        entries = result.entries ?? [];
    } catch (err) {
        panel.postMessage({
            command: 'previewDiffError', previewId, against,
            message: `History unavailable: ${(err as Error).message}`,
        });
        return;
    }
    const filtered = (against === 'main')
        ? entries.filter(e => {
            const branch = (e as { git?: { branch?: string } }).git?.branch;
            return branch === 'main';
        })
        : entries;
    const sorted = [...filtered].sort((a, b) => {
        const at = (a as { timestamp?: string }).timestamp ?? '';
        const bt = (b as { timestamp?: string }).timestamp ?? '';
        return bt.localeCompare(at);
    });
    const target = sorted[0] as { id?: string; timestamp?: string } | undefined;
    if (target?.id) {
        const right = await historySource.read(target.id);
        if (!right) {
            panel.postMessage({
                command: 'previewDiffError', previewId, against,
                message: 'Comparison entry not readable.',
            });
            return;
        }
        const rightBytes = right.pngBytes
            ?? (await fs.promises.readFile(right.pngPath)).toString('base64');
        panel.postMessage({
            command: 'previewDiffReady',
            previewId,
            against,
            leftLabel: 'Live · now',
            leftImage: liveBytes.toString('base64'),
            rightLabel: `${against === 'main' ? 'main' : 'HEAD'} · ${formatRelativeShort(target.timestamp)}`,
            rightImage: rightBytes,
        });
        return;
    }
    // No local archived entry. For "vs main" we can still try the
    // compose-preview/main baselines branch (with legacy preview_main
    // fallback) — repos using the CI baseline workflow publish every main
    // build's PNGs there. Avoids requiring a daemon or a one-off local
    // archive for the user's first diff against main.
    if (against === 'main') {
        const baseline = await readPreviewMainPng(
            gradleService.workspaceRoot, moduleId, previewId,
        );
        if (baseline) {
            panel.postMessage({
                command: 'previewDiffReady',
                previewId,
                against,
                leftLabel: 'Live · now',
                leftImage: liveBytes.toString('base64'),
                rightLabel: `main · ${baseline.ref}`,
                rightImage: baseline.png.toString('base64'),
            });
            return;
        }
    }
    panel.postMessage({
        command: 'previewDiffError', previewId, against,
        message: against === 'main'
            ? 'No archived render on main yet for this preview, and no compose-preview/main baseline.'
            : 'No archived history yet for this preview.',
    });
}

/**
 * Walk every preview discovered in the currently scoped module's manifest,
 * diff its live render against the latest archived render on `main` for
 * the same preview, and surface the drifted ones in a quick-pick. Picking
 * one focuses the live panel on that preview and triggers a Diff vs main
 * so the user lands directly in the result.
 *
 * Hash-only check — answers "did anything change?" not "by how much".
 * The opened diff result already shows pixel-stats for the selected one;
 * scaling that up to per-preview stats is N PNG decodes which we skip
 * for the dashboard until performance demands it.
 */
/**
 * Builds and installs the consumer module's debug APK and renders the
 * targeted `@Preview` composable on a connected Android device through
 * `androidx.compose.ui.tooling.PreviewActivity` — the same activity
 * Android Studio drives for "Deploy Preview to Device". The activity
 * ships in `androidx.compose.ui:ui-tooling` (typically pulled in as
 * `debugImplementation`) and reflects on the FQN passed via the
 * `composable` extra to render that one composable on screen.
 *
 * Wired up to the "Launch on Device" panel button (focus-mode toolbar)
 * and the `composePreview.launchOnDevice` command (view title bar). The
 * extension picks the focused preview, falls back to a quick-pick over
 * the active scope's previews when none is focused, and only considers
 * Android-application modules that apply the preview plugin (CMP shared,
 * libraries, and Desktop don't have `installDebug`, so they're filtered
 * out before resolution falls through).
 *
 * `@PreviewParameter` deep-link extras (`parameterProviderClassName` /
 * `parameterProviderIndex`) are forwarded too, so a parameterised preview
 * lands on the right provider value rather than the first one.
 */
async function launchOnDevice(focusedPreviewId?: string): Promise<void> {
    if (!gradleService) {
        vscode.window.showInformationMessage('Compose Preview is not ready yet.');
        return;
    }
    const previewModules = gradleService.findPreviewModules();
    const candidates = collectAndroidApplicationModules(
        gradleService.workspaceRoot, previewModules,
    );
    if (candidates.length === 0) {
        vscode.window.showInformationMessage(
            'Compose Preview: no Android application module to launch. '
            + 'Apply id("com.android.application") with an applicationId to a preview module.',
        );
        return;
    }

    const preview = await resolveLaunchPreview(focusedPreviewId, candidates);
    if (!preview) { return; }
    const { module, applicationId, info } = preview;
    const composableFqn = `${info.className}.${info.functionName}`;
    logLine(`launchOnDevice: ${module} (${applicationId}) → ${composableFqn}`);

    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `Compose Preview: launching ${info.functionName} on device`,
            cancellable: false,
        },
        async (progress) => {
            progress.report({ message: 'Building & installing (installDebug)…' });
            try {
                await gradleService!.installDebug(module);
            } catch (e: unknown) {
                if (e instanceof TaskCancelledError) { return; }
                const m = e instanceof Error ? e.message : String(e);
                vscode.window.showErrorMessage(
                    `Compose Preview: installDebug failed — ${m}`,
                );
                return;
            }

            progress.report({ message: 'Starting PreviewActivity (adb)…' });
            const sdkRoot = findAndroidSdkRoot(gradleService!.workspaceRoot);
            const adbPath = resolveAdbPath(sdkRoot);
            const args = buildPreviewActivityAmStartArgs({
                applicationId,
                composableFqn,
                parameterProviderClassName: info.params.previewParameterProviderClassName ?? null,
            });
            try {
                const result = await runAdb(adbPath, args);
                if (result.code !== 0) {
                    const detail = (result.stderr || result.stdout || '').trim();
                    const hint = /Activity class .* does not exist|ClassNotFoundException/i.test(detail)
                        ? ' — add `debugImplementation("androidx.compose.ui:ui-tooling")` so PreviewActivity is on the device.'
                        : '';
                    vscode.window.showErrorMessage(
                        `Compose Preview: adb failed to start ${applicationId}` +
                        (detail ? ` — ${detail}` : '') + hint,
                    );
                    return;
                }
            } catch (e: unknown) {
                const m = e instanceof Error ? e.message : String(e);
                vscode.window.showErrorMessage(
                    `Compose Preview: could not run adb (${adbPath}) — ${m}. ` +
                    'Set ANDROID_HOME or sdk.dir in local.properties.',
                );
                return;
            }
            vscode.window.showInformationMessage(
                `Compose Preview: launched ${info.functionName} on device.`,
            );
        },
    );
}

interface ResolvedLaunchPreview {
    module: string;
    applicationId: string;
    info: PreviewInfo;
}

/**
 * Resolve which preview to deploy. Order:
 *
 *   1. If [focusedPreviewId] is supplied (focus-mode button), use that
 *      directly when its owning module is an Android application.
 *   2. Otherwise, gather every preview owned by an Android-application
 *      module and let the user pick from a quick-pick. If only one
 *      candidate exists, skip the quick-pick.
 *
 * Returns `null` when the user cancels or nothing is launchable, after
 * surfacing an appropriate message in either case.
 */
async function resolveLaunchPreview(
    focusedPreviewId: string | undefined,
    candidates: Array<{ module: string; applicationId: string }>,
): Promise<ResolvedLaunchPreview | null> {
    const candidateByModule = new Map(candidates.map(c => [c.module, c]));

    if (focusedPreviewId) {
        const owner = previewModuleMap.get(focusedPreviewId);
        const candidate = owner ? candidateByModule.get(owner) : undefined;
        const previews = owner ? moduleManifestCache.get(owner) : undefined;
        const info = previews?.find(p => p.id === focusedPreviewId);
        if (candidate && info) {
            return { module: candidate.module, applicationId: candidate.applicationId, info };
        }
        if (owner && !candidate) {
            vscode.window.showInformationMessage(
                `Compose Preview: ${owner} is not an Android application module — `
                + 'add id("com.android.application") to deploy a preview from it.',
            );
            return null;
        }
    }

    interface PreviewPickItem extends vscode.QuickPickItem {
        candidate: { module: string; applicationId: string };
        info: PreviewInfo;
    }
    const items: PreviewPickItem[] = [];
    for (const candidate of candidates) {
        const previews = moduleManifestCache.get(candidate.module) ?? [];
        for (const info of previews) {
            items.push({
                label: info.functionName,
                description: candidate.applicationId,
                detail: `${candidate.module} · ${info.className}`,
                candidate,
                info,
            });
        }
    }
    if (items.length === 0) {
        vscode.window.showInformationMessage(
            'Compose Preview: no rendered previews yet — render once first '
            + 'so the @Preview functions show up here.',
        );
        return null;
    }
    if (items.length === 1) {
        const only = items[0];
        return { module: only.candidate.module, applicationId: only.candidate.applicationId, info: only.info };
    }
    const picked = await vscode.window.showQuickPick(items, {
        placeHolder: 'Pick a @Preview to launch on device',
        matchOnDescription: true,
        matchOnDetail: true,
    });
    if (!picked) { return null; }
    return { module: picked.candidate.module, applicationId: picked.candidate.applicationId, info: picked.info };
}

async function diffAllVsMain(): Promise<void> {
    if (!gradleService || !panel || !historySource) {
        vscode.window.showInformationMessage('Compose Preview is not ready yet.');
        return;
    }
    if (!currentScopeFile) {
        vscode.window.showInformationMessage(
            'Open a Kotlin file with @Preview functions before running Diff All vs Main.',
        );
        return;
    }
    const moduleId = gradleService.resolveModule(currentScopeFile);
    if (!moduleId) {
        vscode.window.showInformationMessage('Active file is not part of a Compose Preview module.');
        return;
    }
    const manifest = moduleManifestCache.get(moduleId);
    if (!manifest || manifest.length === 0) {
        vscode.window.showInformationMessage('No previews discovered yet — render once first.');
        return;
    }
    const projectDir = path.join(gradleService.workspaceRoot, moduleId);

    interface DriftItem extends vscode.QuickPickItem {
        previewId: string;
        driftKind: 'differs' | 'no-baseline' | 'no-live';
    }
    const drifted: DriftItem[] = [];
    let identical = 0;

    await vscode.window.withProgress({
        location: vscode.ProgressLocation.Notification,
        title: 'Compose Preview — diffing all vs main',
        cancellable: false,
    }, async (progress) => {
        const total = manifest.length;
        let done = 0;
        for (const preview of manifest) {
            done++;
            progress.report({
                message: `${done} / ${total} · ${preview.functionName}`,
                increment: 100 / total,
            });
            const cap = preview.captures?.[0];
            if (!cap?.renderOutput) { continue; }
            const livePath = path.join(projectDir, cap.renderOutput);
            const liveBytes = await fs.promises.readFile(livePath).catch(() => null);
            const label = preview.functionName
                + (preview.params.name ? ` — ${preview.params.name}` : '');
            if (!liveBytes) {
                drifted.push({
                    label,
                    description: 'no live render',
                    detail: preview.id,
                    previewId: preview.id,
                    driftKind: 'no-live',
                });
                continue;
            }
            const liveHash = crypto.createHash('sha256').update(liveBytes).digest('hex');
            let mainHash: string | null = null;
            let mainTs = '';
            try {
                const list = await historySource!.list({
                    moduleId, projectDir, previewId: preview.id,
                });
                for (const e of list.entries) {
                    const entry = e as { git?: { branch?: string }; pngHash?: string; timestamp?: string };
                    if (entry?.git?.branch !== 'main' || !entry.pngHash) { continue; }
                    const ts = entry.timestamp ?? '';
                    if (ts > mainTs) { mainTs = ts; mainHash = entry.pngHash; }
                }
            } catch {
                // History unavailable for this preview — treat as no baseline.
            }
            if (!mainHash) {
                // Fall back to the compose-preview/main baselines branch
                // (with legacy preview_main fallback) — repos using the CI
                // baseline workflow publish PNGs there even when nothing
                // has been archived locally yet. The lookup also exposes a
                // sha256 in the manifest, which we compare against the
                // live hash to skip identical previews.
                const baseline = await readPreviewMainPng(
                    gradleService!.workspaceRoot, moduleId, preview.id,
                );
                if (!baseline) {
                    drifted.push({
                        label,
                        description: 'no baseline on main',
                        detail: preview.id,
                        previewId: preview.id,
                        driftKind: 'no-baseline',
                    });
                    continue;
                }
                const baselineHash = baseline.sha256
                    ?? crypto.createHash('sha256').update(baseline.png).digest('hex');
                if (liveHash === baselineHash) {
                    identical++;
                    continue;
                }
                drifted.push({
                    label,
                    description: 'differs from main',
                    detail: preview.id,
                    previewId: preview.id,
                    driftKind: 'differs',
                });
                continue;
            }
            if (liveHash === mainHash) {
                identical++;
                continue;
            }
            drifted.push({
                label,
                description: 'differs from main',
                detail: preview.id,
                previewId: preview.id,
                driftKind: 'differs',
            });
        }
    });

    if (drifted.length === 0) {
        vscode.window.showInformationMessage(
            `All ${identical} preview${identical === 1 ? '' : 's'} match main.`,
        );
        return;
    }

    const driftCount = drifted.filter(i => i.driftKind === 'differs').length;
    const placeholder = driftCount > 0
        ? `${driftCount} preview${driftCount === 1 ? '' : 's'} differ from main · ${identical} identical`
        : `${drifted.length} preview${drifted.length === 1 ? '' : 's'} need attention · ${identical} identical`;
    const picked = await vscode.window.showQuickPick(drifted, {
        title: `Compose Preview — drift vs main (${moduleId})`,
        placeHolder: placeholder,
        matchOnDescription: true,
    });
    if (!picked) { return; }

    await vscode.commands.executeCommand(`${PreviewPanel.viewId}.focus`);
    panel.postMessage({
        command: 'focusAndDiff',
        previewId: picked.previewId,
        against: 'main',
    });
}

function formatRelativeShort(iso: string | undefined): string {
    if (!iso) { return '(unknown)'; }
    const t = Date.parse(iso);
    if (isNaN(t)) { return iso; }
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 60) { return s + 's ago'; }
    const m = Math.round(s / 60);
    if (m < 60) { return m + 'm ago'; }
    const h = Math.round(m / 60);
    if (h < 24) { return h + 'h ago'; }
    const d = Math.round(h / 24);
    return d + 'd ago';
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

/**
 * Live-stream (interactive) mode entry/exit handler. See
 * docs/daemon/INTERACTIVE.md § 4 for the full lifecycle. Calls
 * `interactive/start` on the daemon to register the previewId as the
 * priority target and stash the returned `frameStreamId` for click
 * forwarding via [activeInteractiveStreams]. Exit calls `interactive/stop`
 * (notification, drop-and-go) and clears the cached stream id.
 *
 * On daemons with held interactive sessions, `interactive/start` drives
 * the live frame loop itself. Older v1 fallback daemons still receive the
 * original setFocus + renderNow request so cards get a fresh first frame.
 */
async function handleSetInteractive(previewId: string, enabled: boolean): Promise<void> {
    if (!daemonGate?.isEnabled() || !daemonScheduler) { return; }
    const moduleId = previewModuleMap.get(previewId);
    if (!moduleId) {
        logLine(`[interactive] no module for ${previewId}; ignoring setInteractive`);
        return;
    }
    if (!enabled) {
        const stream = activeInteractiveStreams.get(previewId);
        if (stream) {
            activeInteractiveStreams.delete(previewId);
            updateInteractiveStatus();
            const client = await daemonGate?.getOrSpawn(
                moduleId, daemonScheduler.daemonEvents(moduleId),
            );
            client?.interactiveStop({ frameStreamId: stream });
        }
        logLine(`[interactive] live mode off for ${previewId}`);
        return;
    }
    const client = await daemonGate?.getOrSpawn(
        moduleId, daemonScheduler.daemonEvents(moduleId),
    );
    if (!client) {
        // The webview already saw the toggle disabled — but the user could
        // have flipped settings between toggle render and click. Push a
        // fresh availability ping so the chip reverts cleanly.
        panel?.postMessage({
            command: 'setInteractiveAvailability',
            moduleId,
            ready: false,
            interactiveSupported: false,
        });
        return;
    }
    try {
        const result = await client.interactiveStart({ previewId });
        activeInteractiveStreams.set(previewId, result.frameStreamId);
        updateInteractiveStatus();
        const interactiveSupported = daemonGate?.isInteractiveSupported(moduleId) ?? false;
        logLine(
            `[interactive] live mode on for ${previewId} (module=${moduleId}, ` +
            `streamId=${result.frameStreamId}, ` +
            `v2=${interactiveSupported})`,
        );
        if (interactiveSupported) {
            await daemonScheduler.setFocus(moduleId, [previewId]);
            return;
        }
    } catch (err) {
        // MethodNotFound on a daemon that predates the interactive RPC — fall through
        // to the focus + renderNow path so the card still gets a fresh first frame.
        logLine(
            `[interactive] interactive/start unsupported for ${moduleId}: ` +
            `${(err as Error).message}; falling back to setFocus + renderNow`,
        );
    }
    await daemonScheduler.setFocus(moduleId, [previewId]);
    await daemonScheduler.renderNow(moduleId, [previewId], 'fast', 'interactive-on');
}

/**
 * previewId → frameStreamId for currently-active interactive streams. Populated by
 * [handleSetInteractive] on enter, cleared on exit. Click forwarding consults this
 * to look up the stream id the daemon expects on `interactive/input` notifications;
 * a missing entry means "interactive/start either wasn't issued, was rejected
 * (MethodNotFound), or has already been stopped" — drop the click.
 */
const activeInteractiveStreams = new Map<string, string>();

/**
 * Update the v1-fallback status-bar hint (#425). Visible only when at least one active
 * interactive stream is on a daemon that doesn't advertise
 * `InitializeResult.capabilities.interactive`. Hidden when v2 is supported on every
 * stream's host (the panel's LIVE chip is sufficient feedback) and when no streams are
 * active.
 *
 * Called from every site that mutates `activeInteractiveStreams` plus the channel-close
 * cleanup, so the indicator reflects the current state without polling.
 */
function updateInteractiveStatus(): void {
    if (!interactiveStatusItem) { return; }
    if (!daemonGate || activeInteractiveStreams.size === 0) {
        interactiveStatusItem.hide();
        return;
    }
    const unsupportedModules = new Set<string>();
    for (const previewId of activeInteractiveStreams.keys()) {
        const moduleId = previewModuleMap.get(previewId);
        if (!moduleId) { continue; }
        if (!daemonGate.isInteractiveSupported(moduleId)) {
            unsupportedModules.add(moduleId);
        }
    }
    if (unsupportedModules.size === 0) {
        interactiveStatusItem.hide();
        return;
    }
    const moduleLabel = unsupportedModules.size === 1
        ? [...unsupportedModules][0]
        : `${unsupportedModules.size} modules`;
    interactiveStatusItem.text = `$(warning) Live · v1 fallback`;
    interactiveStatusItem.tooltip = (
        `Live mode is active on ${moduleLabel}, but this daemon backend doesn't ` +
        `support clicks-into-composition (v2). Inputs trigger a fresh render but ` +
        `state from \`remember { mutableStateOf(...) }\` resets between clicks. ` +
        `Switch to a desktop module for full interactive mode. ` +
        `See docs/daemon/INTERACTIVE.md § 9.10.`
    );
    interactiveStatusItem.show();
}

/**
 * Forward panel-side input on a live preview to the daemon's `interactive/input`
 * notification. Drops silently when the previewId isn't currently in interactive mode
 * — callers don't need to coordinate with the start/stop dance.
 */
async function forwardInteractiveInput(
    input: Extract<WebviewToExtension, { command: 'recordInteractiveInput' }>,
): Promise<void> {
    if (!daemonGate?.isEnabled() || !daemonScheduler) { return; }
    const previewId = input.previewId;
    const streamId = activeInteractiveStreams.get(previewId);
    if (!streamId) { return; }
    const moduleId = previewModuleMap.get(previewId);
    if (!moduleId) { return; }
    const client = await daemonGate?.getOrSpawn(
        moduleId, daemonScheduler.daemonEvents(moduleId),
    );
    if (!client) { return; }
    client.interactiveInput({
        frameStreamId: streamId,
        kind: input.kind,
        pixelX: input.pixelX,
        pixelY: input.pixelY,
        scrollDeltaY: input.scrollDeltaY,
    });
}

/**
 * Push the daemon-readiness state for [moduleId] to the live panel so the
 * focus-mode LIVE toggle can enable/disable itself. Cheap; no-op when the
 * panel isn't open. Called from every code path that flips daemon state
 * for a module — warm-up completion, channel-close, classpath-dirty.
 */
function publishInteractiveAvailability(moduleId: string): void {
    if (!panel) { return; }
    const ready = daemonGate?.isDaemonReady(moduleId) ?? false;
    panel.postMessage({
        command: 'setInteractiveAvailability',
        moduleId,
        ready,
        interactiveSupported: ready && (daemonGate?.isInteractiveSupported(moduleId) ?? false),
    });
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
