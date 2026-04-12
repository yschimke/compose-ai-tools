import * as vscode from 'vscode';
import * as path from 'path';
import { GradleService, GradleApi } from './gradleService';
import { PreviewPanel } from './previewPanel';
import { PreviewInfo } from './types';

const DEBOUNCE_MS = 1500;
const INIT_DELAY_MS = 1000;

let gradleService: GradleService | null = null;
let panel: PreviewPanel | null = null;
let debounceTimer: NodeJS.Timeout | null = null;
let selectedModule: string | null = null;
let pendingRefresh: AbortController | null = null;
let hasPreviewsLoaded = false;

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
        sendModuleList();
        const active = vscode.window.activeTextEditor;
        if (active?.document.languageId === 'kotlin') {
            refresh(false, active.document.uri.fsPath);
        } else {
            refresh(false);
        }
    }, INIT_DELAY_MS);
}

export function deactivate() {
    if (debounceTimer) { clearTimeout(debounceTimer); }
    pendingRefresh?.abort();
}

function isSourceFile(filePath: string): boolean {
    if (filePath.includes(`${path.sep}build${path.sep}`)) { return false; }
    return /\.(kt|xml|json|properties)$/i.test(filePath);
}

function debouncedRefresh(filePath: string) {
    // Invalidate cache for the changed module so the next discover isn't stale
    if (gradleService) {
        const module = gradleService.resolveModule(filePath);
        if (module) { gradleService.invalidateCache(module); }
    }

    if (debounceTimer) { clearTimeout(debounceTimer); }
    debounceTimer = setTimeout(() => {
        if (filePath.endsWith('.kt')) {
            refresh(false, filePath);
        } else {
            const active = vscode.window.activeTextEditor;
            if (active?.document.languageId === 'kotlin') {
                refresh(false, active.document.uri.fsPath);
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

    // Resolve which modules to load
    const module = selectedModule
        ?? (forFilePath ? gradleService.resolveModule(forFilePath) : null);

    const modules = module ? [module] : gradleService.findPreviewModules();
    if (modules.length === 0) {
        panel.postMessage({ command: 'showMessage', text: 'No modules with compose-ai-tools plugin found' });
        return;
    }

    const filterFile = forFilePath ? path.basename(forFilePath) : undefined;

    // If we already have previews on screen, use a stealth refresh:
    // keep the current cards visible and show per-card spinners rather than
    // clearing the view. Only show a full "Building..." message on first load.
    if (hasPreviewsLoaded) {
        panel.postMessage({ command: 'markAllLoading' });
    } else {
        panel.postMessage({ command: 'setLoading' });
    }
    gradleService.cancel();

    try {
        const allPreviews: PreviewInfo[] = [];
        const previewModuleMap = new Map<string, string>(); // previewId → module

        for (const mod of modules) {
            if (abort.signal.aborted) { return; }

            const manifest = forceRender
                ? await gradleService.renderPreviews(mod)
                : await gradleService.discoverPreviews(mod);

            if (manifest) {
                for (const p of manifest.previews) {
                    allPreviews.push(p);
                    previewModuleMap.set(p.id, mod);
                }
            }
        }

        if (abort.signal.aborted) { return; }

        if (allPreviews.length === 0) {
            panel.postMessage({ command: 'showMessage', text: 'No @Preview functions found' });
            return;
        }

        // Filter to active file if applicable
        let visiblePreviews = allPreviews;
        if (filterFile) {
            const matches = allPreviews.filter(p => p.sourceFile === filterFile);
            if (matches.length > 0) { visiblePreviews = matches; }
        }

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
    }
}

// Loose type for incoming webview messages (validated per-case above)
interface WebviewToExtensionMessage {
    command: string;
    className?: string;
    functionName?: string;
    value?: string;
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
