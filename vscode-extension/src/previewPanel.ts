import * as vscode from 'vscode';
import { ExtensionToWebview, WebviewToExtension } from './types';

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
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, 'media', 'codicon.css'),
        );

        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource}; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
</head>
<body>
    <div class="toolbar" id="toolbar" role="toolbar" aria-label="Preview filters">
        <div class="select-wrapper">
            <select id="filter-function" title="Filter by function" aria-label="Function filter">
                <option value="all">All functions</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
        <div class="select-wrapper">
            <select id="filter-group" title="Filter by @Preview group" aria-label="Group filter">
                <option value="all">All groups</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
        <div class="select-wrapper">
            <select id="layout-mode" title="Layout" aria-label="Layout mode">
                <option value="grid">Grid</option>
                <option value="flow">Flow</option>
                <option value="column">Column</option>
                <option value="focus">Focus</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
    </div>

    <div id="message" class="message" role="status" aria-live="polite"></div>
    <div id="focus-controls" class="focus-controls" hidden>
        <button class="icon-button" id="btn-prev" title="Previous preview" aria-label="Previous preview">
            <i class="codicon codicon-arrow-left" aria-hidden="true"></i>
        </button>
        <span id="focus-position" aria-live="polite"></span>
        <button class="icon-button" id="btn-next" title="Next preview" aria-label="Next preview">
            <i class="codicon codicon-arrow-right" aria-hidden="true"></i>
        </button>
    </div>
    <div id="preview-grid" class="preview-grid" role="list" aria-label="Preview cards"></div>

    <script nonce="${nonce}">
    (function() {
        const vscode = acquireVsCodeApi();
        const state = vscode.getState() || { filters: {} };

        const grid = document.getElementById('preview-grid');
        const message = document.getElementById('message');
        const filterFunction = document.getElementById('filter-function');
        const filterGroup = document.getElementById('filter-group');
        const layoutMode = document.getElementById('layout-mode');
        const focusControls = document.getElementById('focus-controls');
        const btnPrev = document.getElementById('btn-prev');
        const btnNext = document.getElementById('btn-next');
        const focusPosition = document.getElementById('focus-position');

        let allPreviews = [];
        let moduleDir = '';
        let filterDebounce = null;
        let focusIndex = 0;

        // Restore layout preference
        if (state.layout && ['grid', 'flow', 'column', 'focus'].includes(state.layout)) {
            layoutMode.value = state.layout;
        }
        applyLayout();

        // Seed a placeholder so the view isn't blank during the ~1s boot
        // window before the extension posts its first message. Any real
        // message (Building…, empty-state notice, cards) will replace it.
        message.textContent = 'Loading Compose previews…';
        message.style.display = 'block';
        message.dataset.owner = 'fallback';

        layoutMode.addEventListener('change', () => {
            state.layout = layoutMode.value;
            vscode.setState(state);
            applyLayout();
        });

        btnPrev.addEventListener('click', () => navigateFocus(-1));
        btnNext.addEventListener('click', () => navigateFocus(1));

        for (const sel of [filterFunction, filterGroup]) {
            sel.addEventListener('change', () => {
                saveFilterState();
                if (filterDebounce) clearTimeout(filterDebounce);
                filterDebounce = setTimeout(applyFilters, 100);
            });
        }

        function saveFilterState() {
            state.filters = {
                fn: filterFunction.value,
                group: filterGroup.value,
            };
            vscode.setState(state);
        }

        function restoreFilterState() {
            const f = state.filters || {};
            if (f.fn && hasOption(filterFunction, f.fn)) filterFunction.value = f.fn;
            if (f.group && hasOption(filterGroup, f.group)) filterGroup.value = f.group;
        }

        function hasOption(select, value) {
            return Array.from(select.options).some(o => o.value === value);
        }

        function applyFilters() {
            const fnVal = filterFunction.value;
            const grpVal = filterGroup.value;

            let visibleCount = 0;
            document.querySelectorAll('.preview-card').forEach(card => {
                const show =
                    (fnVal === 'all' || card.dataset.function === fnVal) &&
                    (grpVal === 'all' || card.dataset.group === grpVal);
                card.classList.toggle('filtered-out', !show);
                if (show) visibleCount++;
            });

            // Only own the message when we have a filter-specific thing to
            // say. When there are no previews at all, the extension owns the
            // message (e.g. "No @Preview functions in this file") — clearing
            // it here was how the view went blank after a refresh.
            if (allPreviews.length > 0 && visibleCount === 0) {
                setMessage('No previews match the current filters', 'filter');
            } else if (message.dataset.owner === 'filter') {
                // We set this earlier; clear it now that it no longer applies.
                setMessage('', 'filter');
            }

            // Re-apply layout so focus mode updates correctly after filter change
            applyLayout();
        }

        // Central setter so applyFilters and incoming messages don't fight
        // over who owns the current text. The owner tag is used only to let
        // applyFilters clear its own message without touching extension-set
        // text (empty-file notice, build errors, etc.).
        function setMessage(text, owner) {
            message.textContent = text;
            message.style.display = text ? 'block' : 'none';
            if (text) {
                message.dataset.owner = owner || 'extension';
            } else {
                delete message.dataset.owner;
            }
            ensureNotBlank();
        }

        // Safety net: if the grid ends up empty *and* no message is showing,
        // surface a placeholder so the user doesn't stare at a void. This
        // shouldn't normally trigger — the extension sends an explicit
        // message for every empty state — but a silent blank view was the
        // original complaint, so this catches any future regressions.
        function ensureNotBlank() {
            const hasCards = grid.querySelector('.preview-card') !== null;
            const hasMessage = message.style.display !== 'none' && message.textContent;
            if (!hasCards && !hasMessage) {
                message.textContent = 'Preparing previews…';
                message.style.display = 'block';
                message.dataset.owner = 'fallback';
            }
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

        // Data-URL MIME for a preview image, derived from its renderOutput
        // extension. @ScrollingPreview(GIF) captures land at .gif; all
        // other captures are PNG. Browsers sniff magic bytes and would
        // actually render a GIF served as image/png — but declaring the
        // right type matters for the webview's img fallback/accessibility
        // paths and avoids a console warning when saving the preview.
        function mimeFor(renderOutput) {
            return typeof renderOutput === 'string' &&
                renderOutput.toLowerCase().endsWith('.gif')
                ? 'image/gif'
                : 'image/png';
        }

        // Per-preview carousel runtime state — imageData / errorMessage per
        // capture. Populated from updateImage / setImageError messages so
        // prev/next navigation can swap the visible <img> without a fresh
        // extension round-trip.
        // Map<previewId, [{ label, imageData, errorMessage }]>
        const cardCaptures = new Map();

        // Preview is shown with a carousel when it has >1 capture or a single
        // capture with a non-null dimension (e.g. an explicit 500ms snapshot).
        function isAnimatedPreview(p) {
            const caps = p.captures;
            if (caps.length > 1) return true;
            if (caps.length === 1) {
                const c = caps[0];
                return c.advanceTimeMillis != null || c.scroll != null;
            }
            return false;
        }

        function createCard(p) {
            const animated = isAnimatedPreview(p);
            const captures = p.captures;

            const card = document.createElement('div');
            card.className = 'preview-card' + (animated ? ' animated-card' : '');
            card.id = 'preview-' + sanitizeId(p.id);
            card.setAttribute('role', 'listitem');
            card.dataset.function = p.functionName;
            card.dataset.group = p.params.group || '';
            card.dataset.previewId = p.id;
            card.dataset.className = p.className;
            card.dataset.currentIndex = '0';
            cardCaptures.set(p.id, captures.map(c => ({
                label: c.label || '',
                renderOutput: c.renderOutput || '',
                imageData: null,
                errorMessage: null,
            })));

            const header = document.createElement('div');
            header.className = 'card-header';

            const titleRow = document.createElement('div');
            titleRow.className = 'card-title-row';

            const title = document.createElement('button');
            title.className = 'card-title';
            title.textContent = p.functionName + (p.params.name ? ' — ' + p.params.name : '');
            title.title = buildTooltip(p);
            title.addEventListener('click', () => {
                vscode.postMessage({
                    command: 'openFile',
                    className: p.className,
                    functionName: p.functionName,
                });
            });
            titleRow.appendChild(title);

            if (animated) {
                // Inline marker so the title row telegraphs "this one has
                // multiple captures"; the carousel strip under the image is
                // the interactive surface.
                const icon = document.createElement('i');
                icon.className = 'codicon codicon-play-circle animation-icon';
                icon.title = captures.length + ' captures';
                icon.setAttribute('aria-label', 'Animated preview (' + captures.length + ' captures)');
                titleRow.appendChild(icon);
            }

            // History button only appears when the Gradle plugin has been
            // configured with historyEnabled = true and at least one
            // snapshot exists on disk — signalled by p.hasHistory.
            if (p.hasHistory) {
                const historyBtn = document.createElement('button');
                historyBtn.className = 'icon-button card-history-btn';
                historyBtn.title = 'Show render history';
                historyBtn.setAttribute('aria-label', 'Show render history');
                historyBtn.innerHTML = '<i class="codicon codicon-history" aria-hidden="true"></i>';
                historyBtn.addEventListener('click', () => {
                    vscode.postMessage({ command: 'showHistory', previewId: p.id });
                });
                titleRow.appendChild(historyBtn);
            }

            // Stale-tier refresh button — only attached up front for cards
            // already known to be stale at setPreviews time. updateStaleBadges
            // also adds/removes it on subsequent renders. Placed before the
            // header is appended so its DOM order stays predictable.
            applyStaleBadge(card, false);

            header.appendChild(titleRow);
            card.appendChild(header);

            const imgContainer = document.createElement('div');
            imgContainer.className = 'image-container';
            const skeleton = document.createElement('div');
            skeleton.className = 'skeleton';
            skeleton.setAttribute('aria-label', 'Loading preview');
            imgContainer.appendChild(skeleton);
            card.appendChild(imgContainer);

            // ATF legend + overlay layer — rendered in the webview (not
            // baked into the PNG) so rows stay interactive: hovering a
            // finding highlights its bounds on the clean image. Populated
            // only when findings exist; the overlay layer's boxes get
            // computed lazily once the image is loaded (see buildA11yOverlay).
            if (p.a11yFindings && p.a11yFindings.length > 0) {
                const overlay = document.createElement('div');
                overlay.className = 'a11y-overlay';
                overlay.setAttribute('aria-hidden', 'true');
                imgContainer.appendChild(overlay);
                card.appendChild(buildA11yLegend(p));
            }

            const variantLabel = buildVariantLabel(p);
            if (variantLabel) {
                const badge = document.createElement('div');
                badge.className = 'variant-badge';
                badge.textContent = variantLabel;
                card.appendChild(badge);
            }

            if (animated) {
                card.appendChild(buildFrameControls(card));
            }

            // Lazy-built history drawer — only populated when the user clicks
            // the history button and the extension returns entries.
            const drawer = document.createElement('div');
            drawer.className = 'history-drawer';
            drawer.hidden = true;
            card.appendChild(drawer);
            return card;
        }

        // Variant label for one ResourceCapture: 'xhdpi · CIRCLE', 'night-xhdpi',
        // or '' when both qualifiers and shape are absent. Built client-side so
        // ResourceCapture's wire shape stays untouched (composables lean on a
        // pre-baked .label, but the resource path isn't worth threading the
        // same metadata through extension.ts for what amounts to a string
        // concat).
        function resourceCaptureLabel(capture) {
            const v = capture.variant || {};
            const parts = [];
            if (v.qualifiers) parts.push(v.qualifiers);
            if (v.shape) parts.push(v.shape);
            return parts.join(' · ');
        }

        // Build one card for a ResourcePreview. Mirrors createCard's shape so
        // the carousel / skeleton / frame-controls plumbing stays shared, but
        // diverges on:
        //   - title click → opens the resource's source XML, not a Kotlin file.
        //   - subtitle (variant-badge slot) → resource type + optional
        //     'used by …' summary derived from manifest references.
        //   - no a11y findings, no group filter, no history drawer (resources
        //     don't carry those today).
        function createResourceCard(r, refsByResource, module) {
            const captures = (r.captures && r.captures.length > 0)
                ? r.captures
                : [{ variant: null, renderOutput: '' }];
            const animated = captures.length > 1;

            const card = document.createElement('div');
            card.className = 'preview-card resource-card' + (animated ? ' animated-card' : '');
            card.id = 'resource-' + sanitizeId(r.id);
            card.setAttribute('role', 'listitem');
            card.dataset.previewId = r.id;
            card.dataset.resourceType = r.type || '';
            card.dataset.currentIndex = '0';

            cardCaptures.set(r.id, captures.map(c => ({
                label: resourceCaptureLabel(c),
                renderOutput: c.renderOutput || '',
                imageData: null,
                errorMessage: null,
            })));

            const header = document.createElement('div');
            header.className = 'card-header';

            const titleRow = document.createElement('div');
            titleRow.className = 'card-title-row';

            const title = document.createElement('button');
            title.className = 'card-title';
            title.textContent = r.id;
            // Pick the default-qualifier source file (key '') if present;
            // otherwise the first qualified entry. anydpi-v26 adaptive icons
            // typically only have an 'anydpi-v26' key, so this falls back to
            // it without special-casing the type.
            const sources = r.sourceFiles || {};
            const defaultSource = sources[''];
            const firstSource = defaultSource || Object.values(sources)[0] || null;
            if (firstSource) {
                title.title = 'Open ' + firstSource;
                title.addEventListener('click', () => {
                    vscode.postMessage({
                        command: 'openResourceSource',
                        module,
                        sourceFile: firstSource,
                    });
                });
            } else {
                title.disabled = true;
                title.title = 'No source file for this resource';
            }
            titleRow.appendChild(title);

            if (animated) {
                const icon = document.createElement('i');
                icon.className = 'codicon codicon-play-circle animation-icon';
                icon.title = captures.length + ' captures';
                icon.setAttribute('aria-label',
                    'Resource with ' + captures.length + ' captures');
                titleRow.appendChild(icon);
            }
            header.appendChild(titleRow);
            card.appendChild(header);

            const imgContainer = document.createElement('div');
            imgContainer.className = 'image-container';
            const skeleton = document.createElement('div');
            skeleton.className = 'skeleton';
            skeleton.setAttribute('aria-label', 'Loading resource preview');
            imgContainer.appendChild(skeleton);
            card.appendChild(imgContainer);

            const subtitleParts = [r.type];
            const refs = refsByResource.get(r.id) || [];
            if (refs.length > 0) {
                // Compact summary — e.g. 'used by application@icon, MainActivity@icon'.
                const refSummary = refs.map(ref => {
                    const owner = ref.componentName
                        ? ref.componentName.substring(ref.componentName.lastIndexOf('.') + 1)
                        : ref.componentKind;
                    const attr = (ref.attributeName || '').replace(/^android:/, '');
                    return owner + '@' + attr;
                }).join(', ');
                subtitleParts.push('used by ' + refSummary);
            }
            const badge = document.createElement('div');
            badge.className = 'variant-badge';
            badge.textContent = subtitleParts.join(' · ');
            card.appendChild(badge);

            if (animated) {
                card.appendChild(buildFrameControls(card));
            }
            return card;
        }

        // Two stacked sections in the grid:
        //   1. 'Declared in AndroidManifest.xml' — resources with at least one
        //      manifestReferences row pointing at them.
        //   2. 'Other resources' — everything else.
        // When section 1 is empty (manifest references nothing the renderer
        // produced — possible if the consumer's manifest references a raster
        // mipmap we don't render), the section header is omitted and only
        // section 2 shows.
        function renderResources(resources, references) {
            grid.innerHTML = '';
            grid.classList.add('resources-mode');

            // Index references by "<resourceType>/<resourceName>" so the card
            // builder can attach a 'used by …' summary in O(1).
            const refsByResource = new Map();
            for (const ref of references) {
                const id = (ref.resourceType || '') + '/' + (ref.resourceName || '');
                if (!refsByResource.has(id)) refsByResource.set(id, []);
                refsByResource.get(id).push(ref);
            }

            const declared = [];
            const other = [];
            for (const r of resources) {
                if (refsByResource.has(r.id)) declared.push(r);
                else other.push(r);
            }
            // Within each section, sort by id so the order is stable across
            // discovery-task runs (which use a LinkedHashMap but the order
            // shifts when a new resource is added mid-list).
            declared.sort((a, b) => a.id.localeCompare(b.id));
            other.sort((a, b) => a.id.localeCompare(b.id));

            if (declared.length > 0) {
                const heading = document.createElement('h3');
                heading.className = 'resources-section-heading';
                heading.textContent = 'Declared in AndroidManifest.xml';
                grid.appendChild(heading);
                for (const r of declared) {
                    grid.appendChild(createResourceCard(r, refsByResource, currentResourceModule));
                }
            }
            if (other.length > 0) {
                if (declared.length > 0) {
                    const heading = document.createElement('h3');
                    heading.className = 'resources-section-heading';
                    heading.textContent = 'Other resources';
                    grid.appendChild(heading);
                }
                for (const r of other) {
                    grid.appendChild(createResourceCard(r, refsByResource, currentResourceModule));
                }
            }
        }
        // Module name supplied alongside the resources payload — used by card
        // titles to resolve their click target. Held in a webview-scope 'let'
        // (rather than threaded through every render call) so updateImage and
        // similar follow-up messages don't need to know about it.
        let currentResourceModule = '';

        function buildFrameControls(card) {
            const bar = document.createElement('div');
            bar.className = 'frame-controls';

            const prev = document.createElement('button');
            prev.className = 'icon-button frame-prev';
            prev.setAttribute('aria-label', 'Previous capture');
            prev.title = 'Previous capture';
            prev.innerHTML = '<i class="codicon codicon-chevron-left" aria-hidden="true"></i>';
            prev.addEventListener('click', () => stepFrame(card, -1));

            const indicator = document.createElement('span');
            indicator.className = 'frame-indicator';
            indicator.setAttribute('aria-live', 'polite');

            const next = document.createElement('button');
            next.className = 'icon-button frame-next';
            next.setAttribute('aria-label', 'Next capture');
            next.title = 'Next capture';
            next.innerHTML = '<i class="codicon codicon-chevron-right" aria-hidden="true"></i>';
            next.addEventListener('click', () => stepFrame(card, 1));

            bar.appendChild(prev);
            bar.appendChild(indicator);
            bar.appendChild(next);

            // Arrow keys when the carousel has focus.
            bar.tabIndex = 0;
            bar.addEventListener('keydown', (e) => {
                if (e.key === 'ArrowLeft') { stepFrame(card, -1); e.preventDefault(); }
                else if (e.key === 'ArrowRight') { stepFrame(card, 1); e.preventDefault(); }
            });

            // Seed indicator text so it's not blank before any image arrives.
            requestAnimationFrame(() => updateFrameIndicator(card));
            return bar;
        }

        function stepFrame(card, delta) {
            const caps = cardCaptures.get(card.dataset.previewId);
            if (!caps) return;
            const cur = parseInt(card.dataset.currentIndex || '0', 10);
            const next = Math.max(0, Math.min(caps.length - 1, cur + delta));
            if (next === cur) return;
            card.dataset.currentIndex = String(next);
            showFrame(card, next);
        }

        function showFrame(card, index) {
            const caps = cardCaptures.get(card.dataset.previewId);
            if (!caps) return;
            const capture = caps[index];
            if (!capture) return;
            const container = card.querySelector('.image-container');
            if (!container) return;

            if (capture.imageData) {
                const skeleton = container.querySelector('.skeleton');
                const errorMsg = container.querySelector('.error-message');
                if (skeleton) skeleton.remove();
                if (errorMsg) errorMsg.remove();
                card.classList.remove('has-error');
                let img = container.querySelector('img');
                if (!img) {
                    img = document.createElement('img');
                    img.alt = card.dataset.function + ' preview';
                    container.appendChild(img);
                }
                img.src = 'data:' + mimeFor(capture.renderOutput) + ';base64,' + capture.imageData;
                img.className = 'fade-in';
            } else if (capture.errorMessage) {
                container.innerHTML = '<div class="error-message" role="alert">' + escapeHtml(capture.errorMessage) + '</div>';
                card.classList.add('has-error');
            } else {
                // No data for this capture yet — render will fill it in later.
                const existing = container.querySelector('img');
                if (existing) existing.remove();
                if (!container.querySelector('.skeleton')) {
                    const s = document.createElement('div');
                    s.className = 'skeleton';
                    s.setAttribute('aria-label', 'Loading capture');
                    container.appendChild(s);
                }
            }
            updateFrameIndicator(card);
        }

        function updateFrameIndicator(card) {
            const indicator = card.querySelector('.frame-indicator');
            const prevBtn = card.querySelector('.frame-prev');
            const nextBtn = card.querySelector('.frame-next');
            if (!indicator) return;
            const caps = cardCaptures.get(card.dataset.previewId);
            if (!caps) return;
            const idx = parseInt(card.dataset.currentIndex || '0', 10);
            const capture = caps[idx];
            const label = capture && capture.label ? capture.label : '\u2014';
            indicator.textContent = (idx + 1) + ' / ' + caps.length + ' \u00B7 ' + label;
            if (prevBtn) prevBtn.disabled = idx === 0;
            if (nextBtn) nextBtn.disabled = idx === caps.length - 1;
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
            close.className = 'icon-button history-close';
            close.innerHTML = '<i class="codicon codicon-close" aria-hidden="true"></i>';
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
            const m = /^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})\\d{2}/.exec(timestamp);
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
            card.dataset.group = p.params.group || '';
            const title = card.querySelector('.card-title');
            if (title) {
                title.textContent = p.functionName + (p.params.name ? ' — ' + p.params.name : '');
                title.title = buildTooltip(p);
            }
            // Refresh capture labels in place. If the capture count changed
            // (e.g. user edited @RoboComposePreviewOptions) we preserve
            // already-received imageData for renderOutputs that carry over.
            const newCaps = p.captures.map(c => ({
                renderOutput: c.renderOutput,
                label: c.label || '',
            }));
            const prior = cardCaptures.get(p.id) || [];
            // Match by index rather than renderOutput since filenames may
            // legitimately change (e.g. a preview gains a @RoboComposePreviewOptions
            // annotation). Mismatched positions just reset to null-image.
            const mergedCaps = newCaps.map((nc, i) => ({
                label: nc.label,
                renderOutput: nc.renderOutput || '',
                imageData: prior[i]?.imageData ?? null,
                errorMessage: prior[i]?.errorMessage ?? null,
            }));
            cardCaptures.set(p.id, mergedCaps);
            const curIdx = parseInt(card.dataset.currentIndex || '0', 10);
            if (curIdx >= mergedCaps.length) {
                card.dataset.currentIndex = String(Math.max(0, mergedCaps.length - 1));
            }
            if (isAnimatedPreview(p)) updateFrameIndicator(card);
            const variantLabel = buildVariantLabel(p);
            let badge = card.querySelector('.variant-badge');
            if (variantLabel) {
                if (!badge) {
                    badge = document.createElement('div');
                    badge.className = 'variant-badge';
                    card.appendChild(badge);
                }
                badge.textContent = variantLabel;
            } else if (badge) {
                badge.remove();
            }

            // Refresh the a11y legend + overlay in place when findings
            // change (e.g. toggling a11y on turns findings from null → list,
            // or a fresh render updates the set). Tear down the old nodes
            // and rebuild: simpler than reconciling row-by-row for what is
            // a rare event.
            const existingLegend = card.querySelector('.a11y-legend');
            const existingOverlay = card.querySelector('.a11y-overlay');
            if (existingLegend) existingLegend.remove();
            if (existingOverlay) existingOverlay.innerHTML = '';
            if (p.a11yFindings && p.a11yFindings.length > 0) {
                const container = card.querySelector('.image-container');
                if (container && !container.querySelector('.a11y-overlay')) {
                    const overlay = document.createElement('div');
                    overlay.className = 'a11y-overlay';
                    overlay.setAttribute('aria-hidden', 'true');
                    container.appendChild(overlay);
                }
                const legend = buildA11yLegend(p);
                card.appendChild(legend);
                // Repopulate box geometry if the image is already loaded —
                // otherwise updateImage's load handler will pick it up on
                // the next render cycle.
                const img = card.querySelector('.image-container img');
                if (img && img.complete && img.naturalWidth > 0) {
                    buildA11yOverlay(card, p.a11yFindings, img);
                }
            } else if (existingOverlay) {
                existingOverlay.remove();
            }
        }

        /** Shared between createCard (new card) and updateCardMetadata (existing card). */
        function buildA11yLegend(p) {
            const legend = document.createElement('div');
            legend.className = 'a11y-legend';
            const header = document.createElement('div');
            header.className = 'a11y-legend-header';
            header.textContent = 'Accessibility (' + p.a11yFindings.length + ')';
            legend.appendChild(header);
            p.a11yFindings.forEach((f, idx) => {
                const row = document.createElement('div');
                row.className = 'a11y-row a11y-level-' + (f.level || 'info').toLowerCase();
                row.dataset.previewId = p.id;
                row.dataset.findingIdx = String(idx);

                const badge = document.createElement('span');
                badge.className = 'a11y-badge';
                badge.textContent = String(idx + 1);
                row.appendChild(badge);

                const text = document.createElement('div');
                text.className = 'a11y-text';
                const title = document.createElement('div');
                title.className = 'a11y-title';
                title.textContent = f.level + ' · ' + f.type;
                const msg = document.createElement('div');
                msg.className = 'a11y-msg';
                msg.textContent = f.message;
                text.appendChild(title);
                text.appendChild(msg);
                if (f.viewDescription) {
                    const elt = document.createElement('div');
                    elt.className = 'a11y-elt';
                    elt.textContent = f.viewDescription;
                    text.appendChild(elt);
                }
                row.appendChild(text);
                row.addEventListener('mouseenter', () => highlightA11yFinding(p.id, idx));
                row.addEventListener('mouseleave', () => highlightA11yFinding(p.id, null));
                legend.appendChild(row);
            });
            return legend;
        }

        // Compact single-line variant summary rendered in a persistent badge
        // on each card. Longer-form info still lives in the hover tooltip
        // (buildTooltip) — here we only surface what distinguishes siblings:
        // name/group/device first, then dimensions, non-default fontScale,
        // uiMode. Skips redundant bits (e.g. no "1.0×" for default font).
        function buildVariantLabel(p) {
            const parts = [];
            const primary = p.params.name
                || p.params.group
                || shortDevice(p.params.device);
            if (primary) parts.push(primary);
            if (p.params.widthDp && p.params.heightDp) {
                parts.push(p.params.widthDp + '\u00D7' + p.params.heightDp);
            }
            if (p.params.fontScale && p.params.fontScale !== 1.0) {
                parts.push(p.params.fontScale + '\u00D7');
            }
            if (p.params.uiMode) parts.push('uiMode ' + p.params.uiMode);
            if (p.params.locale) parts.push(p.params.locale);
            return parts.join(' \u00B7 ');
        }

        function shortDevice(d) {
            if (!d) return '';
            return d.replace(/^id:/, '').replace(/_/g, ' ');
        }

        function buildTooltip(p) {
            const base = 'Open source: ' + p.className + '.' + p.functionName;
            const parts = [];
            if (p.params.name) parts.push(p.params.name);
            if (p.params.device) parts.push(p.params.device);
            if (p.params.widthDp && p.params.heightDp) {
                parts.push(p.params.widthDp + '\u00D7' + p.params.heightDp + 'dp');
            }
            if (p.params.fontScale && p.params.fontScale !== 1.0) {
                parts.push('font ' + p.params.fontScale + '\u00D7');
            }
            if (p.params.uiMode) parts.push('uiMode=' + p.params.uiMode);
            if (p.params.locale) parts.push(p.params.locale);
            if (p.params.group) parts.push('group: ' + p.params.group);
            return parts.length ? base + '\\n' + parts.join(' \u00B7 ') : base;
        }

        // Scale image containers so preview variants at different device sizes
        // (e.g. wearos_large_round 227dp vs wearos_small_round 192dp) render at
        // relative sizes in fixed-layout modes. Only applied when we have real
        // widthDp/heightDp — variants without known dimensions fall back to
        // the default CSS (full card width, auto aspect).
        function applyRelativeSizing(previews) {
            const widths = previews
                .map(p => p.params.widthDp || 0)
                .filter(w => w > 0);
            const maxW = widths.length > 0 ? Math.max.apply(null, widths) : 0;
            for (const p of previews) {
                const card = document.getElementById('preview-' + sanitizeId(p.id));
                if (!card) continue;
                const w = p.params.widthDp;
                const h = p.params.heightDp;
                if (w && h && maxW > 0) {
                    card.style.setProperty('--size-ratio', (w / maxW).toFixed(4));
                    card.style.setProperty('--aspect-ratio', w + ' / ' + h);
                } else {
                    card.style.removeProperty('--size-ratio');
                    card.style.removeProperty('--aspect-ratio');
                }
            }
        }

        /**
         * Incremental diff: update existing cards, add new ones, remove missing.
         * Keeps rendered images in place during refresh — they're replaced as
         * new images stream in from updateImage messages.
         */
        function renderPreviews(previews) {
            if (previews.length === 0) {
                // Defensive fallback — the extension now always sends an
                // explicit showMessage for empty states, so this branch
                // shouldn't normally fire. Kept so the view never ends up
                // with an empty grid + empty message if a bug slips through.
                grid.innerHTML = '';
                setMessage('No @Preview functions found', 'empty');
                return;
            }
            // Clear transient owner messages now that we have cards. The
            // 'loading' Building… banner gets clobbered here so cards aren't
            // hidden under it while images stream in. 'extension'-owned
            // messages (build errors, empty-state notices) are left alone —
            // those are terminal states the extension is asserting and the
            // caller wouldn't be sending setPreviews alongside them anyway.
            if (message.dataset.owner && message.dataset.owner !== 'extension') {
                setMessage('', message.dataset.owner);
            }

            const newIds = new Set(previews.map(p => p.id));
            const existingCards = new Map();
            grid.querySelectorAll('.preview-card').forEach(card => {
                existingCards.set(card.dataset.previewId, card);
            });

            // Remove cards that no longer exist — drop their cached capture
            // data so stale entries don't pile up if a preview is renamed.
            for (const [id, card] of existingCards) {
                if (!newIds.has(id)) {
                    cardCaptures.delete(id);
                    card.remove();
                }
            }

            // Refresh per-preview findings cache so updateImage can attach
            // them to each new image load. Drop stale entries (preview
            // removed) so the map doesn't grow across sessions.
            cardA11yFindings.clear();
            for (const p of previews) {
                if (p.a11yFindings && p.a11yFindings.length > 0) {
                    cardA11yFindings.set(p.id, p.a11yFindings);
                }
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

        /**
         * Toggles the "stale render — click to refresh" affordance on a card.
         *
         * Called both at card creation time (so cards born stale have the
         * badge from the start) and from updateStaleBadges after each
         * setPreviews (state can flip when the user toggles between fast
         * and full saves). Idempotent: skips if the desired state already
         * matches the DOM, so re-running on an unchanged card is cheap.
         *
         * Why a button rather than a static badge: clicking it is the only
         * way the user can recover a fresh GIF/long-scroll image without
         * editing source. Keeping it inside the title row puts it in the
         * same affordance band as history / open-source buttons.
         */
        function applyStaleBadge(card, isStale) {
            const titleRow = card.querySelector('.card-title-row');
            if (!titleRow) return;
            const existing = card.querySelector('.card-stale-btn');
            if (isStale && !existing) {
                const btn = document.createElement('button');
                btn.className = 'icon-button card-stale-btn';
                btn.title = 'Stale heavy capture — click to render at full tier';
                btn.setAttribute('aria-label', 'Refresh stale capture');
                btn.innerHTML = '<i class="codicon codicon-warning" aria-hidden="true"></i>';
                btn.addEventListener('click', () => {
                    vscode.postMessage({
                        command: 'refreshHeavy',
                        previewId: card.dataset.previewId,
                    });
                });
                titleRow.appendChild(btn);
                card.classList.add('is-stale');
            } else if (!isStale && existing) {
                existing.remove();
                card.classList.remove('is-stale');
            }
        }

        /**
         * Apply the heavy-stale badge state across all cards after
         * setPreviews fires. The extension passes a list of preview IDs
         * whose heavy captures weren't refreshed this run; everything else
         * gets its badge cleared.
         */
        function updateStaleBadges(heavyStaleIds) {
            const stale = new Set(heavyStaleIds || []);
            grid.querySelectorAll('.preview-card').forEach(card => {
                applyStaleBadge(card, stale.has(card.dataset.previewId));
            });
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

        /**
         * Builds the absolutely-positioned overlay boxes on top of the
         * rendered preview image. Runs once per image load — boundsInScreen
         * is in the image pixel coordinates, so we translate to % of the
         * image natural dimensions. The overlay layer scales with the image
         * (position absolute, inset 0 inside image-container which sizes to
         * the img), so % bounds stay correct across layout changes without
         * a resize handler.
         */
        function buildA11yOverlay(card, findings, img) {
            const overlay = card.querySelector('.a11y-overlay');
            if (!overlay) return;
            overlay.innerHTML = '';
            const natW = img.naturalWidth;
            const natH = img.naturalHeight;
            if (!natW || !natH) return;
            findings.forEach((f, idx) => {
                const bounds = parseBounds(f.boundsInScreen);
                if (!bounds) return;
                const box = document.createElement('div');
                box.className = 'a11y-box a11y-level-' + (f.level || 'info').toLowerCase();
                box.dataset.findingIdx = String(idx);
                box.style.left = (bounds.left / natW * 100) + '%';
                box.style.top = (bounds.top / natH * 100) + '%';
                box.style.width = ((bounds.right - bounds.left) / natW * 100) + '%';
                box.style.height = ((bounds.bottom - bounds.top) / natH * 100) + '%';
                const badge = document.createElement('span');
                badge.className = 'a11y-badge';
                badge.textContent = String(idx + 1);
                box.appendChild(badge);
                overlay.appendChild(box);
            });
        }

        function parseBounds(s) {
            if (!s) return null;
            const parts = s.split(',').map(x => parseInt(x.trim(), 10));
            if (parts.length !== 4 || parts.some(isNaN)) return null;
            return { left: parts[0], top: parts[1], right: parts[2], bottom: parts[3] };
        }

        /** Adds/removes .a11y-active on matching legend row + overlay box. */
        function highlightA11yFinding(previewId, idx) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;
            card.querySelectorAll('.a11y-row.a11y-active, .a11y-box.a11y-active').forEach(el => {
                el.classList.remove('a11y-active');
            });
            if (idx === null || idx === undefined) return;
            const sel = '[data-finding-idx="' + idx + '"]';
            card.querySelectorAll(sel).forEach(el => el.classList.add('a11y-active'));
        }

        function updateImage(previewId, captureIndex, imageData) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;

            // Cache so carousel navigation can restore this capture without
            // a fresh extension round-trip.
            const caps = cardCaptures.get(previewId);
            if (caps && caps[captureIndex]) {
                caps[captureIndex].imageData = imageData;
                caps[captureIndex].errorMessage = null;
            }

            // Only paint the <img> if the currently-displayed capture is the
            // one that just arrived. Otherwise the cached bytes wait for
            // prev/next.
            const cur = parseInt(card.dataset.currentIndex || '0', 10);
            if (cur !== captureIndex) {
                if (caps) updateFrameIndicator(card);
                return;
            }

            const container = card.querySelector('.image-container');
            // Tear down every prior state before showing the new image.
            // Leftover .error-message divs here are what caused the
            // "Render pending — save the file to trigger a render" banner
            // to stay visible forever even after a successful render.
            const skeleton = container.querySelector('.skeleton');
            const overlay = container.querySelector('.loading-overlay');
            const errorMsg = container.querySelector('.error-message');
            if (skeleton) skeleton.remove();
            if (overlay) overlay.remove();
            if (errorMsg) errorMsg.remove();
            card.classList.remove('has-error');

            const ro = caps && caps[captureIndex] ? caps[captureIndex].renderOutput : '';
            const newSrc = 'data:' + mimeFor(ro) + ';base64,' + imageData;

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

            if (caps) updateFrameIndicator(card);

            // Re-build the a11y overlay once the image natural dimensions
            // are known. Data-URL srcs may resolve synchronously; in that
            // case img.complete is true and load will not fire, so we
            // check both. Findings are stashed at setPreviews time via the
            // renderPreviews pipeline.
            const findings = cardA11yFindings.get(previewId);
            if (findings && findings.length > 0) {
                const apply = () => buildA11yOverlay(card, findings, img);
                if (img.complete && img.naturalWidth > 0) {
                    apply();
                } else {
                    img.addEventListener('load', apply, { once: true });
                }
            }
        }

        // previewId -> findings. Populated from setPreviews so updateImage can
        // re-read the list on every image (re)load without re-querying the
        // DOM for data attributes.
        const cardA11yFindings = new Map();

        window.addEventListener('message', event => {
            const msg = event.data;
            switch (msg.command) {
                case 'setResources': {
                    // Resources mode is fully orthogonal to the composable
                    // preview state — clear the latter so a previous
                    // setPreviews payload doesn't leak into the resources
                    // view's filter dropdowns or relative-sizing pass.
                    allPreviews = [];
                    currentResourceModule = msg.module;
                    moduleDir = msg.module;
                    renderResources(msg.resources, msg.references);
                    // Function / group filters are composable concepts;
                    // hide the toolbar entries by populating them empty so
                    // the dropdowns don't dangle stale options.
                    populateFilter(filterFunction, [], 'functions');
                    populateFilter(filterGroup, [], 'groups');
                    setMessage('', 'extension');
                    break;
                }

                case 'setPreviews': {
                    grid.classList.remove('resources-mode');
                    allPreviews = msg.previews;
                    moduleDir = msg.moduleDir;
                    renderPreviews(msg.previews);
                    applyRelativeSizing(msg.previews);
                    // Stale-tier badges depend on the latest render's tier
                    // (sent from the extension as heavyStaleIds). Apply
                    // *after* renderPreviews so the badge attaches to cards
                    // that were just inserted, not stripped by a stale-state
                    // diff from the previous setPreviews.
                    updateStaleBadges(msg.heavyStaleIds);

                    const fns = [...new Set(msg.previews.map(p => p.functionName))].sort();
                    const groups = [...new Set(msg.previews.map(p => p.params.group).filter(Boolean))].sort();

                    populateFilter(filterFunction, fns, 'functions');
                    populateFilter(filterGroup, groups, 'groups');

                    restoreFilterState();
                    applyFilters();
                    applyLayout();
                    break;
                }

                case 'markAllLoading':
                    markAllLoading();
                    break;

                case 'clearAll':
                    allPreviews = [];
                    grid.innerHTML = '';
                    // Don't clear the message here — if it came with a
                    // follow-up showMessage (the usual pattern) it'll be
                    // replaced; if not, ensureNotBlank will backstop a
                    // placeholder so the view never ends up empty+silent.
                    ensureNotBlank();
                    break;

                case 'updateImage':
                    updateImage(msg.previewId, msg.captureIndex || 0, msg.imageData);
                    break;

                case 'setModules':
                    // Module selector removed from UI — module is resolved from the active editor.
                    break;

                case 'setFunctionFilter': {
                    // Driven by the gutter-icon hover link: narrow the grid
                    // to a single @Preview function. If the option isn't yet
                    // in the dropdown (arrived before setPreviews populated
                    // it) add it so the value sticks and filter still applies.
                    const fn = msg.functionName;
                    if (!hasOption(filterFunction, fn)) {
                        const opt = document.createElement('option');
                        opt.value = fn;
                        opt.textContent = fn;
                        filterFunction.appendChild(opt);
                    }
                    filterFunction.value = fn;
                    saveFilterState();
                    applyFilters();
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
                        // 'loading' (not 'extension') so renderPreviews can
                        // clear it the moment cards arrive — otherwise the
                        // banner sits on top of skeleton cards while images
                        // stream in, which looks like the build is stuck.
                        setMessage('Building…', 'loading');
                    }
                    break;

                case 'setError':
                case 'setImageError': {
                    const errCard = document.getElementById('preview-' + sanitizeId(msg.previewId));
                    if (errCard) {
                        // Stash per-capture error so carousel navigation
                        // restores the message when the user returns to
                        // that specific capture. setError is preview-wide
                        // (captureIndex defaulted to 0) — applies to the
                        // representative image container only.
                        const captureIndex = msg.command === 'setImageError' ? (msg.captureIndex || 0) : 0;
                        const caps = cardCaptures.get(msg.previewId);
                        if (caps && caps[captureIndex]) {
                            caps[captureIndex].errorMessage = msg.message;
                            caps[captureIndex].imageData = null;
                        }
                        const cur = parseInt(errCard.dataset.currentIndex || '0', 10);
                        if (caps && cur !== captureIndex) break;

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
                    setMessage(msg.text, 'extension');
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
