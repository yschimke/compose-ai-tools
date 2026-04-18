import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { GradleService, GradleApi } from './gradleService';
import { PreviewPanel } from './previewPanel';
import { PreviewRegistry } from './previewRegistry';
import { PreviewGutterDecorations } from './previewGutterDecorations';
import { PreviewHoverProvider } from './previewHoverProvider';
import { PreviewCodeLensProvider } from './previewCodeLensProvider';
import { PreviewA11yDiagnostics } from './previewA11yDiagnostics';
import { PreviewDoctorDiagnostics } from './previewDoctorDiagnostics';
import { packageQualifiedSourcePath } from './sourcePath';
import { PreviewInfo } from './types';

const DEBOUNCE_MS = 1500;
// Edits to the currently-scoped preview file (e.g. Claude Code's Edit tool
// writing to Previews.kt) are nearly always a single discrete event, not a
// burst — cut the wait so the refresh feels responsive. The refreshInFlight
// gate still protects against stacking builds if something happens faster.
const SCOPE_DEBOUNCE_MS = 300;
const INIT_DELAY_MS = 1000;

let gradleService: GradleService | null = null;
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
/** previewId → module, updated on every refresh. Used by history commands. */
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
/** Captured in activate() so notification helpers can reach workspaceState. */
let extensionContext: vscode.ExtensionContext | null = null;
/** Guard against firing the "plugin not applied" notification more than once
 *  per session — users shouldn't see it on every refresh tick. */
let warnedMissingPluginThisSession = false;
// Decide whether a file "probably wants previews" with the plugin off. Kept
// deliberately loose: the file just needs to contain the substring "Preview"
// (covers `@Preview`, `@PreviewLightDark`, preview-tooling imports, and
// custom-multipreview definition sites) and be a Composable file at all.
// False positives are cheap — an extra informational message. False
// negatives (silence when the user wanted a nudge) are the worse outcome.
const SETUP_DOCS_URL = 'https://github.com/yschimke/compose-ai-tools/tree/main/vscode-extension#readme';

