import * as vscode from 'vscode';
import { PreviewInfo, ExtensionToWebview, WebviewToExtension } from './types';

export class PreviewPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = 'composePreview.panel';

    private view?: vscode.WebviewView;
    private extensionUri: vscode.Uri;
    private onMessage: (msg: WebviewToExtension) => void;

    constructor(extensionUri: vscode.Uri, onMessage: (msg: WebviewToExtension) => void) {
        this.extensionUri = extensionUri;
        this.onMessage = onMessage;
    }

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
        webviewView.webview.onDidReceiveMessage((msg: WebviewToExtension) => {
            this.onMessage(msg);
        });
    }

    postMessage(msg: ExtensionToWebview): void {
        this.view?.webview.postMessage(msg);
    }

    private getHtml(webview: vscode.Webview): string {
        const nonce = getNonce();
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, 'media', 'preview.css'),
        );

        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${styleUri}" rel="stylesheet">
</head>
<body>
    <div class="toolbar" id="toolbar" role="toolbar" aria-label="Preview filters">
        <select id="filter-file" title="Filter by source file" aria-label="Source file filter">
            <option value="all">All files</option>
        </select>
        <select id="filter-function" title="Filter by function" aria-label="Function filter">
            <option value="all">All functions</option>
        </select>
        <select id="filter-label" title="Filter by preview label" aria-label="Preview label filter">
            <option value="all">All labels</option>
        </select>
        <select id="filter-group" title="Filter by group" aria-label="Group filter">
            <option value="all">All groups</option>
        </select>
        <select id="filter-module" title="Module" aria-label="Module selector">
            <option value="">Auto</option>
        </select>
        <select id="layout-mode" title="Layout" aria-label="Layout mode">
            <option value="grid">Grid</option>
            <option value="flow">Flow row</option>
            <option value="column">Column</option>
            <option value="focus">Focus</option>
        </select>
        <button id="btn-refresh" title="Refresh previews" aria-label="Refresh previews">&#x21bb;</button>
    </div>

    <div id="message" class="message" role="status" aria-live="polite"></div>
    <div id="focus-controls" class="focus-controls" hidden>
        <button id="btn-prev" title="Previous preview" aria-label="Previous preview">&#x2190;</button>
        <span id="focus-position" aria-live="polite"></span>
        <button id="btn-next" title="Next preview" aria-label="Next preview">&#x2192;</button>
    </div>
    <div id="preview-grid" class="preview-grid" role="list" aria-label="Preview cards"></div>

    <script nonce="${nonce}">
    (function() {
        const vscode = acquireVsCodeApi();
        const state = vscode.getState() || { filters: {} };

        const grid = document.getElementById('preview-grid');
        const message = document.getElementById('message');
        const filterFile = document.getElementById('filter-file');
        const filterFunction = document.getElementById('filter-function');
        const filterLabel = document.getElementById('filter-label');
        const filterGroup = document.getElementById('filter-group');
        const filterModule = document.getElementById('filter-module');
        const layoutMode = document.getElementById('layout-mode');
        const focusControls = document.getElementById('focus-controls');
        const btnPrev = document.getElementById('btn-prev');
        const btnNext = document.getElementById('btn-next');
        const focusPosition = document.getElementById('focus-position');
        const btnRefresh = document.getElementById('btn-refresh');

        let allPreviews = [];
        let moduleDir = '';
        let filterDebounce = null;
        let focusIndex = 0;

        // Restore layout preference
        if (state.layout && ['grid', 'flow', 'column', 'focus'].includes(state.layout)) {
            layoutMode.value = state.layout;
        }
        applyLayout();

        layoutMode.addEventListener('change', () => {
            state.layout = layoutMode.value;
            vscode.setState(state);
            applyLayout();
        });

        btnPrev.addEventListener('click', () => navigateFocus(-1));
        btnNext.addEventListener('click', () => navigateFocus(1));

        btnRefresh.addEventListener('click', () => vscode.postMessage({ command: 'refresh' }));

        for (const sel of [filterFile, filterFunction, filterLabel, filterGroup]) {
            sel.addEventListener('change', () => {
                saveFilterState();
                if (filterDebounce) clearTimeout(filterDebounce);
                filterDebounce = setTimeout(applyFilters, 100);
            });
        }

        filterModule.addEventListener('change', () => {
            vscode.postMessage({ command: 'selectModule', value: filterModule.value });
        });

        function saveFilterState() {
            state.filters = {
                file: filterFile.value,
                fn: filterFunction.value,
                label: filterLabel.value,
                group: filterGroup.value,
            };
            vscode.setState(state);
        }

        function restoreFilterState() {
            const f = state.filters || {};
            if (f.file && hasOption(filterFile, f.file)) filterFile.value = f.file;
            if (f.fn && hasOption(filterFunction, f.fn)) filterFunction.value = f.fn;
            if (f.label && hasOption(filterLabel, f.label)) filterLabel.value = f.label;
            if (f.group && hasOption(filterGroup, f.group)) filterGroup.value = f.group;
        }

        function hasOption(select, value) {
            return Array.from(select.options).some(o => o.value === value);
        }

        function applyFilters() {
            const fnVal = filterFunction.value;
            const lblVal = filterLabel.value;
            const grpVal = filterGroup.value;
            const fileVal = filterFile.value;

            let visibleCount = 0;
            document.querySelectorAll('.preview-card').forEach(card => {
                const show =
                    (fnVal === 'all' || card.dataset.function === fnVal) &&
                    (lblVal === 'all' || card.dataset.label === lblVal) &&
                    (grpVal === 'all' || card.dataset.group === grpVal) &&
                    (fileVal === 'all' || card.dataset.file === fileVal);
                card.classList.toggle('filtered-out', !show);
                if (show) visibleCount++;
            });

            message.textContent = visibleCount === 0 && allPreviews.length > 0
                ? 'No previews match the current filters'
                : '';
            message.style.display = visibleCount === 0 && allPreviews.length > 0 ? 'block' : 'none';

            // Re-apply layout so focus mode updates correctly after filter change
            applyLayout();
        }

        function getVisibleCards() {
            return Array.from(document.querySelectorAll('.preview-card'))
                .filter(c => !c.classList.contains('filtered-out'));
        }

        function applyLayout() {
            const mode = layoutMode.value;
            grid.className = 'preview-grid layout-' + mode;
            focusControls.hidden = mode !== 'focus';

            if (mode === 'focus') {
                const visible = getVisibleCards();
                if (visible.length === 0) {
                    focusPosition.textContent = '0 / 0';
                    return;
                }
                if (focusIndex >= visible.length) focusIndex = visible.length - 1;
                if (focusIndex < 0) focusIndex = 0;
                // Hide all non-focused cards
                document.querySelectorAll('.preview-card').forEach(card => {
                    card.classList.remove('focused');
                });
                visible.forEach((card, i) => {
                    card.classList.toggle('hidden-by-focus', i !== focusIndex);
                });
                if (visible[focusIndex]) {
                    visible[focusIndex].classList.add('focused');
                }
                focusPosition.textContent = (focusIndex + 1) + ' / ' + visible.length;
                btnPrev.disabled = focusIndex === 0;
                btnNext.disabled = focusIndex === visible.length - 1;
            } else {
                // Clear focus classes for other layouts
                document.querySelectorAll('.preview-card').forEach(card => {
                    card.classList.remove('focused', 'hidden-by-focus');
                });
            }
        }

        function navigateFocus(delta) {
            const visible = getVisibleCards();
            if (visible.length === 0) return;
            focusIndex = Math.max(0, Math.min(visible.length - 1, focusIndex + delta));
            applyLayout();
        }

        function populateFilter(select, values, label) {
            const prev = select.value;
            select.innerHTML = '';
            const allOpt = document.createElement('option');
            allOpt.value = 'all';
            allOpt.textContent = 'All ' + label;
            select.appendChild(allOpt);
            for (const v of values) {
                if (!v) continue;
                const opt = document.createElement('option');
                opt.value = v;
                opt.textContent = v;
                select.appendChild(opt);
            }
            if (hasOption(select, prev)) select.value = prev;
        }

        function sanitizeId(id) {
            return id.replace(/[^a-zA-Z0-9_-]/g, '_');
        }

        function createCard(p) {
            const card = document.createElement('div');
            card.className = 'preview-card';
            card.id = 'preview-' + sanitizeId(p.id);
            card.setAttribute('role', 'listitem');
            card.dataset.function = p.functionName;
            card.dataset.label = p.params.name || '';
            card.dataset.group = p.params.group || '';
            card.dataset.file = p.sourceFile || '';
            card.dataset.previewId = p.id;
            card.dataset.className = p.className;

            const header = document.createElement('div');
            header.className = 'card-header';

            const titleRow = document.createElement('div');
            titleRow.className = 'card-title-row';

            const title = document.createElement('button');
            title.className = 'card-title';
            title.textContent = p.functionName + (p.params.name ? ' — ' + p.params.name : '');
            title.title = 'Open source: ' + p.className + '.' + p.functionName;
            title.addEventListener('click', () => {
                vscode.postMessage({
                    command: 'openFile',
                    className: p.className,
                    functionName: p.functionName,
                });
            });
            titleRow.appendChild(title);

            // History toggle — asks the extension for this preview's snapshots.
            // Hidden by default; shown once the extension replies with a non-empty
            // setHistory payload, or re-hidden if the folder is empty.
            const historyBtn = document.createElement('button');
            historyBtn.className = 'card-history-btn';
            historyBtn.title = 'Show render history';
            historyBtn.setAttribute('aria-label', 'Show render history');
            historyBtn.innerHTML = '&#x1F552;'; // clock face
            historyBtn.addEventListener('click', () => {
                vscode.postMessage({ command: 'showHistory', previewId: p.id });
            });
            titleRow.appendChild(historyBtn);

            header.appendChild(titleRow);

            if (p.sourceFile) {
                const sub = document.createElement('div');
                sub.className = 'card-subtitle';
                sub.textContent = p.sourceFile;
                header.appendChild(sub);
            }

            card.appendChild(header);

            const imgContainer = document.createElement('div');
            imgContainer.className = 'image-container';
            const skeleton = document.createElement('div');
            skeleton.className = 'skeleton';
            skeleton.setAttribute('aria-label', 'Loading preview');
            imgContainer.appendChild(skeleton);
            card.appendChild(imgContainer);

            // Lazy-built history drawer — only populated when the user clicks
            // the history button and the extension returns entries.
            const drawer = document.createElement('div');
            drawer.className = 'history-drawer';
            drawer.hidden = true;
            card.appendChild(drawer);
            return card;
        }

        /**
         * Populates a card's history drawer and reveals it. Called when the
         * extension replies to our showHistory request.
         */
        function showHistory(previewId, entries) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;
            const drawer = card.querySelector('.history-drawer');
            if (!drawer) return;

            if (!entries || entries.length === 0) {
                drawer.hidden = false;
                drawer.innerHTML = '<div class="history-empty">No history yet. Enable <code>historyEnabled</code> in composePreview to start archiving.</div>';
                return;
            }

            drawer.innerHTML = '';

            // Header with close button + position indicator
            const head = document.createElement('div');
            head.className = 'history-head';
            const label = document.createElement('span');
            label.className = 'history-label';
            const close = document.createElement('button');
            close.className = 'history-close';
            close.innerHTML = '&times;';
            close.title = 'Close history';
            close.addEventListener('click', () => {
                drawer.hidden = true;
                restoreLatestImage(card);
            });
            head.appendChild(label);
            head.appendChild(close);
            drawer.appendChild(head);

            // Horizontal timeline strip — click a chip to view that snapshot
            const strip = document.createElement('div');
            strip.className = 'history-strip';
            strip.setAttribute('role', 'list');
            for (const entry of entries) {
                const chip = document.createElement('button');
                chip.className = 'history-chip';
                chip.dataset.filename = entry.filename;
                chip.title = entry.iso;
                chip.textContent = formatChipLabel(entry.timestamp);
                chip.addEventListener('click', () => {
                    // Visual feedback + state
                    strip.querySelectorAll('.history-chip').forEach(c =>
                        c.classList.toggle('selected', c === chip));
                    label.textContent = 'Viewing ' + entry.iso;
                    card.dataset.viewingHistory = entry.filename;
                    // Show spinner in the main image container until the extension replies
                    const container = card.querySelector('.image-container');
                    if (container && !container.querySelector('.loading-overlay')) {
                        const overlay = document.createElement('div');
                        overlay.className = 'loading-overlay subtle';
                        overlay.innerHTML = '<div class="spinner" aria-label="Loading snapshot"></div>';
                        container.appendChild(overlay);
                    }
                    vscode.postMessage({
                        command: 'loadHistoryImage',
                        previewId: previewId,
                        filename: entry.filename,
                    });
                });
                strip.appendChild(chip);
            }
            drawer.appendChild(strip);
            drawer.hidden = false;

            // Preselect the newest entry and load it so the user sees content immediately.
            const firstChip = strip.querySelector('.history-chip');
            if (firstChip) { firstChip.click(); }
        }

        /** Snapshot filename 20260412-215512 becomes 2026-04-12 21:55 for display. */
        function formatChipLabel(timestamp) {
            const m = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}/.exec(timestamp);
            if (!m) return timestamp;
            return m[1] + '-' + m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5];
        }

        function updateHistoryImage(previewId, filename, imageData) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;
            // Ignore stale replies — user may have clicked another chip or closed.
            if (card.dataset.viewingHistory !== filename) return;
            const container = card.querySelector('.image-container');
            if (!container) return;
            const overlay = container.querySelector('.loading-overlay');
            if (overlay) overlay.remove();
            let img = container.querySelector('img');
            if (!img) {
                img = document.createElement('img');
                img.alt = card.dataset.function + ' preview';
                container.appendChild(img);
            }
            // Preserve the latest render's data URL so we can restore it on close.
            if (!card.dataset.latestImage) {
                card.dataset.latestImage = img.src;
            }
            img.src = 'data:image/png;base64,' + imageData;
            img.className = 'fade-in';
        }

        function restoreLatestImage(card) {
            delete card.dataset.viewingHistory;
            const container = card.querySelector('.image-container');
            const img = container?.querySelector('img');
            if (img && card.dataset.latestImage) {
                img.src = card.dataset.latestImage;
                delete card.dataset.latestImage;
            }
        }

        function updateCardMetadata(card, p) {
            card.dataset.function = p.functionName;
            card.dataset.label = p.params.name || '';
            card.dataset.group = p.params.group || '';
            card.dataset.file = p.sourceFile || '';
            const title = card.querySelector('.card-title');
            if (title) {
                title.textContent = p.functionName + (p.params.name ? ' — ' + p.params.name : '');
            }
            const sub = card.querySelector('.card-subtitle');
            if (sub) sub.textContent = p.sourceFile || '';
        }

        /**
         * Incremental diff: update existing cards, add new ones, remove missing.
         * Keeps rendered images in place during refresh — they're replaced as
         * new images stream in from updateImage messages.
         */
        function renderPreviews(previews) {
            if (previews.length === 0) {
                grid.innerHTML = '';
                message.textContent = 'No @Preview functions found';
                message.style.display = 'block';
                return;
            }
            message.style.display = 'none';

            const newIds = new Set(previews.map(p => p.id));
            const existingCards = new Map();
            grid.querySelectorAll('.preview-card').forEach(card => {
                existingCards.set(card.dataset.previewId, card);
            });

            // Remove cards that no longer exist
            for (const [id, card] of existingCards) {
                if (!newIds.has(id)) card.remove();
            }

            // Add new cards / update existing ones, preserving order
            let lastInsertedCard = null;
            for (const p of previews) {
                const existing = existingCards.get(p.id);
                if (existing) {
                    updateCardMetadata(existing, p);
                    // Ensure correct position
                    if (lastInsertedCard) {
                        if (lastInsertedCard.nextSibling !== existing) {
                            grid.insertBefore(existing, lastInsertedCard.nextSibling);
                        }
                    } else if (grid.firstChild !== existing) {
                        grid.insertBefore(existing, grid.firstChild);
                    }
                    lastInsertedCard = existing;
                } else {
                    const card = createCard(p);
                    if (lastInsertedCard) {
                        grid.insertBefore(card, lastInsertedCard.nextSibling);
                    } else {
                        grid.insertBefore(card, grid.firstChild);
                    }
                    lastInsertedCard = card;
                }
            }
        }

        /** Add a subtle spinner overlay to every card during a stealth refresh. */
        function markAllLoading() {
            document.querySelectorAll('.preview-card').forEach(card => {
                const container = card.querySelector('.image-container');
                if (!container) return;
                // Skip if already has an overlay (e.g. previous refresh still running)
                if (container.querySelector('.loading-overlay')) return;
                // Don't add overlay if there's just a skeleton (nothing useful to cover)
                if (container.querySelector('.skeleton') && !container.querySelector('img')) return;
                const overlay = document.createElement('div');
                overlay.className = 'loading-overlay subtle';
                overlay.innerHTML = '<div class="spinner" aria-label="Refreshing"></div>';
                container.appendChild(overlay);
            });
        }

        function updateImage(previewId, imageData) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;
            const container = card.querySelector('.image-container');
            // Remove skeleton/spinner but keep error state if present
            const skeleton = container.querySelector('.skeleton');
            const overlay = container.querySelector('.loading-overlay');
            if (skeleton) skeleton.remove();
            if (overlay) overlay.remove();
            card.classList.remove('has-error');

            const newSrc = 'data:image/png;base64,' + imageData;

            // If the user is viewing a history snapshot, don't clobber it —
            // stash the new latest so closing the drawer reveals the update.
            if (card.dataset.viewingHistory) {
                card.dataset.latestImage = newSrc;
                return;
            }

            let img = container.querySelector('img');
            if (!img) {
                img = document.createElement('img');
                img.alt = card.dataset.function + ' preview';
                container.appendChild(img);
            }
            img.src = newSrc;
            img.className = 'fade-in';
        }

        window.addEventListener('message', event => {
            const msg = event.data;
            switch (msg.command) {
                case 'setPreviews': {
                    allPreviews = msg.previews;
                    moduleDir = msg.moduleDir;
                    renderPreviews(msg.previews);

                    const fns = [...new Set(msg.previews.map(p => p.functionName))].sort();
                    const labels = [...new Set(msg.previews.map(p => p.params.name).filter(Boolean))].sort();
                    const groups = [...new Set(msg.previews.map(p => p.params.group).filter(Boolean))].sort();
                    const files = [...new Set(msg.previews.map(p => p.sourceFile).filter(Boolean))].sort();

                    populateFilter(filterFile, files, 'files');
                    populateFilter(filterFunction, fns, 'functions');
                    populateFilter(filterLabel, labels, 'labels');
                    populateFilter(filterGroup, groups, 'groups');

                    restoreFilterState();
                    applyFilters();
                    applyLayout();
                    break;
                }

                case 'markAllLoading':
                    markAllLoading();
                    break;

                case 'updateImage':
                    updateImage(msg.previewId, msg.imageData);
                    break;

                case 'setModules': {
                    filterModule.innerHTML = '';
                    const autoOpt = document.createElement('option');
                    autoOpt.value = '';
                    autoOpt.textContent = 'Auto-detect';
                    filterModule.appendChild(autoOpt);
                    for (const m of msg.modules) {
                        const opt = document.createElement('option');
                        opt.value = m;
                        opt.textContent = m;
                        filterModule.appendChild(opt);
                    }
                    if (msg.selected) filterModule.value = msg.selected;
                    break;
                }

                case 'setLoading':
                    if (msg.previewId) {
                        const card = document.getElementById('preview-' + sanitizeId(msg.previewId));
                        if (card) {
                            const container = card.querySelector('.image-container');
                            if (!container.querySelector('.loading-overlay')) {
                                const overlay = document.createElement('div');
                                overlay.className = 'loading-overlay';
                                overlay.innerHTML = '<div class="spinner" aria-label="Rendering"></div>';
                                container.appendChild(overlay);
                            }
                        }
                    } else {
                        message.textContent = 'Building...';
                        message.style.display = 'block';
                    }
                    break;

                case 'setError':
                case 'setImageError': {
                    const errCard = document.getElementById('preview-' + sanitizeId(msg.previewId));
                    if (errCard) {
                        errCard.classList.add('has-error');
                        const container = errCard.querySelector('.image-container');
                        // Keep existing image visible under the error for setImageError
                        if (msg.command === 'setImageError') {
                            const existing = container.querySelector('img');
                            if (!existing) {
                                container.innerHTML = '<div class="error-message" role="alert">' + escapeHtml(msg.message) + '</div>';
                            }
                        } else {
                            container.innerHTML = '<div class="error-message" role="alert">' + escapeHtml(msg.message) + '</div>';
                        }
                    }
                    break;
                }

                case 'showMessage':
                    message.textContent = msg.text;
                    message.style.display = msg.text ? 'block' : 'none';
                    break;

                case 'setHistory':
                    showHistory(msg.previewId, msg.entries);
                    break;

                case 'updateHistoryImage':
                    updateHistoryImage(msg.previewId, msg.filename, msg.imageData);
                    break;
            }
        });

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
    })();
    </script>
</body>
</html>`;
    }
}

function getNonce(): string {
    let text = '';
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}
