import * as vscode from 'vscode';
import { ExtensionToWebview, WebviewToExtension } from './types';

export class PreviewPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = 'composePreview.panel';

    private view?: vscode.WebviewView;
    private extensionUri: vscode.Uri;
    private onMessage: (msg: WebviewToExtension) => void;
    private shouldRestoreVisibility: () => boolean;

    constructor(
        extensionUri: vscode.Uri,
        onMessage: (msg: WebviewToExtension) => void,
        shouldRestoreVisibility: () => boolean = () => false,
    ) {
        this.extensionUri = extensionUri;
        this.onMessage = onMessage;
        this.shouldRestoreVisibility = shouldRestoreVisibility;
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
        webviewView.onDidChangeVisibility(() => {
            if (webviewView.visible || !this.shouldRestoreVisibility()) { return; }
            void vscode.commands.executeCommand(`${PreviewPanel.viewId}.focus`);
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
    <div id="progress-bar" class="progress-bar" role="progressbar"
         aria-label="Refresh progress"
         aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
        <div class="progress-label" id="progress-label"></div>
        <div class="progress-track">
            <div class="progress-fill"></div>
        </div>
    </div>
    <div id="compile-errors" class="compile-errors" role="alert" hidden>
        <div class="compile-errors-header">
            <i class="codicon codicon-error" aria-hidden="true"></i>
            <span id="compile-errors-title">Compile errors</span>
        </div>
        <div id="compile-errors-list" class="compile-errors-list"></div>
        <div class="compile-errors-footnote">Showing last successful render.</div>
    </div>
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
        <button class="icon-button" id="btn-stop-interactive-global" title="Stop live preview"
                aria-label="Stop live preview" hidden>
            <i class="codicon codicon-debug-stop" aria-hidden="true"></i>
        </button>
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
        <button class="icon-button" id="btn-diff-head" title="Diff vs last archived render (HEAD)" aria-label="Diff vs HEAD">
            <i class="codicon codicon-git-compare" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-diff-main" title="Diff vs the latest render archived on main" aria-label="Diff vs main">
            <i class="codicon codicon-source-control" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-launch-device" title="Launch on connected Android device" aria-label="Launch on device">
            <i class="codicon codicon-device-mobile" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-a11y-overlay" title="Show accessibility overlay"
                aria-label="Toggle accessibility overlay" aria-pressed="false">
            <i class="codicon codicon-eye" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-interactive" title="Daemon not ready — live mode unavailable"
                aria-label="Toggle live (interactive) mode" aria-pressed="false" disabled hidden>
            <i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-stop-interactive" title="Stop live preview"
                aria-label="Stop live preview" hidden>
            <i class="codicon codicon-debug-stop" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-exit-focus" title="Exit focus mode" aria-label="Exit focus mode">
            <i class="codicon codicon-close" aria-hidden="true"></i>
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
        const btnDiffHead = document.getElementById('btn-diff-head');
        const btnDiffMain = document.getElementById('btn-diff-main');
        const btnLaunchDevice = document.getElementById('btn-launch-device');
        const btnA11yOverlay = document.getElementById('btn-a11y-overlay');
        const btnInteractive = document.getElementById('btn-interactive');
        const btnStopInteractiveGlobal = document.getElementById('btn-stop-interactive-global');
        const btnStopInteractive = document.getElementById('btn-stop-interactive');
        const btnExitFocus = document.getElementById('btn-exit-focus');
        // D2 — focus-mode a11y overlay toggle. Off by default; turning it on subscribes the
        // focused preview to a11y/atf + a11y/hierarchy via the extension, off unsubscribes.
        // Also gates the panel-side hierarchy overlay so the existing finding overlay (which
        // can also arrive via the Gradle sidecar path) doesn't appear without an explicit
        // user gesture. State is per-previewId because hopping between focused cards re-applies
        // the toggle to the new target.
        let a11yOverlayPreviewId = null;
        const focusPosition = document.getElementById('focus-position');
        const progressBar = document.getElementById('progress-bar');
        const progressFill = progressBar.querySelector('.progress-fill');
        const progressLabel = document.getElementById('progress-label');
        // Auto-reset-to-idle timer for the bar after it lands at 100%.
        // Holds for a beat so the user sees the completed state, then
        // resets the fill + label to their idle look. The strip itself
        // stays mounted — see notes in setProgress().
        let progressHideTimer = null;
        // Deferred-paint state. We don't paint a fill until ~200ms of
        // in-flight work has accumulated, so warm-cache refreshes never
        // pulse the bar. The strip itself is ALWAYS mounted (no
        // display:none toggle), so deferring the paint keeps the layout
        // identical between idle and pending — only the fill width and
        // label text change once the deferral elapses.
        const PROGRESS_PAINT_DELAY_MS = 200;
        let progressPaintTimer = null;
        let progressActive = false;
        let pendingProgressState = null;

        function resetProgressVisuals() {
            progressBar.classList.remove('progress-finishing', 'progress-slow');
            progressFill.style.width = '0%';
            progressBar.setAttribute('aria-valuenow', '0');
            progressLabel.textContent = '';
        }

        function applyProgressState(state) {
            const pct = Math.max(0, Math.min(1, state.percent));
            progressActive = true;
            progressBar.classList.remove('progress-finishing');
            progressBar.classList.toggle('progress-slow', !!state.slow);
            progressFill.style.width = (pct * 100).toFixed(1) + '%';
            progressBar.setAttribute('aria-valuenow', String(Math.round(pct * 100)));
            const slowSuffix = state.slow ? ' (slow)' : '';
            progressLabel.textContent = state.label
                ? state.label + slowSuffix + ' · ' + Math.round(pct * 100) + '%'
                : '';
            if (pct >= 1) {
                progressBar.classList.add('progress-finishing');
                progressHideTimer = setTimeout(() => {
                    progressActive = false;
                    resetProgressVisuals();
                    progressHideTimer = null;
                }, 600);
            }
        }

        function setProgress(label, percent, slow) {
            if (progressHideTimer) {
                clearTimeout(progressHideTimer);
                progressHideTimer = null;
            }
            const state = { label: label || '', percent, slow: !!slow };
            // Already painting — apply directly so the bar stays in lockstep
            // with the tracker rather than re-entering the deferral window.
            if (progressActive) {
                applyProgressState(state);
                return;
            }
            // Latched state for when the deferral timer fires.
            pendingProgressState = state;
            // Terminal state: paint immediately so the user gets a visible
            // completion even on instant refreshes (rare path — tracker
            // emits an immediate done=100% on an up-to-date discover).
            if (state.percent >= 1) {
                applyProgressState(state);
                return;
            }
            if (progressPaintTimer === null) {
                progressPaintTimer = setTimeout(() => {
                    progressPaintTimer = null;
                    if (pendingProgressState) {
                        applyProgressState(pendingProgressState);
                    }
                }, PROGRESS_PAINT_DELAY_MS);
            }
        }

        function clearProgress() {
            if (progressHideTimer) {
                clearTimeout(progressHideTimer);
                progressHideTimer = null;
            }
            if (progressPaintTimer) {
                clearTimeout(progressPaintTimer);
                progressPaintTimer = null;
            }
            pendingProgressState = null;
            progressActive = false;
            resetProgressVisuals();
        }

        // Compile-error banner state. Cards stay rendered while the banner
        // is visible — they're decorated via .compile-stale on the grid so
        // the user keeps the last-good render visible while reading the
        // error list.
        const compileErrorsBox = document.getElementById('compile-errors');
        const compileErrorsList = document.getElementById('compile-errors-list');
        const compileErrorsTitle = document.getElementById('compile-errors-title');

        function setCompileErrors(errors) {
            compileErrorsList.innerHTML = '';
            const count = errors.length;
            compileErrorsTitle.textContent = count === 1
                ? '1 compile error'
                : count + ' compile errors';
            for (const e of errors) {
                const row = document.createElement('button');
                row.type = 'button';
                row.className = 'compile-error-row';
                row.title = 'Open ' + e.file + ':' + e.line + ':' + e.column;
                const loc = document.createElement('span');
                loc.className = 'compile-error-loc';
                loc.textContent = e.file + ':' + e.line + ':' + e.column;
                const msg = document.createElement('span');
                msg.className = 'compile-error-msg';
                msg.textContent = e.message;
                row.appendChild(loc);
                row.appendChild(msg);
                // Each error carries its own absolute path — required so
                // a cross-file kotlinc error (e.g. broken Theme.kt while
                // editing Previews.kt) opens the right file rather than
                // whichever file the panel happened to be scoped to.
                const path = e.path;
                row.addEventListener('click', () => {
                    vscode.postMessage({
                        command: 'openCompileError',
                        sourceFile: path,
                        line: e.line,
                        column: e.column,
                    });
                });
                compileErrorsList.appendChild(row);
            }
            compileErrorsBox.hidden = false;
            // Dim existing cards via a grid-level class so the user sees
            // they're stale relative to the buffer. CSS handles the visual.
            grid.classList.add('compile-stale');
        }

        function clearCompileErrors() {
            compileErrorsBox.hidden = true;
            compileErrorsList.innerHTML = '';
            compileErrorsTitle.textContent = '';
            grid.classList.remove('compile-stale');
        }

        let allPreviews = [];
        let moduleDir = '';
        let filterDebounce = null;
        let focusIndex = 0;
        // Last previewId published to the extension via previewScopeChanged.
        // Tracked here so we don't spam the History panel with redundant
        // re-scopes (e.g. layout reapplies on every filter tweak).
        let lastScopedPreviewId = null;
        // Layout to fall back to when the user exits focus mode. Captured
        // whenever we transition into focus from another layout (dropdown
        // change, dblclick on a card). Defaults to grid so the very first
        // exit lands somewhere sensible.
        let previousLayout = state.layout && state.layout !== 'focus' ? state.layout : 'grid';

        // Interactive (live-stream) mode state. Declared up here — *before*
        // the first applyLayout() call below — because applyLayout reads
        // interactivePreviewIds via the early-exit-on-focus-change path.
        // moduleDaemonReady tracks per-module daemon readiness pushed by the
        // extension via setInteractiveAvailability; the button enables only
        // when the focused card's owning module is ready.
        //
        // interactivePreviewIds is a Set so Shift+click on the LIVE toggle
        // can add/remove a preview without disturbing others (multi-stream
        // UI). The wire and daemon already support concurrent streams
        // (INTERACTIVE.md § 8); the panel just exposes it under a modifier
        // key. Default un-modified click stays single-target — clearing the
        // set before adding the new one — so casual users don't accidentally
        // pile up live streams.
        const moduleDaemonReady = new Map();
        const interactivePreviewIds = new Set();

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
            if (layoutMode.value === 'focus' && state.layout !== 'focus') {
                previousLayout = state.layout || 'grid';
            }
            state.layout = layoutMode.value;
            vscode.setState(state);
            applyLayout();
        });

        btnPrev.addEventListener('click', () => navigateFocus(-1));
        btnNext.addEventListener('click', () => navigateFocus(1));
        btnDiffHead.addEventListener('click', () => requestFocusedDiff('head'));
        btnDiffMain.addEventListener('click', () => requestFocusedDiff('main'));
        btnLaunchDevice.addEventListener('click', () => requestLaunchOnDevice());
        btnA11yOverlay.addEventListener('click', () => toggleA11yOverlay());
        // Shift modifier opts into the multi-stream path: keep the prior live targets, add or
        // remove just this one. Plain click keeps the single-target single-card UX casual users
        // expect.
        btnInteractive.addEventListener('click', (e) => toggleInteractive(e.shiftKey));
        btnStopInteractiveGlobal.addEventListener('click', () => stopAllInteractive());
        btnStopInteractive.addEventListener('click', () => stopAllInteractive());
        btnExitFocus.addEventListener('click', () => exitFocus());

        // ----- Interactive (live-stream) mode helpers -----
        // State (moduleDaemonReady, interactivePreviewIds) is declared
        // earlier so the first applyLayout() can reach it. The function
        // declarations below are hoisted, so call sites above this block
        // resolve fine.

        function isFocusedDaemonReady() {
            // The webview doesn't know moduleId per preview today. We fall
            // back to "any module ready" because the extension-side panel
            // is single-module-scoped (it only ever holds one module's
            // previews at a time) — so the readiness of the sole module is
            // also the readiness for whatever's focused. If the panel ever
            // shows multiple modules at once, swap this to lookup by the
            // focused card's data-module-id.
            for (const ready of moduleDaemonReady.values()) {
                if (ready) return true;
            }
            return false;
        }

        function applyInteractiveButtonState() {
            const inFocus = layoutMode.value === 'focus';
            // Hide outright when not in focus mode — the toolbar already
            // hides itself, but this keeps aria-pressed correct for tests
            // that snapshot the button in either layout.
            btnInteractive.hidden = !inFocus;
            const hasLive = interactivePreviewIds.size > 0;
            btnStopInteractiveGlobal.hidden = !hasLive;
            btnStopInteractiveGlobal.disabled = !hasLive;
            btnStopInteractive.hidden = !inFocus || !hasLive;
            btnStopInteractive.disabled = !hasLive;
            if (!inFocus) {
                // Cheap fast-path: applyLayout fires this on every layout
                // change (filter tweaks, focus nav, every setPreviews). In
                // non-focus modes the focus-controls strip is hidden by CSS,
                // so nothing visible would change — skip the getVisibleCards
                // DOM walk and the per-attribute writes. State on re-entry to
                // focus mode is rebuilt fresh by the full path below.
                btnInteractive.setAttribute('aria-pressed', 'false');
                btnInteractive.classList.remove('live-on');
                return;
            }
            const visible = getVisibleCards();
            const card = visible[focusIndex];
            if (!card) {
                btnInteractive.disabled = true;
                btnInteractive.setAttribute('aria-pressed', 'false');
                btnInteractive.title = 'Daemon not ready — live mode unavailable';
                btnInteractive.innerHTML =
                    '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
                return;
            }
            const previewId = card.dataset.previewId;
            const ready = isFocusedDaemonReady();
            const live = !!previewId && interactivePreviewIds.has(previewId);
            const otherLiveCount = interactivePreviewIds.size - (live ? 1 : 0);
            btnInteractive.disabled = !ready && !live;
            btnInteractive.setAttribute('aria-pressed', live ? 'true' : 'false');
            btnInteractive.classList.toggle('live-on', live);
            btnInteractive.title = !ready
                ? 'Daemon not ready — live mode unavailable'
                : (live
                    ? 'Live · click to exit · Shift+click to leave others on'
                    : (otherLiveCount > 0
                        ? otherLiveCount + ' other live · click to make this one live too · '
                          + 'Shift+click to add without unsubscribing the rest'
                        : 'Enter live mode (stream renders) · Shift+click to add to multi-stream'));
            btnInteractive.innerHTML = live
                ? '<i class="codicon codicon-record" aria-hidden="true"></i>'
                : '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
        }

        // D2 — keep the a11y-overlay toggle in lockstep with focus-mode + the focused card
        // identity. Outside focus mode the button hides; inside focus mode aria-pressed
        // tracks whether the currently focused preview has the overlay subscription on.
        function applyA11yOverlayButtonState() {
            const inFocus = layoutMode.value === 'focus';
            btnA11yOverlay.hidden = !inFocus;
            if (!inFocus) {
                btnA11yOverlay.setAttribute('aria-pressed', 'false');
                return;
            }
            const card = getVisibleCards()[focusIndex];
            const previewId = card ? card.dataset.previewId : null;
            const on = previewId !== null && previewId === a11yOverlayPreviewId;
            btnA11yOverlay.setAttribute('aria-pressed', on ? 'true' : 'false');
            btnA11yOverlay.title = on
                ? 'Hide accessibility overlay'
                : 'Show accessibility overlay';
            btnA11yOverlay.classList.toggle('a11y-overlay-on', on);
        }

        // D2 — clicking the a11y toggle subscribes/unsubscribes via the extension. When
        // turning OFF, the extension also pushes an empty updateA11y so the cached overlay
        // tears down immediately rather than waiting for a next render. When turning ON for a
        // different preview, first turn the previous one off so the wire stays clean.
        function toggleA11yOverlay() {
            if (layoutMode.value !== 'focus') return;
            const card = getVisibleCards()[focusIndex];
            const previewId = card ? card.dataset.previewId : null;
            if (!previewId) return;
            const turningOn = previewId !== a11yOverlayPreviewId;
            if (a11yOverlayPreviewId && a11yOverlayPreviewId !== previewId) {
                vscode.postMessage({
                    command: 'setA11yOverlay',
                    previewId: a11yOverlayPreviewId,
                    enabled: false,
                });
            }
            a11yOverlayPreviewId = turningOn ? previewId : null;
            vscode.postMessage({
                command: 'setA11yOverlay',
                previewId,
                enabled: turningOn,
            });
            applyA11yOverlayButtonState();
        }

        function applyLiveBadge() {
            // Tear down every prior live decoration first — a removal from interactivePreviewIds
            // (Shift+click off, set-cleared on daemon-not-ready, etc.) needs the badge to come
            // off the now-not-live card. Then re-stamp every still-live preview.
            document.querySelectorAll('.preview-card.live').forEach(c => {
                c.classList.remove('live');
            });
            if (interactivePreviewIds.size === 0) return;
            interactivePreviewIds.forEach(previewId => {
                const card = document.getElementById('preview-' + sanitizeId(previewId));
                if (!card) return;
                card.classList.add('live');
            });
        }

        function stopAllInteractive() {
            if (interactivePreviewIds.size === 0) return;
            const ids = Array.from(interactivePreviewIds);
            interactivePreviewIds.clear();
            ids.forEach(previewId => {
                vscode.postMessage({
                    command: 'setInteractive',
                    previewId,
                    enabled: false,
                });
            });
            applyLiveBadge();
            applyInteractiveButtonState();
        }

        function toggleInteractive(shift) {
            // Drives the LIVE button in the focus-mode toolbar — operates on the currently
            // focused card. Body factored into setInteractiveForCard so the in-card click handler
            // (any layout) can reuse the same plain-vs-Shift logic.
            if (layoutMode.value !== 'focus') return;
            const visible = getVisibleCards();
            const card = visible[focusIndex];
            if (!card) return;
            setInteractiveForCard(card, shift);
        }

        /**
         * Toggle interactive mode for [card] honouring plain/Shift semantics:
         *  - Plain: single-target. Drop every prior live target before adding (or re-removing)
         *    this one — keeps the casual UX matching v1's "one card live at a time" mental model.
         *  - Shift: multi-target. Toggle just this preview in/out of the live set without
         *    touching the others.
         */
        function setInteractiveForCard(card, shift) {
            const previewId = card.dataset.previewId;
            if (!previewId) return;
            const wasLive = interactivePreviewIds.has(previewId);
            if (!shift) {
                interactivePreviewIds.forEach(prior => {
                    if (prior === previewId) return;
                    vscode.postMessage({
                        command: 'setInteractive',
                        previewId: prior,
                        enabled: false,
                    });
                });
                interactivePreviewIds.clear();
            }
            const turnOn = !wasLive;
            if (turnOn) {
                interactivePreviewIds.add(previewId);
            } else {
                interactivePreviewIds.delete(previewId);
            }
            vscode.postMessage({
                command: 'setInteractive',
                previewId,
                enabled: turnOn,
            });
            applyLiveBadge();
            applyInteractiveButtonState();
        }

        /**
         * Single-click-to-LIVE entry point from the in-card image click handler. Ensures
         * image clicks land here in any layout (focus, grid, flow, column) — the focus-mode
         * LIVE button is a redundant affordance for the focus-mode user, this lets every layout
         * reach interactive mode in one click. Subsequent clicks while LIVE forward to the
         * daemon via the per-image handler in updateImage; that is a separate code path and
         * the click here short-circuits.
         */
        function enterInteractiveOnCard(card, shift) {
            setInteractiveForCard(card, shift);
        }

        // Idempotent attach. Adds pointer + wheel listeners to the live card's image
        // exactly once; flagged via dataset so a second updateImage on the
        // same element doesn't stack listeners. Detached the moment live
        // mode exits, so non-live cards never carry the handler.
        function ensureInteractiveInputHandlers(card, img) {
            const previewId = card.dataset.previewId;
            const shouldHandle = !!previewId && interactivePreviewIds.has(previewId);
            if (shouldHandle && img.dataset.interactiveBound !== '1') {
                img.dataset.interactiveBound = '1';
                const state = {
                    pointerId: null,
                    start: null,
                    last: null,
                    dragging: false,
                    sentDown: false,
                };
                img.addEventListener('pointerdown', (evt) => {
                    const id = card.dataset.previewId;
                    if (!id || !interactivePreviewIds.has(id)) return;
                    if (evt.button !== 0) return;
                    state.pointerId = evt.pointerId;
                    state.start = imagePoint(img, evt);
                    state.last = state.start;
                    state.dragging = false;
                    state.sentDown = false;
                    img.setPointerCapture?.(evt.pointerId);
                    evt.preventDefault();
                    evt.stopPropagation();
                });
                img.addEventListener('pointermove', (evt) => {
                    const id = card.dataset.previewId;
                    if (!id || !interactivePreviewIds.has(id)) return;
                    if (state.pointerId !== evt.pointerId || !state.start) return;
                    const next = imagePoint(img, evt);
                    if (!next) return;
                    const dx = next.clientX - state.start.clientX;
                    const dy = next.clientY - state.start.clientY;
                    if (!state.dragging && Math.hypot(dx, dy) >= 4) {
                        state.dragging = true;
                    }
                    if (state.dragging) {
                        if (!state.sentDown) {
                            postInteractiveInput(id, img, 'pointerDown', state.start);
                            state.sentDown = true;
                        }
                        postInteractiveInput(id, img, 'pointerMove', next);
                        state.last = next;
                        evt.preventDefault();
                        evt.stopPropagation();
                    }
                });
                img.addEventListener('pointerup', (evt) => {
                    const id = card.dataset.previewId;
                    if (!id || !interactivePreviewIds.has(id)) return;
                    if (state.pointerId !== evt.pointerId || !state.start) return;
                    const point = imagePoint(img, evt) || state.last || state.start;
                    if (state.dragging) {
                        if (!state.sentDown) {
                            postInteractiveInput(id, img, 'pointerDown', state.start);
                        }
                        postInteractiveInput(id, img, 'pointerUp', point);
                    } else {
                        postInteractiveInput(id, img, 'click', point);
                    }
                    img.releasePointerCapture?.(evt.pointerId);
                    state.pointerId = null;
                    state.start = null;
                    state.last = null;
                    state.dragging = false;
                    state.sentDown = false;
                    evt.preventDefault();
                    evt.stopPropagation();
                });
                img.addEventListener('pointercancel', (evt) => {
                    if (state.pointerId !== evt.pointerId) return;
                    img.releasePointerCapture?.(evt.pointerId);
                    state.pointerId = null;
                    state.start = null;
                    state.last = null;
                    state.dragging = false;
                    state.sentDown = false;
                });
                img.addEventListener('wheel', (evt) => {
                    const id = card.dataset.previewId;
                    if (!id || !interactivePreviewIds.has(id) || card.dataset.wearPreview !== '1') return;
                    const point = imagePoint(img, evt);
                    if (!point) return;
                    postInteractiveInput(id, img, 'rotaryScroll', point, evt.deltaY);
                    evt.preventDefault();
                    evt.stopPropagation();
                }, { passive: false });
            }
        }

        function imagePoint(img, evt) {
            // Image-natural pixel coords — same space the daemon's renderer
            // works in. The webview's offsetX/Y is in CSS pixels of the
            // displayed image; scale by the displayed/natural ratio to
            // recover the pixel the user actually clicked.
            const natW = img.naturalWidth || 0;
            const natH = img.naturalHeight || 0;
            const rect = img.getBoundingClientRect();
            if (!natW || !natH || rect.width === 0 || rect.height === 0) return null;
            const clientX = evt.clientX - rect.left;
            const clientY = evt.clientY - rect.top;
            const pixelX = Math.round(clientX * (natW / rect.width));
            const pixelY = Math.round(clientY * (natH / rect.height));
            return {
                clientX,
                clientY,
                pixelX: Math.max(0, Math.min(natW - 1, pixelX)),
                pixelY: Math.max(0, Math.min(natH - 1, pixelY)),
            };
        }

        function postInteractiveInput(previewId, img, kind, point, scrollDeltaY) {
            const natW = img.naturalWidth || 0;
            const natH = img.naturalHeight || 0;
            if (!point || !natW || !natH) return;
            vscode.postMessage({
                command: 'recordInteractiveInput',
                previewId,
                kind,
                pixelX: point.pixelX,
                pixelY: point.pixelY,
                imageWidth: natW,
                imageHeight: natH,
                scrollDeltaY,
            });
        }

        // Document-level Left/Right in focus mode steps between cards. The
        // animated-carousel frame-controls handler stops propagation so
        // its arrow keys still walk captures within a single card. Skip
        // when an input-like element has focus (the layout dropdown,
        // future text inputs) so native keyboard semantics aren't stolen.
        document.addEventListener('keydown', (e) => {
            if (layoutMode.value !== 'focus') return;
            if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
            const tag = e.target && e.target.tagName;
            if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
            navigateFocus(e.key === 'ArrowLeft' ? -1 : 1);
            e.preventDefault();
        });

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
                    publishScopedPreview();
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
            document.querySelectorAll('.image-container').forEach(c => c.removeAttribute('title'));
            publishScopedPreview();
            // Single-target follow-focus: when there's exactly one live stream and the user
            // navigates off it, drop the stream so the LIVE chip follows the focused card.
            // Multi-target (size > 1) is treated as an explicit opt-in via Shift+click — those
            // streams persist across focus navigation until the user explicitly toggles them off
            // (or daemon dies, or the webview disposes).
            if (interactivePreviewIds.size === 1) {
                const visible = getVisibleCards();
                const card = mode === 'focus' ? visible[focusIndex] : null;
                const lone = interactivePreviewIds.values().next().value;
                if (!card || card.dataset.previewId !== lone) {
                    vscode.postMessage({
                        command: 'setInteractive',
                        previewId: lone,
                        enabled: false,
                    });
                    interactivePreviewIds.clear();
                    applyLiveBadge();
                }
            }
            // D2 — same teardown for the a11y overlay: navigating off the previewed card
            // (or exiting focus mode) unsubscribes so the wire stays quiet for cards the
            // user isn't looking at.
            if (a11yOverlayPreviewId) {
                const visible = getVisibleCards();
                const card = mode === 'focus' ? visible[focusIndex] : null;
                if (!card || card.dataset.previewId !== a11yOverlayPreviewId) {
                    vscode.postMessage({
                        command: 'setA11yOverlay',
                        previewId: a11yOverlayPreviewId,
                        enabled: false,
                    });
                    a11yOverlayPreviewId = null;
                }
            }
            applyInteractiveButtonState();
            applyA11yOverlayButtonState();
        }

        // Compute the previewId the panel is currently narrowed to, if any:
        //   - focus mode: the focused card
        //   - non-focus: the sole visible card when filters narrowed to one
        //   - otherwise: null (panel shows multiple previews — module-level history)
        // Posts the current value to the extension only when it changes so
        // the History panel re-lists at most once per user-driven narrowing.
        function publishScopedPreview() {
            const visible = getVisibleCards();
            let previewId = null;
            if (layoutMode.value === 'focus') {
                if (visible.length > 0 && focusIndex >= 0 && focusIndex < visible.length) {
                    previewId = visible[focusIndex].dataset.previewId || null;
                }
            } else if (visible.length === 1) {
                previewId = visible[0].dataset.previewId || null;
            }
            if (previewId === lastScopedPreviewId) return;
            lastScopedPreviewId = previewId;
            vscode.postMessage({
                command: 'previewScopeChanged',
                previewId,
            });
        }

        function navigateFocus(delta) {
            const visible = getVisibleCards();
            if (visible.length === 0) return;
            focusIndex = Math.max(0, Math.min(visible.length - 1, focusIndex + delta));
            applyLayout();
        }

        // Switch the layout to focus mode and target the supplied card.
        // No-op when the card is filtered out (it wouldn't be in the visible
        // set anyway, and forcing focus on an invisible card surfaces an
        // empty pane).
        function focusOnCard(card) {
            const visible = getVisibleCards();
            const idx = visible.indexOf(card);
            if (idx === -1) return;
            focusIndex = idx;
            if (layoutMode.value !== 'focus') {
                previousLayout = layoutMode.value;
                layoutMode.value = 'focus';
                state.layout = 'focus';
                vscode.setState(state);
            }
            applyLayout();
        }

        function exitFocus() {
            if (layoutMode.value !== 'focus') return;
            layoutMode.value = previousLayout;
            state.layout = previousLayout;
            vscode.setState(state);
            applyLayout();
        }

        // Live-panel diff: only meaningful when one preview is focused. Pulls
        // the currently focused card's previewId and asks the extension to
        // resolve the comparison anchor (HEAD = latest archived render,
        // main = latest archived render on the main branch).
        function requestFocusedDiff(against) {
            if (layoutMode.value !== 'focus') return;
            const visible = getVisibleCards();
            const card = visible[focusIndex];
            if (!card) return;
            const previewId = card.dataset.previewId;
            if (!previewId) return;
            showDiffOverlay(card, against, null, null);
            vscode.postMessage({ command: 'requestPreviewDiff', previewId, against });
        }

        // Live-panel "Launch on Device": runs the consumer's
        // installDebug task and uses adb to start the launcher activity on
        // a connected device. Only meaningful when one preview is focused
        // -- the extension uses the focused previewId to pick the owning
        // module before falling back to a quick-pick.
        function requestLaunchOnDevice() {
            if (layoutMode.value !== 'focus') return;
            const visible = getVisibleCards();
            const card = visible[focusIndex];
            if (!card) return;
            const previewId = card.dataset.previewId;
            if (!previewId) return;
            vscode.postMessage({ command: 'requestLaunchOnDevice', previewId });
        }

        function showDiffOverlay(card, against, payload, errorMessage) {
            const container = card.querySelector('.image-container');
            if (!container) return;
            const existing = container.querySelector('.preview-diff-overlay');
            if (existing) existing.remove();
            const overlay = document.createElement('div');
            overlay.className = 'preview-diff-overlay';
            overlay.dataset.against = against;
            const close = document.createElement('button');
            close.className = 'icon-button preview-diff-close';
            close.title = 'Exit diff';
            close.setAttribute('aria-label', 'Exit diff');
            close.innerHTML = '<i class="codicon codicon-close" aria-hidden="true"></i>';
            close.addEventListener('click', () => overlay.remove());
            overlay.appendChild(close);
            if (errorMessage) {
                const err = document.createElement('div');
                err.className = 'preview-diff-error';
                err.textContent = errorMessage;
                overlay.appendChild(err);
                container.appendChild(overlay);
                return;
            }
            if (!payload) {
                const loading = document.createElement('div');
                loading.className = 'preview-diff-loading';
                loading.textContent = 'Loading diff…';
                overlay.appendChild(loading);
                container.appendChild(overlay);
                return;
            }
            // Persist the user's last-picked mode so it sticks across diff
            // requests within the same session.
            const initialMode = (state.diffMode === 'overlay' || state.diffMode === 'onion')
                ? state.diffMode : 'side';
            const header = document.createElement('div');
            header.className = 'diff-header';
            const body = document.createElement('div');
            body.className = 'preview-diff-body';
            const modeBar = buildDiffModeBar(initialMode, (mode) => {
                state.diffMode = mode;
                vscode.setState(state);
                renderPreviewDiffMode(body, mode, payload);
            });
            const stats = document.createElement('div');
            stats.className = 'diff-stats';
            stats.textContent = 'computing…';
            header.appendChild(modeBar);
            header.appendChild(stats);
            overlay.appendChild(header);
            overlay.appendChild(body);
            container.appendChild(overlay);
            renderPreviewDiffMode(body, initialMode, payload);
            computeDiffStats(payload.leftImage, payload.rightImage).then(s => {
                applyDiffStats(stats, s);
            });
        }

        function buildDiffModeBar(initialMode, onChange) {
            const bar = document.createElement('div');
            bar.className = 'diff-mode-bar';
            bar.setAttribute('role', 'tablist');
            const modes = [
                { id: 'side', label: 'Side' },
                { id: 'overlay', label: 'Overlay' },
                { id: 'onion', label: 'Onion' },
            ];
            for (const m of modes) {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.textContent = m.label;
                btn.dataset.mode = m.id;
                btn.setAttribute('role', 'tab');
                btn.setAttribute('aria-selected', m.id === initialMode ? 'true' : 'false');
                if (m.id === initialMode) btn.classList.add('active');
                btn.addEventListener('click', () => {
                    bar.querySelectorAll('button').forEach(b => {
                        b.classList.toggle('active', b.dataset.mode === m.id);
                        b.setAttribute('aria-selected', b.dataset.mode === m.id ? 'true' : 'false');
                    });
                    onChange(m.id);
                });
                bar.appendChild(btn);
            }
            return bar;
        }

        function renderPreviewDiffMode(body, mode, payload) {
            body.innerHTML = '';
            if (mode === 'side') {
                const grid = document.createElement('div');
                grid.className = 'preview-diff-grid';
                grid.appendChild(buildPreviewDiffPane(payload.leftLabel, payload.leftImage));
                grid.appendChild(buildPreviewDiffPane(payload.rightLabel, payload.rightImage));
                body.appendChild(grid);
                return;
            }
            const stack = buildDiffStack(mode, payload);
            body.appendChild(stack);
        }

        function buildDiffStack(mode, payload) {
            const wrapper = document.createElement('div');
            wrapper.className = 'preview-diff-stack-wrapper';
            const stack = document.createElement('div');
            stack.className = 'diff-stack';
            stack.dataset.mode = mode;
            const base = document.createElement('img');
            base.className = 'diff-stack-base';
            base.alt = payload.leftLabel;
            base.src = 'data:image/png;base64,' + payload.leftImage;
            const top = document.createElement('img');
            top.className = 'diff-stack-top';
            top.alt = payload.rightLabel;
            top.src = 'data:image/png;base64,' + payload.rightImage;
            stack.appendChild(base);
            stack.appendChild(top);
            wrapper.appendChild(stack);
            if (mode === 'onion') {
                const slider = document.createElement('input');
                slider.type = 'range';
                slider.min = '0';
                slider.max = '100';
                slider.value = '50';
                slider.className = 'diff-stack-onion-slider';
                slider.setAttribute('aria-label',
                    'Onion-skin mix between ' + payload.leftLabel + ' and ' + payload.rightLabel);
                stack.style.setProperty('--diff-onion-mix', '0.5');
                slider.addEventListener('input', () => {
                    stack.style.setProperty('--diff-onion-mix', (slider.value / 100).toString());
                });
                wrapper.appendChild(slider);
            }
            const cap = document.createElement('div');
            cap.className = 'diff-stack-caption';
            cap.textContent = payload.leftLabel + '  ◄  ' + payload.rightLabel;
            wrapper.appendChild(cap);
            return wrapper;
        }

        function buildPreviewDiffPane(label, imageData) {
            const pane = document.createElement('div');
            pane.className = 'preview-diff-pane';
            const cap = document.createElement('div');
            cap.className = 'preview-diff-pane-label';
            cap.textContent = label;
            pane.appendChild(cap);
            if (imageData) {
                const img = document.createElement('img');
                img.src = 'data:image/png;base64,' + imageData;
                img.alt = label;
                pane.appendChild(img);
            } else {
                const empty = document.createElement('div');
                empty.className = 'preview-diff-pane-empty';
                empty.textContent = '(no image)';
                pane.appendChild(empty);
            }
            return pane;
        }

        // Client-side pixel diff: load both base64 PNGs, draw to canvas,
        // walk the ImageData buffers in parallel. Cheap on the typical
        // preview size (< 0.5 megapixel); GIFs / very large captures will
        // be slower but still bounded. Daemon-side pixel mode (with SSIM)
        // can plug in here later via a different code path.
        function computeDiffStats(leftBase64, rightBase64) {
            return new Promise((resolve) => {
                const left = new Image();
                const right = new Image();
                let loaded = 0;
                const onErr = () => resolve({ error: 'image failed to load' });
                const onOk = () => {
                    if (++loaded < 2) return;
                    try {
                        if (left.naturalWidth !== right.naturalWidth
                            || left.naturalHeight !== right.naturalHeight) {
                            resolve({
                                sameSize: false,
                                leftW: left.naturalWidth, leftH: left.naturalHeight,
                                rightW: right.naturalWidth, rightH: right.naturalHeight,
                            });
                            return;
                        }
                        const w = left.naturalWidth, h = left.naturalHeight;
                        const c1 = document.createElement('canvas');
                        c1.width = w; c1.height = h;
                        c1.getContext('2d').drawImage(left, 0, 0);
                        const d1 = c1.getContext('2d').getImageData(0, 0, w, h).data;
                        const c2 = document.createElement('canvas');
                        c2.width = w; c2.height = h;
                        c2.getContext('2d').drawImage(right, 0, 0);
                        const d2 = c2.getContext('2d').getImageData(0, 0, w, h).data;
                        let diff = 0;
                        const len = d1.length;
                        for (let i = 0; i < len; i += 4) {
                            if (d1[i] !== d2[i] || d1[i+1] !== d2[i+1]
                                || d1[i+2] !== d2[i+2] || d1[i+3] !== d2[i+3]) diff++;
                        }
                        const total = w * h;
                        resolve({
                            sameSize: true, w, h, diffPx: diff, total,
                            percent: total > 0 ? diff / total : 0,
                        });
                    } catch (err) {
                        resolve({ error: (err && err.message) || 'stats unavailable' });
                    }
                };
                left.onload = onOk; left.onerror = onErr;
                right.onload = onOk; right.onerror = onErr;
                left.src = 'data:image/png;base64,' + leftBase64;
                right.src = 'data:image/png;base64,' + rightBase64;
            });
        }

        function applyDiffStats(el, s) {
            if (!s) { el.textContent = ''; el.removeAttribute('data-state'); return; }
            if (s.error) { el.textContent = s.error; el.removeAttribute('data-state'); return; }
            if (!s.sameSize) {
                el.textContent = 'sizes differ — '
                    + s.leftW + '×' + s.leftH + ' vs '
                    + s.rightW + '×' + s.rightH;
                el.dataset.state = 'size-mismatch';
                return;
            }
            if (s.diffPx === 0) {
                el.textContent = 'identical · ' + s.w + '×' + s.h;
                el.dataset.state = 'identical';
                return;
            }
            const p = s.percent * 100;
            const pct = p < 0.01 ? p.toFixed(3) : p.toFixed(2);
            el.textContent = s.diffPx.toLocaleString() + ' px ('
                + pct + '%) · ' + s.w + '×' + s.h;
            el.dataset.state = 'changed';
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
            card.dataset.wearPreview = isWearPreview(p) ? '1' : '0';
            card.dataset.currentIndex = '0';
            cardCaptures.set(p.id, captures.map(c => ({
                label: c.label || '',
                renderOutput: c.renderOutput || '',
                imageData: null,
                errorMessage: null,
                renderError: null,
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

            // Per-card focus icon. Replaces the previous "double-click image"
            // affordance — single-click on the image is now reserved for
            // entering LIVE (interactive) mode, so we need an explicit handle
            // for "view this card by itself". Same hot zone toggles between
            // enter-focus (other layouts) and exit-focus (focus layout).
            const focusBtn = document.createElement('button');
            focusBtn.type = 'button';
            focusBtn.className = 'card-focus-btn';
            focusBtn.innerHTML = '<i class="codicon codicon-screen-full" aria-hidden="true"></i>';
            focusBtn.title = 'Focus this preview';
            focusBtn.setAttribute('aria-label', 'Focus this preview');
            focusBtn.addEventListener('click', (evt) => {
                evt.stopPropagation();
                if (layoutMode.value === 'focus') {
                    exitFocus();
                } else {
                    focusOnCard(card);
                }
            });
            titleRow.appendChild(focusBtn);

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

            // Single-click on the image enters LIVE for this preview (in any
            // layout — focus, grid, flow, column). The first click toggles
            // interactive on; subsequent clicks while LIVE forward as pointer
            // events to the daemon (handled by ensureInteractiveInputHandlers
            // attached via updateImage). The handler is on the container, not
            // the <img>, so clicks land before the image renders too. Modifier-
            // aware: Shift+click follows the multi-stream semantics from
            // toggleInteractive().
            imgContainer.addEventListener('click', (evt) => {
                const previewId = card.dataset.previewId;
                if (!previewId) return;
                // If we're already live for this preview, the per-image click
                // handler routes to recordInteractiveInput. Check before the
                // stale-card branch so interactive clicks do not also queue a
                // heavyweight refresh for stale captures.
                if (interactivePreviewIds.has(previewId)) return;
                if (card.classList.contains('is-stale')) {
                    evt.preventDefault();
                    evt.stopPropagation();
                    requestHeavyRefresh(card);
                    return;
                }
                enterInteractiveOnCard(card, evt.shiftKey);
            });

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

            observeCardForViewport(card);
            return card;
        }

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

            // Arrow keys when the carousel has focus. Stop propagation so
            // the document-level focus-mode nav doesn't also advance the card.
            bar.tabIndex = 0;
            bar.addEventListener('keydown', (e) => {
                if (e.key === 'ArrowLeft') {
                    stepFrame(card, -1); e.preventDefault(); e.stopPropagation();
                } else if (e.key === 'ArrowRight') {
                    stepFrame(card, 1); e.preventDefault(); e.stopPropagation();
                }
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

        /**
         * Build the error overlay DOM for a failing capture. When
         * renderError is non-null the panel has structured detail:
         * exception class, message, a clickable file:line frame, and a
         * collapsible stack trace. Otherwise it falls back to the plain
         * one-line message (the case for renderers that don't yet
         * produce a sidecar, or when the sidecar was unreadable).
         *
         * Click on the frame button posts an openSourceFile message;
         * the extension resolves the stack-trace basename to an
         * absolute path via workspace.findFiles. The disclosure for
         * the full trace is a native details element -- no JS state
         * to manage, browser handles the toggle.
         */
        function buildErrorPanel(message, renderError, className) {
            const panel = document.createElement('div');
            panel.className = 'error-message render-error';
            panel.setAttribute('role', 'alert');
            if (!renderError) {
                panel.textContent = message || '';
                return panel;
            }
            const cls = (renderError.exception || '').split('.').pop()
                || renderError.exception || 'Error';
            const head = document.createElement('div');
            head.className = 'render-error-class';
            head.textContent = cls;
            panel.appendChild(head);

            if (renderError.message) {
                const msg = document.createElement('div');
                msg.className = 'render-error-msg';
                msg.textContent = renderError.message;
                panel.appendChild(msg);
            }

            const frame = renderError.topAppFrame;
            if (frame && frame.file) {
                const link = document.createElement('button');
                link.className = 'render-error-frame';
                link.type = 'button';
                const fnSuffix = frame.function ? ' in ' + frame.function : '';
                const lineSuffix = frame.line > 0 ? ':' + frame.line : '';
                link.textContent = frame.file + lineSuffix + fnSuffix;
                link.title = 'Open ' + frame.file + lineSuffix;
                link.addEventListener('click', () => {
                    vscode.postMessage({
                        command: 'openSourceFile',
                        fileName: frame.file,
                        line: frame.line,
                        // className lets the extension disambiguate same-
                        // named files across modules — when the throw is
                        // in this preview's own file, the class-derived
                        // path matches and we pick the right one without
                        // a workspace-wide first-hit guess.
                        className: className || undefined,
                    });
                });
                panel.appendChild(link);
            }

            if (renderError.stackTrace) {
                const details = document.createElement('details');
                details.className = 'render-error-stack';
                const summary = document.createElement('summary');
                summary.textContent = 'Stack trace';
                details.appendChild(summary);
                const pre = document.createElement('pre');
                pre.textContent = renderError.stackTrace;
                details.appendChild(pre);
                panel.appendChild(details);
            }
            return panel;
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
                ensureInteractiveInputHandlers(card, img);
            } else if (capture.errorMessage || capture.renderError) {
                const existingErr = container.querySelector('.error-message');
                if (existingErr) existingErr.remove();
                container.appendChild(
                    buildErrorPanel(capture.errorMessage, capture.renderError, card.dataset.className),
                );
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

        function updateCardMetadata(card, p) {
            card.dataset.function = p.functionName;
            card.dataset.group = p.params.group || '';
            card.dataset.wearPreview = isWearPreview(p) ? '1' : '0';
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
                renderError: prior[i]?.renderError ?? null,
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

        function isWearPreview(p) {
            const device = (p.params.device || '').toLowerCase();
            if (device.includes('wear')) return true;
            const w = p.params.widthDp || 0;
            const h = p.params.heightDp || 0;
            return w > 0 && h > 0 && w === h && w <= 260;
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
                    intersecting.delete(id);
                    if (intersectionObserver) intersectionObserver.unobserve(card);
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

            // Clear transient owner messages now that cards are in the DOM.
            // The 'loading' Building… banner and the 'fallback' "Preparing
            // previews…" placeholder both get cleared here. 'extension'-owned
            // messages (build errors, empty-state notices) are left alone —
            // those are terminal states the extension is asserting and the
            // caller wouldn't be sending setPreviews alongside them anyway.
            //
            // Must run *after* cards are inserted: setMessage('', …) calls
            // ensureNotBlank, which would re-set "Preparing previews…" if
            // the grid still looked empty when the message was cleared.
            if (message.dataset.owner && message.dataset.owner !== 'extension') {
                setMessage('', message.dataset.owner);
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
         * same affordance band as the open-source buttons.
         */
        function requestHeavyRefresh(card) {
            const previewId = card.dataset.previewId;
            if (!previewId) return;
            vscode.postMessage({
                command: 'refreshHeavy',
                previewId,
            });
        }

        function applyStaleBadge(card, isStale) {
            const titleRow = card.querySelector('.card-title-row');
            if (!titleRow) return;
            const existing = card.querySelector('.card-stale-btn');
            if (isStale && !existing) {
                const btn = document.createElement('button');
                btn.className = 'icon-button card-stale-btn';
                btn.title = 'Stale heavy capture — click to keep fresh while focused';
                btn.setAttribute('aria-label', 'Keep stale capture fresh');
                btn.innerHTML = '<i class="codicon codicon-warning" aria-hidden="true"></i>';
                btn.addEventListener('click', (evt) => {
                    evt.preventDefault();
                    evt.stopPropagation();
                    requestHeavyRefresh(card);
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

        // Two-stage overlay during stealth refresh:
        //   stage 1 (0–500ms): tiny corner spinner, no dim, no blur. Most
        //     daemon hits land in this window — image swap reads as a clean
        //     update, no "dim → undim" flicker.
        //   stage 2 (>500ms):  classic subtle (dim + blur + corner spinner).
        //     The build is taking real time, escalate so the user sees the
        //     panel is actually working.
        // Cards whose updateImage arrives during stage 1 never see stage 2.
        const OVERLAY_ESCALATE_MS = 500;
        let overlayEscalationTimer = null;

        function markAllLoading() {
            document.querySelectorAll('.preview-card').forEach(card => {
                const container = card.querySelector('.image-container');
                if (!container) return;
                // Skip if already has an overlay (e.g. previous refresh still running)
                if (container.querySelector('.loading-overlay')) return;
                // Don't add overlay if there's just a skeleton (nothing useful to cover)
                if (container.querySelector('.skeleton') && !container.querySelector('img')) return;
                const overlay = document.createElement('div');
                overlay.className = 'loading-overlay minimal';
                overlay.innerHTML = '<div class="spinner" aria-label="Refreshing"></div>';
                container.appendChild(overlay);
            });
            scheduleOverlayEscalation();
        }

        function scheduleOverlayEscalation() {
            if (overlayEscalationTimer) clearTimeout(overlayEscalationTimer);
            overlayEscalationTimer = setTimeout(() => {
                overlayEscalationTimer = null;
                // Promote every still-present minimal overlay to subtle.
                // Cards whose images already arrived have removed their
                // overlays, so this only touches cards still waiting.
                document.querySelectorAll('.loading-overlay.minimal').forEach(o => {
                    o.classList.remove('minimal');
                    o.classList.add('subtle');
                });
            }, OVERLAY_ESCALATE_MS);
        }

        function cancelOverlayEscalation() {
            if (overlayEscalationTimer) {
                clearTimeout(overlayEscalationTimer);
                overlayEscalationTimer = null;
            }
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
                caps[captureIndex].renderError = null;
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

            let img = container.querySelector('img');
            if (!img) {
                img = document.createElement('img');
                img.alt = card.dataset.function + ' preview';
                container.appendChild(img);
            }
            img.src = newSrc;
            // In live mode the new bytes are a frame, not a card reload —
            // skip the fade-in so successive frames read as a stream rather
            // than a sequence of independent renders. See INTERACTIVE.md § 3.
            const isLive = interactivePreviewIds.has(previewId);
            img.className = isLive ? 'live-frame' : 'fade-in';
            ensureInteractiveInputHandlers(card, img);

            if (caps) updateFrameIndicator(card);

            // If a diff overlay is open on this card and uses the live render
            // as its left anchor (head / main / current), the bytes the
            // overlay is showing just went stale. Re-issue so the user sees
            // the new render without clicking — symmetric with the
            // compose-preview/main ref watcher's auto-refresh on the right anchor.
            const openDiff = container.querySelector('.preview-diff-overlay');
            if (openDiff) {
                const against = openDiff.dataset.against;
                if (against === 'head' || against === 'main') {
                    showDiffOverlay(card, against, null, null);
                    vscode.postMessage({
                        command: 'requestPreviewDiff',
                        previewId,
                        against,
                    });
                }
            }

            // Re-build the a11y overlay once the image natural dimensions
            // are known. Data-URL srcs may resolve synchronously; in that
            // case img.complete is true and load will not fire, so we
            // check both. Findings are stashed at setPreviews time via the
            // renderPreviews pipeline.
            const findings = cardA11yFindings.get(previewId);
            const nodes = cardA11yNodes.get(previewId);
            if ((findings && findings.length > 0) || (nodes && nodes.length > 0)) {
                const apply = () => {
                    if (findings && findings.length > 0) buildA11yOverlay(card, findings, img);
                    if (nodes && nodes.length > 0) applyHierarchyOverlay(card, nodes, img);
                };
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

        // D2 — previewId -> nodes for the daemon-attached a11y/hierarchy payload. Drives
        // the local hierarchy overlay (translucent rectangles + label/role/states tooltip
        // on hover) drawn on top of the existing finding overlay. Populated by
        // applyA11yUpdate and re-read on each image (re)load via applyHierarchyOverlay.
        const cardA11yNodes = new Map();

        // D2 — handles updateA11y from the extension (daemon-attached a11y data products).
        // Updates the per-preview caches and re-applies whichever overlays are now relevant
        // without rebuilding the whole card. Findings -> legend + finding overlay; nodes ->
        // hierarchy overlay. Either argument may be omitted to leave that side untouched.
        function applyA11yUpdate(previewId, findings, nodes) {
            const card = document.getElementById('preview-' + sanitizeId(previewId));
            if (!card) return;
            const container = card.querySelector('.image-container');
            const img = container && container.querySelector('img');
            if (findings !== undefined) {
                if (findings && findings.length > 0) {
                    cardA11yFindings.set(previewId, findings);
                    if (container && !container.querySelector('.a11y-overlay')) {
                        const overlay = document.createElement('div');
                        overlay.className = 'a11y-overlay';
                        overlay.setAttribute('aria-hidden', 'true');
                        container.appendChild(overlay);
                    }
                    const existingLegend = card.querySelector('.a11y-legend');
                    if (existingLegend) existingLegend.remove();
                    const p = allPreviews.find(pp => pp.id === previewId);
                    if (p) {
                        p.a11yFindings = findings;
                        card.appendChild(buildA11yLegend(p));
                    }
                    if (img && img.complete && img.naturalWidth > 0) {
                        buildA11yOverlay(card, findings, img);
                    }
                } else {
                    cardA11yFindings.delete(previewId);
                    const overlay = card.querySelector('.a11y-overlay');
                    if (overlay) overlay.remove();
                    const legend = card.querySelector('.a11y-legend');
                    if (legend) legend.remove();
                }
            }
            if (nodes !== undefined) {
                if (nodes && nodes.length > 0) {
                    cardA11yNodes.set(previewId, nodes);
                    ensureHierarchyOverlay(container);
                    if (img && img.complete && img.naturalWidth > 0) {
                        applyHierarchyOverlay(card, nodes, img);
                    }
                } else {
                    cardA11yNodes.delete(previewId);
                    const layer = card.querySelector('.a11y-hierarchy-overlay');
                    if (layer) layer.remove();
                }
            }
        }

        function ensureHierarchyOverlay(container) {
            if (!container) return;
            if (container.querySelector('.a11y-hierarchy-overlay')) return;
            const layer = document.createElement('div');
            layer.className = 'a11y-hierarchy-overlay';
            layer.setAttribute('aria-hidden', 'true');
            container.appendChild(layer);
        }

        // D2 — draws one translucent rectangle per accessibility-relevant node. Uses the
        // same boundsInScreen parsing as the finding overlay so the math stays trivial.
        // Each box gets a title attribute summarising label / role / states so a hover
        // yields the same data TalkBack would announce, without baking any of it into
        // the PNG.
        function applyHierarchyOverlay(card, nodes, img) {
            const overlay = card.querySelector('.a11y-hierarchy-overlay');
            if (!overlay) return;
            overlay.innerHTML = '';
            const natW = img.naturalWidth;
            const natH = img.naturalHeight;
            if (!natW || !natH) return;
            nodes.forEach((n) => {
                const bounds = parseBounds(n.boundsInScreen);
                if (!bounds) return;
                const box = document.createElement('div');
                box.className = 'a11y-hierarchy-box' + (n.merged ? '' : ' a11y-hierarchy-unmerged');
                box.style.left = (bounds.left / natW * 100) + '%';
                box.style.top = (bounds.top / natH * 100) + '%';
                box.style.width = ((bounds.right - bounds.left) / natW * 100) + '%';
                box.style.height = ((bounds.bottom - bounds.top) / natH * 100) + '%';
                const tooltipParts = [];
                if (n.label) tooltipParts.push(n.label);
                if (n.role) tooltipParts.push(n.role);
                if (n.states && n.states.length) tooltipParts.push(n.states.join(', '));
                if (tooltipParts.length) box.title = tooltipParts.join(' · ');
                overlay.appendChild(box);
            });
        }

        // ----- Viewport tracking (daemon scroll-ahead, PREDICTIVE.md § 7) -----
        // Webview owns the geometry. Extension's daemon scheduler consumes
        // the published snapshot; when the daemon path is off the extension
        // simply ignores these messages.
        const intersecting = new Set();
        let lastScrollTop = 0;
        let lastScrollAt = 0;
        let scrollVelocity = 0; // px/ms, +ve = scrolling down
        let viewportDebounce = null;

        const intersectionObserver = ('IntersectionObserver' in window)
            ? new IntersectionObserver((entries) => {
                for (const entry of entries) {
                    const id = entry.target.dataset.previewId;
                    if (!id) continue;
                    if (entry.isIntersecting) {
                        intersecting.add(id);
                    } else {
                        intersecting.delete(id);
                        // Auto-stop interactive mode when a live preview scrolls out of view —
                        // keeps the daemon from accumulating warm scenes the user can't see and
                        // matches the "follow what the user is looking at" UX. Re-entering view
                        // doesn't auto-resume; the user re-clicks if they want it back.
                        if (interactivePreviewIds.has(id)) {
                            interactivePreviewIds.delete(id);
                            vscode.postMessage({
                                command: 'setInteractive',
                                previewId: id,
                                enabled: false,
                            });
                            applyLiveBadge();
                            applyInteractiveButtonState();
                        }
                    }
                }
                scheduleViewportPublish();
            }, { root: null, rootMargin: '0px', threshold: 0.1 })
            : null;

        function observeCardForViewport(card) {
            if (intersectionObserver) intersectionObserver.observe(card);
        }

        function unobserveAllCards() {
            if (!intersectionObserver) return;
            intersecting.clear();
            document.querySelectorAll('.preview-card').forEach(c => intersectionObserver.unobserve(c));
        }

        // Coalesce viewport publishes — IntersectionObserver fires per-card
        // during a scroll burst; don't drown the daemon in setVisible spam.
        function scheduleViewportPublish() {
            if (viewportDebounce) return;
            viewportDebounce = setTimeout(() => {
                viewportDebounce = null;
                publishViewport();
            }, 120);
        }

        document.addEventListener('scroll', () => {
            const now = performance.now();
            const top = window.scrollY || document.documentElement.scrollTop || 0;
            const dt = Math.max(1, now - lastScrollAt);
            const dy = top - lastScrollTop;
            // Light EMA so a single jittery frame doesn't flip the predicted set.
            scrollVelocity = scrollVelocity * 0.4 + (dy / dt) * 0.6;
            lastScrollAt = now;
            lastScrollTop = top;
            scheduleViewportPublish();
        }, { passive: true });

        // Project the next-page IDs based on scroll direction. Velocity is
        // signed (px/ms): positive = scrolling down → predict cards below
        // the lowest currently-visible card; negative = predict above.
        function predictNextIds() {
            if (Math.abs(scrollVelocity) < 0.05) return [];
            const visibleCards = Array.from(document.querySelectorAll('.preview-card'))
                .filter(c => intersecting.has(c.dataset.previewId)
                    && !c.classList.contains('filtered-out'));
            if (visibleCards.length === 0) return [];
            const allCards = Array.from(document.querySelectorAll('.preview-card'))
                .filter(c => !c.classList.contains('filtered-out'));
            // Cards are in DOM order; pick the ones immediately ahead of the
            // last visible (or before the first) up to a bounded count.
            const PREDICT_AHEAD = 4;
            const ids = [];
            if (scrollVelocity > 0) {
                const lastVisibleIdx = allCards.indexOf(visibleCards[visibleCards.length - 1]);
                for (let i = lastVisibleIdx + 1; i < allCards.length && ids.length < PREDICT_AHEAD; i++) {
                    const id = allCards[i].dataset.previewId;
                    if (id && !intersecting.has(id)) ids.push(id);
                }
            } else {
                const firstVisibleIdx = allCards.indexOf(visibleCards[0]);
                for (let i = firstVisibleIdx - 1; i >= 0 && ids.length < PREDICT_AHEAD; i--) {
                    const id = allCards[i].dataset.previewId;
                    if (id && !intersecting.has(id)) ids.push(id);
                }
            }
            return ids;
        }

        function publishViewport() {
            const visible = Array.from(intersecting);
            const predicted = predictNextIds();
            vscode.postMessage({
                command: 'viewportUpdated',
                visible,
                predicted,
            });
        }

        window.addEventListener('message', event => {
            const msg = event.data;
            switch (msg.command) {
                case 'setPreviews': {
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
                    // setPreviews can rebuild the focused card from scratch;
                    // re-stamp the live badge so the LIVE chip reattaches to
                    // the right card(s). Drop any live previewIds that are
                    // gone from the new manifest — silent cleanup; we don't
                    // bother sending interactive/stop because the preview no
                    // longer exists for the daemon to dispatch into anyway.
                    const newIds = new Set(msg.previews.map(p => p.id));
                    interactivePreviewIds.forEach(id => {
                        if (!newIds.has(id)) interactivePreviewIds.delete(id);
                    });
                    applyLiveBadge();
                    applyInteractiveButtonState();
                    break;
                }

                case 'markAllLoading':
                    markAllLoading();
                    break;

                case 'clearAll':
                    allPreviews = [];
                    grid.innerHTML = '';
                    // Reset so the next setPreviews can re-publish the
                    // narrowed-preview scope if applicable — otherwise a
                    // stale id from the previous module would dedupe the
                    // first publish and the History panel would miss it.
                    lastScopedPreviewId = null;
                    // Cards are gone — escalation timer has nothing left to
                    // promote. Avoids a stray timer firing after the next
                    // refresh has installed fresh minimal overlays.
                    cancelOverlayEscalation();
                    // Don't clear the message here — if it came with a
                    // follow-up showMessage (the usual pattern) it'll be
                    // replaced; if not, ensureNotBlank will backstop a
                    // placeholder so the view never ends up empty+silent.
                    ensureNotBlank();
                    break;

                case 'updateImage':
                    updateImage(msg.previewId, msg.captureIndex || 0, msg.imageData);
                    break;

                case 'updateA11y':
                    // D2 — daemon-attached a11y data products landed for one preview. Refresh
                    // the per-card caches and re-apply the overlays in place; no full re-render
                    // of the grid. findings/nodes left undefined means leave that side alone.
                    applyA11yUpdate(msg.previewId, msg.findings, msg.nodes);
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
                    }
                    // Whole-panel loading state is now carried by the slim
                    // progress bar at the top of the view (setProgress).
                    // Avoid double-signalling with a "Building…" banner —
                    // it competes with the bar for visual attention.
                    break;

                case 'setProgress':
                    setProgress(msg.label || '', msg.percent || 0, msg.slow);
                    break;

                case 'clearProgress':
                    clearProgress();
                    break;

                case 'setCompileErrors':
                    setCompileErrors(msg.errors || []);
                    break;

                case 'clearCompileErrors':
                    clearCompileErrors();
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
                        const renderError = msg.command === 'setImageError' ? (msg.renderError || null) : null;
                        const caps = cardCaptures.get(msg.previewId);
                        if (caps && caps[captureIndex]) {
                            caps[captureIndex].errorMessage = msg.message;
                            caps[captureIndex].renderError = renderError;
                            caps[captureIndex].imageData = null;
                        }
                        const cur = parseInt(errCard.dataset.currentIndex || '0', 10);
                        if (caps && cur !== captureIndex) break;

                        errCard.classList.add('has-error');
                        const container = errCard.querySelector('.image-container');
                        const previousErr = container.querySelector('.error-message');
                        if (previousErr) previousErr.remove();
                        // setImageError keeps any existing rendered <img>
                        // visible underneath the error overlay so the user
                        // still has the previous render as a reference.
                        // setError is the preview-wide path — wipe everything
                        // and replace with just the error.
                        if (msg.command === 'setError') {
                            const existingImg = container.querySelector('img');
                            if (existingImg) existingImg.remove();
                            const skeleton = container.querySelector('.skeleton');
                            if (skeleton) skeleton.remove();
                        }
                        container.appendChild(
                            buildErrorPanel(msg.message, renderError, errCard.dataset.className),
                        );
                    }
                    break;
                }

                case 'showMessage':
                    setMessage(msg.text, 'extension');
                    break;

                case 'previewDiffReady': {
                    const card = document.getElementById('preview-' + sanitizeId(msg.previewId));
                    if (!card) break;
                    showDiffOverlay(card, msg.against, {
                        leftLabel: msg.leftLabel,
                        leftImage: msg.leftImage,
                        rightLabel: msg.rightLabel,
                        rightImage: msg.rightImage,
                    }, null);
                    break;
                }
                case 'previewDiffError': {
                    const card = document.getElementById('preview-' + sanitizeId(msg.previewId));
                    if (!card) break;
                    showDiffOverlay(card, msg.against, null, msg.message || 'Diff unavailable.');
                    break;
                }
                case 'focusAndDiff': {
                    const card = document.getElementById('preview-' + sanitizeId(msg.previewId));
                    if (!card) break;
                    focusOnCard(card);
                    showDiffOverlay(card, msg.against, null, null);
                    vscode.postMessage({
                        command: 'requestPreviewDiff',
                        previewId: msg.previewId,
                        against: msg.against,
                    });
                    break;
                }
                case 'setInteractiveAvailability': {
                    moduleDaemonReady.set(msg.moduleId, !!msg.ready);
                    // Daemon went away while a card was live — drop the live
                    // state so the user doesn't keep seeing a LIVE badge on
                    // a card whose stream has stopped. Today the panel is
                    // single-module-scoped so any not-ready signal applies
                    // to every live preview; if the panel ever shows multi-
                    // module previews simultaneously, this needs scoping by
                    // each preview's owning module.
                    if (!msg.ready && interactivePreviewIds.size > 0) {
                        interactivePreviewIds.clear();
                        applyLiveBadge();
                    }
                    applyInteractiveButtonState();
                    break;
                }
                case 'clearInteractive': {
                    // Extension flushed daemon-side streams (e.g. user moved focus to a
                    // different editor). Drop our UI-side bookkeeping in lockstep — the
                    // extension already stopped the streams server-side, so we MUST NOT post
                    // setInteractive messages back; that would race the flush.
                    if (interactivePreviewIds.size > 0) {
                        interactivePreviewIds.clear();
                        applyLiveBadge();
                        applyInteractiveButtonState();
                    }
                    break;
                }
                case 'previewMainRefChanged': {
                    // compose-preview/main moved — re-issue any open vs-main
                    // diff overlay so the user sees the new bytes without
                    // clicking. Other diffs (HEAD, current, previous) are
                    // unaffected.
                    document.querySelectorAll(
                        '.preview-diff-overlay[data-against="main"]',
                    ).forEach(overlay => {
                        const card = overlay.closest('.preview-card');
                        const previewId = card && card.dataset.previewId;
                        if (!card || !previewId) return;
                        showDiffOverlay(card, 'main', null, null);
                        vscode.postMessage({
                            command: 'requestPreviewDiff',
                            previewId,
                            against: 'main',
                        });
                    });
                    break;
                }
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
