import * as fs from 'fs';
import * as vscode from 'vscode';
import { HistoryReader } from './daemon/historyReader';
import {
    HistoryAddedParams,
    HistoryListResult,
    HistoryReadResult,
    HistorySourceKind,
} from './daemon/daemonProtocol';

/**
 * Read-only Preview History panel — HISTORY.md § "VS Code integration".
 *
 * A separate webview view from the existing live `Compose Preview` panel.
 * Lists rendered snapshots newest-first across all previews in the active
 * file's module; click-to-expand shows the full PNG + metadata; "Open in
 * Editor" jumps to `previewMetadata.sourceFile`. Filter dropdown narrows
 * by source kind / branch / agent.
 *
 * **Storage**: prefers the daemon's `history/list` (live, push-updated
 * via `historyAdded`) when available; falls back to the FS reader when
 * the daemon is disabled or unhealthy. Same wire-format on both sides
 * — the panel's webview never knows which path served a given list.
 *
 * **Mutations: none.** Panel is read-only per HISTORY.md § "VS Code
 * integration". Pruning happens daemon-side or via the Gradle path.
 *
 * **Scope**: keyed off the same `currentScopeFile` extension.ts maintains
 * for the live panel — when the user navigates to a different file, the
 * history view re-scopes to that file's module's history. No multi-module
 * timeline today; that's H14 (cross-worktree merge in MCP).
 */
