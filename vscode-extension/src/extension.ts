import * as vscode from 'vscode';
import * as path from 'path';
import { GradleService, GradleApi } from './gradleService';
import { PreviewPanel } from './previewPanel';
import { PreviewRegistry } from './previewRegistry';
import { PreviewCodeLensProvider } from './previewCodeLensProvider';
import { PreviewHoverProvider } from './previewHoverProvider';
import { PreviewInfo } from './types';

const DEBOUNCE_MS = 1500;
const INIT_DELAY_MS = 1000;

let gradleService: GradleService | null = null;
let panel: PreviewPanel | null = null;
let debounceTimer: NodeJS.Timeout | null = null;
let selectedModule: string | null = null;
let pendingRefresh: AbortController | null = null;
let hasPreviewsLoaded = false;
let lastLoadedModules: string[] = [];
const registry = new PreviewRegistry();
/** previewId → module, updated on every refresh. Used by history commands. */
const previewModuleMap = new Map<string, string>();

export async function activate(context: vscode.ExtensionContext) {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) { return; }

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
        vscode.commands.registerCommand('composePreview.refresh', () => refresh(true)),
        vscode.commands.registerCommand('composePreview.renderAll', () => refresh(true)),
        vscode.commands.registerCommand('composePreview.runForFile', (filePath?: string) => {
            const target = filePath ?? vscode.window.activeTextEditor?.document.uri.fsPath;
            if (target) { refresh(true, target); }
        }),
    );

    const detectLog = (msg: string) => outputChannel.appendLine(`[detect] ${msg}`);
    const codeLensProvider = new PreviewCodeLensProvider(registry, detectLog);
    const hoverProvider = new PreviewHoverProvider(registry, detectLog);
    const kotlinFiles: vscode.DocumentSelector = { language: 'kotlin', scheme: 'file' };
    context.subscriptions.push(
        vscode.languages.registerCodeLensProvider(kotlinFiles, codeLensProvider),
        vscode.languages.registerHoverProvider(kotlinFiles, hoverProvider),
        codeLensProvider,
        { dispose: () => registry.dispose() },
    );

    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(editor => {
            if (editor?.document.languageId === 'kotlin') {
                refresh(false, editor.document.uri.fsPath);
            }
        }),
    );

    // Editor saves (Ctrl+S, auto-save)
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument(doc => {
            if (isSourceFile(doc.uri.fsPath)) {
                debouncedRefresh(doc.uri.fsPath);
            }
        }),
    );

    // External file system changes (git, refactor tools)
    for (const glob of ['**/*.kt', '**/res/**/*.xml']) {
        const watcher = vscode.workspace.createFileSystemWatcher(glob);
        watcher.onDidChange(uri => debouncedRefresh(uri.fsPath));
        watcher.onDidCreate(uri => debouncedRefresh(uri.fsPath));
        watcher.onDidDelete(uri => debouncedRefresh(uri.fsPath));
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

function debouncedRefresh(filePath: string) {
    const cfg = vscode.workspace.getConfiguration('composePreview');
    if (!cfg.get<boolean>('autoRefresh', true)) { return; }
    const render = cfg.get<boolean>('renderOnSave', false);

    // Invalidate cache for the changed module so the next discover isn't stale
    if (gradleService) {
        const module = gradleService.resolveModule(filePath);
        if (module) { gradleService.invalidateCache(module); }
    }

    if (debounceTimer) { clearTimeout(debounceTimer); }
    debounceTimer = setTimeout(() => {
        if (filePath.endsWith('.kt')) {
            refresh(render, filePath);
        } else {
            const active = vscode.window.activeTextEditor;
            if (active?.document.languageId === 'kotlin') {
                refresh(render, active.document.uri.fsPath);
            }
        }
    }, DEBOUNCE_MS);
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

    // The panel is always scoped to the active Kotlin source file's module.
    // Anything else (build scripts, non-Kotlin files, no editor) → blank.
    const activeFile = forFilePath ?? vscode.window.activeTextEditor?.document.uri.fsPath;
    const module = activeFile && isPreviewSourceFile(activeFile)
        ? gradleService.resolveModule(activeFile)
        : null;
    if (!module) {
        panel.postMessage({ command: 'clearAll' });
        panel.postMessage({
            command: 'showMessage',
            text: 'Open a Kotlin source file in a module that applies ee.schimke.composeai.preview.',
        });
        lastLoadedModules = [];
        hasPreviewsLoaded = false;
        return;
    }

    const modules = [module];
    const filterFile = activeFile ? path.basename(activeFile) : undefined;

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

        // Filter to active file if applicable
        // Always filter to the active file's previews. If the file has none
        // (e.g. build.gradle.kts, or a Kotlin file without any @Preview),
        // the panel shows an empty state rather than dumping the whole module.
        const visiblePreviews = filterFile
            ? allPreviews.filter(p => p.sourceFile === filterFile)
            : allPreviews;

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

            const mod = previewModuleMap.get(preview.id)!;
            const imageData = await gradleService.readPreviewImage(mod, preview.renderOutput);
            if (abort.signal.aborted) { return; }

            if (imageData) {
                registry.setImage(preview.id, imageData);
                panel.postMessage({ command: 'updateImage', previewId: preview.id, imageData });
            } else {
                panel.postMessage({
                    command: 'setImageError',
                    previewId: preview.id,
                    message: 'Render not found — click refresh to render',
                });
            }
        }

        panel.postMessage({ command: 'showMessage', text: '' });
    } catch (err: any) {
        if (abort.signal.aborted) { return; }
        panel.postMessage({
            command: 'showMessage',
            text: err.message?.slice(0, 300) ?? 'Build failed',
        });
    } finally {
        if (pendingRefresh === abort) { pendingRefresh = null; }
    }
}

function handleWebviewMessage(msg: WebviewToExtensionMessage) {
    switch (msg.command) {
        case 'refresh':
            refresh(true);
            break;
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
    const match = new RegExp(`fun\\s+${functionName}\\s*\\(`).exec(text);
    if (match) {
        const pos = doc.positionAt(match.index);
        editor.selection = new vscode.Selection(pos, pos);
        editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
    }
}