export async function activate(context: vscode.ExtensionContext) {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) { return; }

    extensionContext = context;
    const workspaceRoot = workspaceFolders[0].uri.fsPath;
    const outputChannel = vscode.window.createOutputChannel('Compose Preview');
    context.subscriptions.push(outputChannel);

    // vscjava.vscode-gradle is declared as an extensionDependency, so it's
    // guaranteed to be installed. Activate it and get its API.
    const gradleExt = vscode.extensions.getExtension('vscjava.vscode-gradle');
    if (!gradleExt) {
        vscode.window.showErrorMessage(
            'Compose Preview requires the "Gradle for Java" extension (vscjava.vscode-gradle).',
        );
        return;
    }
    const gradleApi = (await gradleExt.activate()) as GradleApi;

    gradleService = new GradleService(workspaceRoot, gradleApi, outputChannel);

    panel = new PreviewPanel(context.extensionUri, handleWebviewMessage);
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(PreviewPanel.viewId, panel),
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
    );

    const detectLog = (msg: string) => outputChannel.appendLine(`[detect] ${msg}`);
    const gutterDecorations = new PreviewGutterDecorations(context.extensionUri, registry, detectLog);
    const hoverProvider = new PreviewHoverProvider(registry, detectLog);
    const codeLensProvider = new PreviewCodeLensProvider(registry, detectLog);
    const a11yDiagnostics = new PreviewA11yDiagnostics(registry, detectLog);
    const doctorDiagnostics = new PreviewDoctorDiagnostics(
        gradleService,
        workspaceRoot,
        (msg) => outputChannel.appendLine(`[doctor] ${msg}`),
    );
    const kotlinFiles: vscode.DocumentSelector = { language: 'kotlin', scheme: 'file' };
    context.subscriptions.push(
        vscode.languages.registerHoverProvider(kotlinFiles, hoverProvider),
        vscode.languages.registerCodeLensProvider(kotlinFiles, codeLensProvider),
        codeLensProvider,
        gutterDecorations,
        a11yDiagnostics,
        doctorDiagnostics,
        { dispose: () => registry.dispose() },
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
    void refreshDoctor();

    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(editor => {
            if (editor?.document.languageId !== 'kotlin') { return; }
            const filePath = editor.document.uri.fsPath;
            // Focus toggling (editor ↔ webview/terminal ↔ back) fires this
            // event with the same Kotlin file. Re-running refresh there just
            // cancels any in-flight render, flashes spinners, and burns a
            // Gradle invocation — all for a no-op.
            if (filePath === currentScopeFile) { return; }
            refresh(false, filePath);
        }),
    );

    // Editor saves (Ctrl+S, auto-save). The first save of a given file since
    // activation refreshes immediately so the user sees their change right
    // away; subsequent saves coalesce through a debounced + in-flight-aware
    // queue so we never stack builds on top of each other.
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument(doc => {
            if (!isSourceFile(doc.uri.fsPath)) { return; }
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

    setTimeout(() => {
        const active = vscode.window.activeTextEditor;
        if (active?.document.languageId === 'kotlin') {
            refresh(false, active.document.uri.fsPath);
        } else {
            // No Kotlin file in focus — let refresh() emit the empty-state
            // message without trying to load anything.
            refresh(false);
        }
    }, INIT_DELAY_MS);
}

export function deactivate() {
    if (debounceTimer) { clearTimeout(debounceTimer); }
    pendingRefresh?.abort();
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
 *   3. The first visible Kotlin editor (covers focus-on-webview, Log,
 *      Problems pane, etc. — activeTextEditor is undefined/non-Kotlin then).
 *   4. The last file a refresh successfully scoped to, so transient focus
 *      changes don't unscope the panel.
 * Returns the resolved path and a short tag describing which fallback was used,
 * for logging.
 */
function resolveScopeFile(forFilePath?: string): { file?: string; source: string } {
    if (forFilePath) { return { file: forFilePath, source: 'caller' }; }

    const active = vscode.window.activeTextEditor?.document;
    if (active && active.languageId === 'kotlin' && isPreviewSourceFile(active.uri.fsPath)) {
        return { file: active.uri.fsPath, source: 'active' };
    }

    for (const editor of vscode.window.visibleTextEditors) {
        const doc = editor.document;
        if (doc.languageId === 'kotlin' && isPreviewSourceFile(doc.uri.fsPath)) {
            return { file: doc.uri.fsPath, source: 'visible' };
        }
    }

    if (currentScopeFile && isPreviewSourceFile(currentScopeFile)) {
        return { file: currentScopeFile, source: 'sticky' };
    }

    return { source: 'none' };
}

function invalidateModuleCache(filePath: string): void {
    if (!gradleService) { return; }
    const module = gradleService.resolveModule(filePath);
    if (module) { gradleService.invalidateCache(module); }
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
 *  during the run, re-applying the debounce-elapsed check. */
async function runRefreshExclusive(filePath: string): Promise<void> {
    refreshInFlight = true;
    try {
        await refresh(true, filePath);
    } finally {
        refreshInFlight = false;
        maybeFirePendingRefresh();
    }
}

function sendModuleList() {
    if (!gradleService || !panel) { return; }
    const modules = gradleService.findPreviewModules();
    panel.postMessage({ command: 'setModules', modules, selected: selectedModule || '' });
}

/**
 * Main refresh entry point.
 * @param forceRender  If true, runs renderAllPreviews (not just discover).
 * @param forFilePath  If set, scopes to the module owning this file.
 */
async function refresh(forceRender: boolean, forFilePath?: string) {
    if (!gradleService || !panel) { return; }

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
    const { file: activeFile } = resolveScopeFile(forFilePath);
    const module = activeFile && isPreviewSourceFile(activeFile)
        ? gradleService.resolveModule(activeFile)
        : null;
    if (!activeFile || !module) {
        panel.postMessage({ command: 'clearAll' });
        panel.postMessage({ command: 'showMessage', text: emptyStateMessage(activeFile) });
        lastLoadedModules = [];
        hasPreviewsLoaded = false;
        currentScopeFile = null;
        if (activeFile && isPreviewSourceFile(activeFile)) {
            maybeShowSetupPrompt(activeFile);
        }
        return;
    }

    currentScopeFile = activeFile;
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

    try {
        const allPreviews: PreviewInfo[] = [];
        previewModuleMap.clear();

        for (const mod of modules) {
            if (abort.signal.aborted) { return; }

            const manifest = forceRender
                ? await gradleService.renderPreviews(mod)
                : await gradleService.discoverPreviews(mod);

            const perModule: PreviewInfo[] = [];
            if (manifest) {
                for (const p of manifest.previews) {
                    p.hasHistory = gradleService.listHistory(mod, p.id).length > 0;
                    allPreviews.push(p);
                    previewModuleMap.set(p.id, mod);
                    perModule.push(p);
                }
            }
            registry.replaceModule(mod, perModule);
        }

        if (abort.signal.aborted) { return; }

        if (allPreviews.length === 0) {
            panel.postMessage({ command: 'showMessage', text: 'No @Preview functions found' });
            return;
        }

        // Scope strictly to the active file. If the file has no @Preview
        // functions, the panel shows an empty state — never the whole module.
        const visiblePreviews = allPreviews.filter(p => p.sourceFile === filterFile);

        panel.postMessage({
            command: 'setPreviews',
            previews: visiblePreviews,
            moduleDir: modules.join(','),
        });
        hasPreviewsLoaded = true;

        // Load images asynchronously
        for (const preview of visiblePreviews) {
            if (abort.signal.aborted) { return; }
            if (!preview.renderOutput) { continue; }

            const mod = previewModuleMap.get(preview.id);
            if (!mod) { continue; }
            const imageData = await gradleService.readPreviewImage(mod, preview.renderOutput);
            if (abort.signal.aborted) { return; }

            if (imageData) {
                registry.setImage(preview.id, imageData);
                panel.postMessage({ command: 'updateImage', previewId: preview.id, imageData });
            } else if (forceRender) {
                // Render task completed but produced no PNG for this preview —
                // a per-preview failure that didn't fail the whole task (e.g.
                // one parameterized Robolectric test threw). Surface it on the
                // card; the root-cause log is in Output ▸ Compose Preview.
                panel.postMessage({
                    command: 'setImageError',
                    previewId: preview.id,
                    message: 'Render failed — see Output ▸ Compose Preview',
                });
            }
            // else: discover-only pass, PNG not produced yet. Leave the skeleton
            // in place; the next save-triggered render will populate it.
        }

        panel.postMessage({ command: 'showMessage', text: '' });
    } catch (err: unknown) {
        if (abort.signal.aborted) { return; }
        const message = err instanceof Error ? err.message.slice(0, 300) : 'Build failed';
        panel.postMessage({
            command: 'showMessage',
            text: message,
        });
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
        case 'showHistory':
            if (msg.previewId) { sendHistoryList(msg.previewId); }
            break;
        case 'loadHistoryImage':
            if (msg.previewId && msg.filename) {
                sendHistoryImage(msg.previewId, msg.filename);
            }
            break;
    }
}

// Loose type for incoming webview messages (validated per-case above)
interface WebviewToExtensionMessage {
    command: string;
    className?: string;
    functionName?: string;
    value?: string;
    previewId?: string;
    filename?: string;
}

function sendHistoryList(previewId: string) {
    if (!gradleService || !panel) { return; }
    const mod = previewModuleMap.get(previewId);
    if (!mod) { return; }
    const entries = gradleService.listHistory(mod, previewId);
    panel.postMessage({ command: 'setHistory', previewId, entries });
}

async function sendHistoryImage(previewId: string, filename: string) {
    if (!gradleService || !panel) { return; }
    const mod = previewModuleMap.get(previewId);
    if (!mod) { return; }
    const imageData = await gradleService.readHistoryImage(mod, previewId, filename);
    if (imageData) {
        panel.postMessage({ command: 'updateHistoryImage', previewId, filename, imageData });
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