export class HistoryPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = 'composePreview.historyPanel';

    private view?: vscode.WebviewView;
    private currentScope: HistoryScope | null = null;

    constructor(
        private readonly extensionUri: vscode.Uri,
        private readonly source: HistorySource,
    ) {}

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        this.view = webviewView;
        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri],
        };
        webviewView.webview.html = this.getHtml(webviewView.webview);
        webviewView.webview.onDidReceiveMessage((msg) => this.handleMessage(msg));
        // Re-list whenever the view becomes visible (lazy panel UX).
        webviewView.onDidChangeVisibility(() => {
            if (webviewView.visible) { void this.refresh(); }
        });
        if (this.currentScope) { void this.refresh(); }
    }

    /** Re-scope the panel to a different module's history. Called from
     *  extension.ts when the active editor's module changes. */
    setScope(scope: HistoryScope | null): void {
        this.currentScope = scope;
        if (this.view?.visible) { void this.refresh(); }
    }

    /** Daemon push: a new render landed. If it belongs to the currently-
     *  scoped module and previewId, prepend it to the visible list and
     *  highlight it briefly. Drops cleanly when the panel isn't open. */
    onHistoryAdded(params: HistoryAddedParams): void {
        if (!this.view) { return; }
        const entry = params.entry as { previewId?: string; module?: string };
        if (!matchesScope(entry, this.currentScope)) { return; }
        this.view.webview.postMessage({ command: 'entryAdded', entry: params.entry });
    }

    /** Force a re-list against the current scope. */
    async refresh(): Promise<void> {
        if (!this.view || !this.currentScope) {
            this.view?.webview.postMessage({ command: 'showMessage', text: 'Open a Kotlin file to see its render history.' });
            return;
        }
        try {
            const result = await this.source.list(this.currentScope);
            this.view.webview.postMessage({ command: 'setEntries', result });
        } catch (err) {
            this.view.webview.postMessage({
                command: 'showMessage',
                text: `History unavailable: ${(err as Error).message}`,
            });
        }
    }

    private async handleMessage(msg: HistoryWebviewMessage): Promise<void> {
        switch (msg.command) {
            case 'refresh':
                await this.refresh();
                break;
            case 'loadImage':
                if (msg.id) { await this.sendImage(msg.id); }
                break;
            case 'openSource':
                if (msg.sourceFile) {
                    const uri = vscode.Uri.file(msg.sourceFile);
                    await vscode.window.showTextDocument(uri);
                }
                break;
            case 'diff':
                if (msg.fromId && msg.toId) {
                    await this.runDiff(msg.fromId, msg.toId);
                }
                break;
        }
    }

    private async sendImage(id: string): Promise<void> {
        if (!this.view) { return; }
        try {
            const result = await this.source.read(id);
            if (!result) {
                this.view.webview.postMessage({ command: 'imageError', id, message: 'Entry not found.' });
                return;
            }
            const bytes = result.pngBytes
                ?? (await fs.promises.readFile(result.pngPath)).toString('base64');
            this.view.webview.postMessage({
                command: 'imageReady', id, imageData: bytes, entry: result.entry,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: 'imageError', id, message: (err as Error).message,
            });
        }
    }

    private async runDiff(fromId: string, toId: string): Promise<void> {
        if (!this.view) { return; }
        try {
            const result = await this.source.diff(fromId, toId);
            this.view.webview.postMessage({ command: 'diffResult', fromId, toId, result });
        } catch (err) {
            this.view.webview.postMessage({
                command: 'diffError', fromId, toId, message: (err as Error).message,
            });
        }
    }

    private getHtml(webview: vscode.Webview): string {
        const nonce = getNonce();
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, 'media', 'codicon.css'),
        );
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, 'media', 'preview.css'),
        );
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource}; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
    <style nonce="${nonce}">
      body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); margin: 0; padding: 8px; }
      .toolbar { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
      .toolbar select { background: var(--vscode-dropdown-background); color: var(--vscode-dropdown-foreground);
                        border: 1px solid var(--vscode-dropdown-border); padding: 2px 6px; }
      .timeline { display: flex; flex-direction: column; gap: 4px; }
      .row { display: grid; grid-template-columns: 56px 1fr auto; gap: 8px; align-items: center;
             padding: 4px; border: 1px solid transparent; cursor: pointer; }
      .row.selected { border-color: var(--vscode-focusBorder); }
      .row:hover { background: var(--vscode-list-hoverBackground); }
      .row .thumb { width: 56px; height: 56px; background: var(--vscode-editorWidget-background);
                    display: flex; align-items: center; justify-content: center; overflow: hidden; }
      .row .thumb img { max-width: 100%; max-height: 100%; }
      .row .meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
      .row .ts { font-weight: 600; }
      .row .sub { color: var(--vscode-descriptionForeground); font-size: 90%;
                  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .row .badge { font-size: 80%; padding: 1px 6px; border-radius: 8px;
                    background: var(--vscode-badge-background); color: var(--vscode-badge-foreground); }
      .row .changed-dot { width: 8px; height: 8px; border-radius: 50%;
                          background: var(--vscode-charts-yellow); display: inline-block; margin-right: 4px; }
      .message { padding: 12px; color: var(--vscode-descriptionForeground); }
      .expanded { padding: 8px; background: var(--vscode-editorWidget-background); }
      .expanded img { max-width: 100%; }
      .actions { display: flex; gap: 6px; margin-top: 6px; }
      .actions button { background: var(--vscode-button-background); color: var(--vscode-button-foreground);
                        border: 0; padding: 3px 8px; cursor: pointer; }
      .actions button[disabled] { opacity: 0.5; cursor: default; }
      pre.metadata { font-size: 90%; max-height: 200px; overflow: auto;
                     background: var(--vscode-textCodeBlock-background); padding: 6px; }
      .diff-inline { padding: 8px; background: var(--vscode-editorWidget-background);
                     margin-top: 8px; border-left: 2px solid var(--vscode-focusBorder); }
    </style>
</head>
<body>
    <div class="toolbar" role="toolbar" aria-label="History filters">
        <select id="filter-source" title="Source">
            <option value="all">All sources</option>
            <option value="fs">Local filesystem</option>
            <option value="git">Git ref</option>
        </select>
        <select id="filter-branch" title="Branch">
            <option value="all">All branches</option>
        </select>
        <button id="btn-refresh" title="Refresh">⟳</button>
        <button id="btn-diff" disabled title="Pixel-diff two selected entries (metadata mode in current daemon)">Diff selected</button>
    </div>
    <div id="message" class="message">Loading…</div>
    <div id="timeline" class="timeline" role="list" aria-label="History entries"></div>

    <script nonce="${nonce}">
    (function() {
        const vscode = acquireVsCodeApi();
        const messageEl = document.getElementById('message');
        const timelineEl = document.getElementById('timeline');
        const filterSourceEl = document.getElementById('filter-source');
        const filterBranchEl = document.getElementById('filter-branch');
        const btnRefreshEl = document.getElementById('btn-refresh');
        const btnDiffEl = document.getElementById('btn-diff');

        let entries = [];
        let selectedIds = new Set();
        let expandedId = null;

        btnRefreshEl.addEventListener('click', () => {
            vscode.postMessage({ command: 'refresh' });
        });
        btnDiffEl.addEventListener('click', () => {
            const ids = [...selectedIds];
            if (ids.length === 2) {
                vscode.postMessage({ command: 'diff', fromId: ids[0], toId: ids[1] });
            }
        });
        filterSourceEl.addEventListener('change', applyFilters);
        filterBranchEl.addEventListener('change', applyFilters);

        function setMessage(text) {
            if (text) {
                messageEl.textContent = text;
                messageEl.style.display = 'block';
                timelineEl.innerHTML = '';
            } else {
                messageEl.style.display = 'none';
            }
        }

        function applyFilters() {
            const sourceVal = filterSourceEl.value;
            const branchVal = filterBranchEl.value;
            timelineEl.querySelectorAll('.row').forEach(row => {
                const matchSource = sourceVal === 'all' || row.dataset.sourceKind === sourceVal;
                const matchBranch = branchVal === 'all' || row.dataset.branch === branchVal;
                row.style.display = (matchSource && matchBranch) ? '' : 'none';
            });
        }

        function populateBranchFilter(es) {
            const branches = new Set();
            for (const e of es) {
                const b = e.git && e.git.branch;
                if (b) branches.add(b);
            }
            const prev = filterBranchEl.value;
            filterBranchEl.innerHTML = '';
            const allOpt = document.createElement('option');
            allOpt.value = 'all'; allOpt.textContent = 'All branches';
            filterBranchEl.appendChild(allOpt);
            for (const b of [...branches].sort()) {
                const opt = document.createElement('option');
                opt.value = b; opt.textContent = b;
                filterBranchEl.appendChild(opt);
            }
            if ([...filterBranchEl.options].some(o => o.value === prev)) {
                filterBranchEl.value = prev;
            }
        }

        function renderTimeline() {
            timelineEl.innerHTML = '';
            for (const entry of entries) {
                const row = document.createElement('div');
                row.className = 'row';
                row.setAttribute('role', 'listitem');
                row.dataset.id = entry.id || '';
                row.dataset.sourceKind = (entry.source && entry.source.kind) || '';
                row.dataset.branch = (entry.git && entry.git.branch) || '';

                const thumb = document.createElement('div');
                thumb.className = 'thumb';
                row.appendChild(thumb);

                const meta = document.createElement('div');
                meta.className = 'meta';
                const ts = document.createElement('div');
                ts.className = 'ts';
                ts.textContent = entry.timestamp || '(no timestamp)';
                meta.appendChild(ts);

                const sub = document.createElement('div');
                sub.className = 'sub';
                const dot = (entry.deltaFromPrevious && entry.deltaFromPrevious.pngHashChanged)
                    ? '<span class="changed-dot" title="bytes changed vs previous"></span>' : '';
                const trigger = entry.trigger ? entry.trigger : '—';
                const branch = (entry.git && entry.git.branch) || '';
                sub.innerHTML = dot + escapeHtml(trigger) + (branch ? ' · ' + escapeHtml(branch) : '');
                meta.appendChild(sub);
                row.appendChild(meta);

                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.textContent = ((entry.source && entry.source.kind) || 'fs');
                row.appendChild(badge);

                row.addEventListener('click', (ev) => {
                    if (ev.shiftKey) toggleSelected(entry.id, row);
                    else expandRow(entry.id, row);
                });
                timelineEl.appendChild(row);
            }
        }

        function toggleSelected(id, row) {
            if (selectedIds.has(id)) {
                selectedIds.delete(id);
                row.classList.remove('selected');
            } else {
                if (selectedIds.size >= 2) {
                    // Drop oldest selection so we never have more than 2.
                    const drop = [...selectedIds][0];
                    selectedIds.delete(drop);
                    const prev = timelineEl.querySelector('.row[data-id="' + cssEscape(drop) + '"]');
                    if (prev) prev.classList.remove('selected');
                }
                selectedIds.add(id);
                row.classList.add('selected');
            }
            btnDiffEl.disabled = selectedIds.size !== 2;
        }

        function expandRow(id, row) {
            // Collapse any previous expansion.
            const prev = timelineEl.querySelector('.expanded');
            if (prev) prev.remove();
            if (expandedId === id) { expandedId = null; return; }
            expandedId = id;

            const expansion = document.createElement('div');
            expansion.className = 'expanded';
            expansion.dataset.id = id;
            expansion.innerHTML = '<div>Loading…</div>';
            row.parentNode.insertBefore(expansion, row.nextSibling);
            vscode.postMessage({ command: 'loadImage', id });
        }

        function fillExpansion(id, imageData, entry) {
            const expansion = timelineEl.querySelector('.expanded[data-id="' + cssEscape(id) + '"]');
            if (!expansion) return;
            expansion.innerHTML = '';
            const img = document.createElement('img');
            img.src = 'data:image/png;base64,' + imageData;
            img.alt = (entry && entry.previewId) || id;
            expansion.appendChild(img);

            const actions = document.createElement('div');
            actions.className = 'actions';
            const sourceFile = entry && entry.previewMetadata && entry.previewMetadata.sourceFile;
            if (sourceFile) {
                const open = document.createElement('button');
                open.textContent = 'Open in Editor';
                open.addEventListener('click', () =>
                    vscode.postMessage({ command: 'openSource', sourceFile }));
                actions.appendChild(open);
            }
            expansion.appendChild(actions);

            const meta = document.createElement('pre');
            meta.className = 'metadata';
            meta.textContent = JSON.stringify(entry, null, 2);
            expansion.appendChild(meta);
        }

        function showDiff(fromId, toId, result) {
            const block = document.createElement('div');
            block.className = 'diff-inline';
            if (!result) {
                block.textContent = 'Diff unavailable.';
            } else {
                const changed = result.pngHashChanged ? 'pixels differ' : 'bytes identical';
                block.textContent = 'Diff (metadata): ' + changed
                    + (result.diffPx != null ? ' · diffPx=' + result.diffPx : '')
                    + (result.ssim != null ? ' · ssim=' + result.ssim.toFixed(3) : '');
            }
            timelineEl.insertBefore(block, timelineEl.firstChild);
            // Auto-clear after 12s so the panel doesn't accumulate stale diffs.
            setTimeout(() => block.remove(), 12_000);
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = String(text ?? '');
            return div.innerHTML;
        }
        function cssEscape(s) {
            return String(s).replace(/[\\\\"']/g, '\\\\$&');
        }

        window.addEventListener('message', event => {
            const msg = event.data;
            switch (msg.command) {
                case 'setEntries':
                    entries = msg.result.entries || [];
                    if (entries.length === 0) {
                        setMessage('No history yet for this preview / module.');
                    } else {
                        setMessage('');
                        populateBranchFilter(entries);
                        renderTimeline();
                        applyFilters();
                    }
                    break;
                case 'entryAdded':
                    if (msg.entry) {
                        entries.unshift(msg.entry);
                        populateBranchFilter(entries);
                        renderTimeline();
                        applyFilters();
                    }
                    break;
                case 'showMessage':
                    setMessage(msg.text || '');
                    break;
                case 'imageReady':
                    fillExpansion(msg.id, msg.imageData, msg.entry);
                    break;
                case 'imageError': {
                    const expansion = timelineEl.querySelector('.expanded[data-id="' + cssEscape(msg.id) + '"]');
                    if (expansion) expansion.textContent = 'Failed to load image: ' + (msg.message || '(no detail)');
                    break;
                }
                case 'diffResult':
                    showDiff(msg.fromId, msg.toId, msg.result);
                    break;
                case 'diffError':
                    showDiff(msg.fromId, msg.toId, null);
                    break;
            }
        });
    })();
    </script>
</body>
</html>`;
    }
}

/**
 * The two surfaces the panel reads from. Both implement the same shape so
 * the panel doesn't know whether the daemon or the FS reader served the
 * data — `extension.ts` decides per-call based on gate health.
 */
export interface HistorySource {
    list(scope: HistoryScope): Promise<HistoryListResult>;
    read(id: string): Promise<HistoryReadResult | null>;
    diff(fromId: string, toId: string): Promise<unknown | null>;
}

export interface HistoryScope {
    moduleId: string;
    /** Module project directory; the panel needs this to resolve the
     *  fallback `historyDir` when the daemon is down. */
    projectDir: string;
    /** Optional preview filter; when null, the panel shows every preview
     *  in the module's history. */
    previewId?: string;
}

/**
 * `HistorySource` factory that picks the live daemon when healthy, otherwise
 * the FS reader. Used by extension.ts so the panel always has a source.
 */
export function buildHistorySource(opts: BuildSourceOptions): HistorySource {
    return {
        list: async (scope) => {
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    return await opts.daemonList(scope);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon list failed for ${scope.moduleId}, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            return new HistoryReader(historyDirFor(scope)).list({
                previewId: scope.previewId,
            });
        },
        read: async (id) => {
            if (opts.currentScope) {
                if (opts.isDaemonReady(opts.currentScope.moduleId)) {
                    try {
                        return await opts.daemonRead(id);
                    } catch (err) {
                        opts.logger?.appendLine(
                            `[history] daemon read failed for ${id}, falling back to FS: ${(err as Error).message}`,
                        );
                    }
                }
                return new HistoryReader(historyDirFor(opts.currentScope)).read(id);
            }
            return null;
        },
        diff: async (fromId, toId) => {
            if (opts.currentScope) {
                if (opts.isDaemonReady(opts.currentScope.moduleId)) {
                    try {
                        return await opts.daemonDiff(fromId, toId);
                    } catch (err) {
                        opts.logger?.appendLine(
                            `[history] daemon diff failed, falling back to FS: ${(err as Error).message}`,
                        );
                    }
                }
                return new HistoryReader(historyDirFor(opts.currentScope))
                    .diff(fromId, toId, 'metadata');
            }
            return null;
        },
    };
}

function historyDirFor(scope: HistoryScope): string {
    return `${scope.projectDir}/.compose-preview-history`;
}

interface BuildSourceOptions {
    isDaemonReady: (moduleId: string) => boolean;
    daemonList: (scope: HistoryScope) => Promise<HistoryListResult>;
    daemonRead: (id: string) => Promise<HistoryReadResult>;
    daemonDiff: (fromId: string, toId: string) => Promise<unknown>;
    /** Mutable closure — extension.ts updates this on every scope change so
     *  the panel's `read` / `diff` callbacks know which module's FS to fall
     *  back to. We don't bake it into the `HistorySource` shape because
     *  list() takes scope as an arg but read() / diff() don't. */
    currentScope: HistoryScope | null;
    logger?: { appendLine(s: string): void };
}

interface HistoryWebviewMessage {
    command: string;
    id?: string;
    sourceFile?: string;
    fromId?: string;
    toId?: string;
}

function matchesScope(
    entry: { previewId?: string; module?: string },
    scope: HistoryScope | null,
): boolean {
    if (!scope) { return false; }
    // The panel scope is keyed on Gradle moduleId — entries carry it as
    // `module`. previewId filter is optional; absent → any preview in the
    // module matches.
    if (entry.module !== scope.moduleId) { return false; }
    if (scope.previewId && entry.previewId !== scope.previewId) { return false; }
    return true;
}

function getNonce(): string {
    let text = '';
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}
