import * as fs from 'fs';
import * as vscode from 'vscode';
import { HistoryReader } from './daemon/historyReader';
import { CurrentRendersHistory } from './daemon/currentRendersHistory';
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
            this.view?.webview.postMessage({ command: 'setScopeLabel', label: null });
            return;
        }
        if (!this.currentScope.previewId) {
            // Module-wide history would mix entries from every preview in the
            // file, which is rarely what the user wants and obscures whose
            // timeline they're looking at. Require a single preview be
            // selected (focus mode, or filters narrowed to one card) and
            // show a hint until then.
            this.view.webview.postMessage({ command: 'setScopeLabel', label: null });
            this.view.webview.postMessage({
                command: 'showMessage',
                text: 'Select a single preview (focus mode, or narrow the filter to one card) to see its render history.',
            });
            return;
        }
        const label = this.currentScope.previewLabel ?? this.currentScope.previewId;
        this.view.webview.postMessage({ command: 'setScopeLabel', label });
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
                if (msg.id) { await this.sendImage(msg.id, 'expansion'); }
                break;
            case 'loadThumb':
                if (msg.id) { await this.sendImage(msg.id, 'thumb'); }
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
            case 'requestDiff':
                if (msg.id && (msg.against === 'current' || msg.against === 'previous')) {
                    await this.runPairDiff(msg.id, msg.against);
                }
                break;
        }
    }

    private async runPairDiff(id: string, against: 'current' | 'previous'): Promise<void> {
        if (!this.view) { return; }
        const scope = this.currentScope;
        if (!scope) {
            this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'No active scope.' });
            return;
        }
        try {
            const left = await this.source.read(id);
            if (!left) {
                this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'Entry not found.' });
                return;
            }
            const leftEntry = left.entry as { previewId?: string; timestamp?: string };
            const previewId = leftEntry.previewId ?? scope.previewId;
            if (!previewId) {
                this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'Entry has no previewId.' });
                return;
            }

            let right: HistoryReadResult | null = null;
            let rightLabel = '';
            if (against === 'current') {
                const synthList = currentRendersFor(scope).list(previewId);
                const synth = synthList.entries[0] as { id?: string; timestamp?: string } | undefined;
                if (!synth?.id) {
                    this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'No live render available for this preview.' });
                    return;
                }
                right = currentRendersFor(scope).read(synth.id);
                rightLabel = `Current · ${formatLabelTime(synth.timestamp)}`;
            } else {
                const list = await this.source.list({ ...scope, previewId });
                // Sort newest-first to match the panel; the entry just *after*
                // the clicked one in this order is the older "previous".
                const sorted = [...list.entries].sort((a, b) => {
                    const at = (a as { timestamp?: string }).timestamp ?? '';
                    const bt = (b as { timestamp?: string }).timestamp ?? '';
                    return bt.localeCompare(at);
                });
                const idx = sorted.findIndex(e => (e as { id?: string }).id === id);
                const prev = idx >= 0 ? sorted[idx + 1] as { id?: string; timestamp?: string } | undefined : undefined;
                if (!prev?.id) {
                    this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'No earlier entry for this preview.' });
                    return;
                }
                right = await this.source.read(prev.id);
                rightLabel = `Previous · ${formatLabelTime(prev.timestamp)}`;
            }
            if (!right) {
                this.view.webview.postMessage({ command: 'diffPairError', id, against, message: 'Comparison entry not found.' });
                return;
            }
            const leftBytes = left.pngBytes
                ?? (await fs.promises.readFile(left.pngPath)).toString('base64');
            const rightBytes = right.pngBytes
                ?? (await fs.promises.readFile(right.pngPath)).toString('base64');
            this.view.webview.postMessage({
                command: 'diffReady',
                id,
                against,
                leftLabel: `This entry · ${formatLabelTime(leftEntry.timestamp)}`,
                leftImage: leftBytes,
                rightLabel,
                rightImage: rightBytes,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: 'diffPairError', id, against, message: (err as Error).message,
            });
        }
    }

    private async sendImage(id: string, kind: 'expansion' | 'thumb'): Promise<void> {
        if (!this.view) { return; }
        const readyCmd = kind === 'thumb' ? 'thumbReady' : 'imageReady';
        const errorCmd = kind === 'thumb' ? 'thumbError' : 'imageError';
        try {
            const result = await this.source.read(id);
            if (!result) {
                this.view.webview.postMessage({ command: errorCmd, id, message: 'Entry not found.' });
                return;
            }
            const bytes = result.pngBytes
                ?? (await fs.promises.readFile(result.pngPath)).toString('base64');
            this.view.webview.postMessage({
                command: readyCmd, id, imageData: bytes, entry: result.entry,
            });
        } catch (err) {
            this.view.webview.postMessage({
                command: errorCmd, id, message: (err as Error).message,
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
      .row { grid-template-columns: 56px 1fr auto auto; }
      .row-actions { display: flex; gap: 2px; opacity: 0;
                     transition: opacity 100ms ease; }
      .row:hover .row-actions, .row:focus-within .row-actions { opacity: 1; }
      .row-action { width: 22px; height: 22px; }
      .diff-expanded { padding: 8px; background: var(--vscode-editorWidget-background); }
      .diff-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
      .diff-pane { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
      .diff-pane-label { font-size: 90%; color: var(--vscode-descriptionForeground);
                         white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .diff-pane img { max-width: 100%; height: auto; display: block;
                       background: var(--vscode-editor-background); }
      .diff-pane-empty { font-size: 90%; padding: 12px; text-align: center;
                         color: var(--vscode-descriptionForeground);
                         background: var(--vscode-editor-background); }
      .scope-chip { display: inline-flex; align-items: center; gap: 6px;
                    padding: 2px 8px; margin-bottom: 8px;
                    background: var(--vscode-badge-background);
                    color: var(--vscode-badge-foreground);
                    border-radius: 10px; font-size: 90%;
                    max-width: 100%; overflow: hidden; text-overflow: ellipsis;
                    white-space: nowrap; }
      .scope-chip[hidden] { display: none; }
      .scope-chip .codicon { font-size: 12px; opacity: 0.85; }
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
    <div id="scope-chip" class="scope-chip" role="status" aria-live="polite" hidden
         title="History narrowed because a single preview is selected in the live panel — change focus or filters there to widen.">
        <i class="codicon codicon-filter" aria-hidden="true"></i>
        <span id="scope-chip-label"></span>
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
        const scopeChipEl = document.getElementById('scope-chip');
        const scopeChipLabelEl = document.getElementById('scope-chip-label');

        let entries = [];
        let selectedIds = new Set();
        let expandedId = null;
        // Thumbnail cache + dedup so each entry's PNG is fetched at most once
        // per panel session even if scrolling brings the same row back into
        // view repeatedly.
        const thumbCache = new Map();
        const thumbRequested = new Set();
        const thumbObserver = ('IntersectionObserver' in window)
            ? new IntersectionObserver((items) => {
                for (const item of items) {
                    if (!item.isIntersecting) continue;
                    const el = item.target;
                    const id = el.dataset.id;
                    if (!id || thumbRequested.has(id)) continue;
                    thumbRequested.add(id);
                    vscode.postMessage({ command: 'loadThumb', id });
                }
            }, { root: null, rootMargin: '64px', threshold: 0 })
            : null;

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
            // Thumbnails not yet loaded for the new entry set should retry —
            // disconnect the old observer and rebuild against the new rows.
            if (thumbObserver) thumbObserver.disconnect();
            thumbRequested.clear();
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
                thumb.dataset.id = entry.id || '';
                if (thumbCache.has(entry.id)) {
                    populateThumb(thumb, thumbCache.get(entry.id));
                } else if (thumbObserver) {
                    thumbObserver.observe(thumb);
                }
                row.appendChild(thumb);

                const meta = document.createElement('div');
                meta.className = 'meta';
                const ts = document.createElement('div');
                ts.className = 'ts';
                ts.textContent = formatRelative(entry.timestamp);
                ts.title = entry.timestamp || '';
                meta.appendChild(ts);

                const sub = document.createElement('div');
                sub.className = 'sub';
                const dot = (entry.deltaFromPrevious && entry.deltaFromPrevious.pngHashChanged)
                    ? '<span class="changed-dot" title="bytes changed vs previous"></span>' : '';
                const absolute = formatAbsolute(entry.timestamp);
                const trigger = entry.trigger ? entry.trigger : '—';
                const branch = (entry.git && entry.git.branch) || '';
                const subParts = [];
                if (absolute) subParts.push(escapeHtml(absolute));
                subParts.push(escapeHtml(trigger));
                if (branch) subParts.push(escapeHtml(branch));
                sub.innerHTML = dot + subParts.join(' · ');
                meta.appendChild(sub);
                row.appendChild(meta);

                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.textContent = ((entry.source && entry.source.kind) || 'fs');
                row.appendChild(badge);

                const actions = document.createElement('div');
                actions.className = 'row-actions';
                const diffPrevBtn = document.createElement('button');
                diffPrevBtn.className = 'icon-button row-action';
                diffPrevBtn.title = 'Diff against the previous entry for this preview';
                diffPrevBtn.setAttribute('aria-label', 'Diff vs previous');
                diffPrevBtn.innerHTML = '<i class="codicon codicon-arrow-up" aria-hidden="true"></i>';
                diffPrevBtn.addEventListener('click', (ev) => {
                    ev.stopPropagation();
                    requestRowDiff(entry.id, row, 'previous');
                });
                actions.appendChild(diffPrevBtn);
                const diffCurrentBtn = document.createElement('button');
                diffCurrentBtn.className = 'icon-button row-action';
                diffCurrentBtn.title = 'Diff against the live render of this preview';
                diffCurrentBtn.setAttribute('aria-label', 'Diff vs current');
                diffCurrentBtn.innerHTML = '<i class="codicon codicon-git-compare" aria-hidden="true"></i>';
                diffCurrentBtn.addEventListener('click', (ev) => {
                    ev.stopPropagation();
                    requestRowDiff(entry.id, row, 'current');
                });
                actions.appendChild(diffCurrentBtn);
                row.appendChild(actions);

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

        function requestRowDiff(id, row, against) {
            const prev = timelineEl.querySelector('.expanded');
            if (prev) prev.remove();
            expandedId = id;
            const expansion = document.createElement('div');
            expansion.className = 'expanded diff-expanded';
            expansion.dataset.id = id;
            expansion.dataset.against = against;
            expansion.innerHTML = '<div>Loading diff…</div>';
            row.parentNode.insertBefore(expansion, row.nextSibling);
            vscode.postMessage({ command: 'requestDiff', id, against });
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
        }

        function populateThumb(thumbEl, imageData) {
            thumbEl.innerHTML = '';
            const img = document.createElement('img');
            img.src = 'data:image/png;base64,' + imageData;
            img.alt = '';
            thumbEl.appendChild(img);
        }

        function fillDiff(id, against, leftLabel, leftImage, rightLabel, rightImage) {
            const expansion = timelineEl.querySelector(
                '.expanded[data-id="' + cssEscape(id) + '"][data-against="' + cssEscape(against) + '"]');
            if (!expansion) return;
            expansion.innerHTML = '';
            const grid = document.createElement('div');
            grid.className = 'diff-grid';
            grid.appendChild(buildDiffPane(leftLabel, leftImage));
            grid.appendChild(buildDiffPane(rightLabel, rightImage));
            expansion.appendChild(grid);
        }

        function buildDiffPane(label, imageData) {
            const pane = document.createElement('div');
            pane.className = 'diff-pane';
            const cap = document.createElement('div');
            cap.className = 'diff-pane-label';
            cap.textContent = label;
            pane.appendChild(cap);
            if (imageData) {
                const img = document.createElement('img');
                img.src = 'data:image/png;base64,' + imageData;
                img.alt = label;
                pane.appendChild(img);
            } else {
                const empty = document.createElement('div');
                empty.className = 'diff-pane-empty';
                empty.textContent = '(no image)';
                pane.appendChild(empty);
            }
            return pane;
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

        function formatRelative(iso) {
            if (!iso) return '(no timestamp)';
            const t = Date.parse(iso);
            if (isNaN(t)) return iso;
            const s = Math.round((Date.now() - t) / 1000);
            if (s < 5) return 'just now';
            if (s < 60) return s + 's ago';
            const m = Math.round(s / 60);
            if (m < 60) return m + 'm ago';
            const h = Math.round(m / 60);
            if (h < 24) return h + 'h ago';
            const d = Math.round(h / 24);
            if (d < 30) return d + 'd ago';
            const mo = Math.round(d / 30);
            if (mo < 12) return mo + 'mo ago';
            return Math.round(mo / 12) + 'y ago';
        }

        function formatAbsolute(iso) {
            if (!iso) return '';
            const t = Date.parse(iso);
            if (isNaN(t)) return '';
            try {
                return new Date(t).toLocaleString(undefined, {
                    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
                });
            } catch (_) {
                return '';
            }
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
                case 'setScopeLabel':
                    if (msg.label) {
                        scopeChipLabelEl.textContent = msg.label;
                        scopeChipEl.hidden = false;
                    } else {
                        scopeChipLabelEl.textContent = '';
                        scopeChipEl.hidden = true;
                    }
                    break;
                case 'imageReady':
                    fillExpansion(msg.id, msg.imageData, msg.entry);
                    break;
                case 'imageError': {
                    const expansion = timelineEl.querySelector('.expanded[data-id="' + cssEscape(msg.id) + '"]');
                    if (expansion) expansion.textContent = 'Failed to load image: ' + (msg.message || '(no detail)');
                    break;
                }
                case 'thumbReady': {
                    thumbCache.set(msg.id, msg.imageData);
                    const thumbEl = timelineEl.querySelector('.thumb[data-id="' + cssEscape(msg.id) + '"]');
                    if (thumbEl) populateThumb(thumbEl, msg.imageData);
                    break;
                }
                case 'thumbError':
                    // Drop the dedup so a future re-render can retry. Leave
                    // the gray box in place; surfacing per-entry errors at
                    // the thumb scale would just be noisy.
                    thumbRequested.delete(msg.id);
                    break;
                case 'diffResult':
                    showDiff(msg.fromId, msg.toId, msg.result);
                    break;
                case 'diffError':
                    showDiff(msg.fromId, msg.toId, null);
                    break;
                case 'diffReady':
                    fillDiff(msg.id, msg.against, msg.leftLabel, msg.leftImage,
                             msg.rightLabel, msg.rightImage);
                    break;
                case 'diffPairError': {
                    const expansion = timelineEl.querySelector(
                        '.expanded[data-id="' + cssEscape(msg.id) + '"][data-against="' + cssEscape(msg.against) + '"]');
                    if (expansion) expansion.textContent = 'Diff unavailable: ' + (msg.message || '(no detail)');
                    break;
                }
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
    /** Display name for the previewId filter (function or `@Preview` name).
     *  Surfaced in the panel's toolbar as a chip when set so the user can
     *  see why entries are narrowed. Not used for filtering. */
    previewLabel?: string;
}

/**
 * `HistorySource` factory that picks the live daemon when healthy, otherwise
 * the FS reader. Used by extension.ts so the panel always has a source.
 */
export function buildHistorySource(opts: BuildSourceOptions): HistorySource {
    return {
        list: async (scope) => {
            let result: HistoryListResult | null = null;
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    result = await opts.daemonList(scope);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon list failed for ${scope.moduleId}, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            if (!result) {
                result = new HistoryReader(historyDirFor(scope)).list({
                    previewId: scope.previewId,
                });
            }
            // Greenfield UX: when no recorded history exists yet (default
            // path with the daemon disabled, or a freshly-warmed daemon
            // that hasn't yet observed a render) but the Gradle render
            // path has already produced PNGs, surface those as a single
            // synthetic page so the panel isn't empty after the user's
            // first render.
            if (result.entries.length === 0) {
                const synth = currentRendersFor(scope).list(scope.previewId);
                if (synth.entries.length > 0) { return synth; }
            }
            return result;
        },
        read: async (id) => {
            const scope = opts.getCurrentScope();
            if (!scope) { return null; }
            if (CurrentRendersHistory.isSyntheticId(id)) {
                return currentRendersFor(scope).read(id);
            }
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    return await opts.daemonRead(id);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon read failed for ${id}, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            return new HistoryReader(historyDirFor(scope)).read(id);
        },
        diff: async (fromId, toId) => {
            const scope = opts.getCurrentScope();
            if (!scope) { return null; }
            // Synthetic "current render" entries don't have a stable prior
            // — diffing them is meaningless. Fall through to null so the
            // panel surfaces "Diff unavailable" instead of crashing.
            if (CurrentRendersHistory.isSyntheticId(fromId)
                || CurrentRendersHistory.isSyntheticId(toId)) {
                return null;
            }
            if (opts.isDaemonReady(scope.moduleId)) {
                try {
                    return await opts.daemonDiff(fromId, toId);
                } catch (err) {
                    opts.logger?.appendLine(
                        `[history] daemon diff failed, falling back to FS: ${(err as Error).message}`,
                    );
                }
            }
            return new HistoryReader(historyDirFor(scope))
                .diff(fromId, toId, 'metadata');
        },
    };
}

function historyDirFor(scope: HistoryScope): string {
    return `${scope.projectDir}/.compose-preview-history`;
}

function currentRendersFor(scope: HistoryScope): CurrentRendersHistory {
    return new CurrentRendersHistory({
        buildDir: `${scope.projectDir}/build/compose-previews`,
        moduleId: scope.moduleId,
    });
}

interface BuildSourceOptions {
    isDaemonReady: (moduleId: string) => boolean;
    daemonList: (scope: HistoryScope) => Promise<HistoryListResult>;
    daemonRead: (id: string) => Promise<HistoryReadResult>;
    daemonDiff: (fromId: string, toId: string) => Promise<unknown>;
    /** Live accessor for the active scope. The panel's read() / diff()
     *  callbacks don't get scope as an argument (unlike list), so they
     *  reach into the extension's mutable scope ref through this getter
     *  rather than capturing a stale snapshot at construction time. */
    getCurrentScope: () => HistoryScope | null;
    logger?: { appendLine(s: string): void };
}

interface HistoryWebviewMessage {
    command: string;
    id?: string;
    sourceFile?: string;
    fromId?: string;
    toId?: string;
    against?: 'current' | 'previous';
}

function formatLabelTime(iso: string | undefined): string {
    if (!iso) { return '(unknown time)'; }
    const t = Date.parse(iso);
    if (isNaN(t)) { return iso; }
    return new Date(t).toLocaleString(undefined, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });
}

function matchesScope(
    entry: { previewId?: string; module?: string },
    scope: HistoryScope | null,
): boolean {
    if (!scope || !scope.previewId) { return false; }
    if (entry.module !== scope.moduleId) { return false; }
    if (entry.previewId !== scope.previewId) { return false; }
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
