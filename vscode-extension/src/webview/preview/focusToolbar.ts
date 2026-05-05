// Focus-mode toolbar — the 10-button strip rendered above the
// preview grid when layout is `focus`.
//
// Lifted verbatim from `behavior.ts`'s
// `applyEarlyFeatureVisibility` / `applyInteractiveButtonState` /
// `applyRecordingButtonState` / `applyA11yOverlayButtonState` quartet
// that drives `disabled` / `hidden` / `aria-pressed` / `title` / icon
// across the focus-mode controls. The HTML structure of the toolbar
// is still rendered by `<preview-app>` in `main.ts`; this module just
// owns the typed state machinery.
//
// Each `applyXxx` takes a typed state object — `behavior.ts` computes
// the inputs from its closures (filter toolbar layout, focus index,
// live / recording sets, daemon readiness Maps, the `a11yOverlay`
// store field) and hands them in. Click handlers on the buttons stay
// in `behavior.ts` because they reach into `setInteractiveForCard` /
// `toggleRecording` / `toggleA11yOverlay` etc.; that's a bigger lift
// for a follow-up.
//
// The two `isFocused*` helpers also live here so behavior.ts can
// import a typed predicate over the daemon-readiness Maps rather than
// inlining the same `.values()` / `.entries()` walk at each call
// site.

export interface FocusToolbarElements {
    btnPrev: HTMLButtonElement;
    btnNext: HTMLButtonElement;
    btnDiffHead: HTMLButtonElement;
    btnDiffMain: HTMLButtonElement;
    btnLaunchDevice: HTMLButtonElement;
    btnA11yOverlay: HTMLButtonElement;
    btnInteractive: HTMLButtonElement;
    btnStopInteractive: HTMLButtonElement;
    btnRecording: HTMLButtonElement;
    btnExitFocus: HTMLButtonElement;
    recordingFormat: HTMLSelectElement;
    focusInspector: HTMLElement;
}

export interface EarlyFeatureVisibilityState {
    earlyFeatures: boolean;
    inFocus: boolean;
}

export interface InteractiveButtonState {
    inFocus: boolean;
    /** `previewId` of the focused card, or `null` when no card is in focus. */
    focusedPreviewId: string | null;
    /** Whether the focused preview is currently in the live set. */
    isLive: boolean;
    /** Live-set size minus the focused preview if it's live — used to
     *  switch the title between "click to make this one live too" and
     *  the multi-stream variants. */
    otherLiveCount: number;
    /** Whether ANY card is currently live (controls the stop-all
     *  button's hidden / disabled state). */
    hasLive: boolean;
    /** Daemon readiness for the focused module. */
    daemonReady: boolean;
    /** Whether the focused module supports v2 live mode (vs the v1
     *  fallback where renders refresh but pointer state is lost). */
    interactiveSupported: boolean;
}

export interface RecordingButtonState {
    inFocus: boolean;
    earlyFeatures: boolean;
    focusedPreviewId: string | null;
    daemonReady: boolean;
    isRecording: boolean;
}

export interface A11yOverlayButtonState {
    inFocus: boolean;
    earlyFeatures: boolean;
    focusedPreviewId: string | null;
    /** `previewId` whose accessibility-overlay subscription is on, or
     *  `null` when no overlay is active. */
    a11yOverlayId: string | null;
}

export class FocusToolbarController {
    constructor(private readonly el: FocusToolbarElements) {}

    applyEarlyFeatureVisibility(s: EarlyFeatureVisibilityState): void {
        this.el.btnDiffHead.hidden = !s.earlyFeatures;
        this.el.btnDiffMain.hidden = !s.earlyFeatures;
        this.el.btnLaunchDevice.hidden = !s.earlyFeatures;
        this.el.btnA11yOverlay.hidden = !s.earlyFeatures || !s.inFocus;
        this.el.btnRecording.hidden = !s.earlyFeatures || !s.inFocus;
        this.el.recordingFormat.hidden = !s.earlyFeatures || !s.inFocus;
        if (!s.earlyFeatures) {
            this.el.focusInspector.hidden = true;
        }
    }

    applyInteractiveButtonState(s: InteractiveButtonState): void {
        // Hide outright when not in focus mode — the toolbar already
        // hides itself, but this keeps aria-pressed correct for tests
        // that snapshot the button in either layout.
        this.el.btnInteractive.hidden = !s.inFocus;
        this.el.btnStopInteractive.hidden = !s.inFocus || !s.hasLive;
        this.el.btnStopInteractive.disabled = !s.hasLive;
        if (!s.inFocus) {
            // Cheap fast-path: applyLayout fires this on every layout
            // change (filter tweaks, focus nav, every setPreviews). In
            // non-focus modes the focus-controls strip is hidden by CSS,
            // so nothing visible would change — skip the per-attribute
            // writes. State on re-entry to focus mode is rebuilt fresh
            // by the full path below.
            this.el.btnInteractive.setAttribute("aria-pressed", "false");
            this.el.btnInteractive.classList.remove("live-on");
            return;
        }
        if (!s.focusedPreviewId) {
            this.el.btnInteractive.disabled = true;
            this.el.btnInteractive.setAttribute("aria-pressed", "false");
            this.el.btnInteractive.title =
                "Daemon not ready — live mode unavailable";
            this.el.btnInteractive.innerHTML =
                '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
            return;
        }
        this.el.btnInteractive.disabled = !s.daemonReady && !s.isLive;
        this.el.btnInteractive.setAttribute(
            "aria-pressed",
            s.isLive ? "true" : "false",
        );
        this.el.btnInteractive.classList.toggle("live-on", s.isLive);
        this.el.btnInteractive.title = !s.daemonReady
            ? "Daemon not ready — live mode unavailable"
            : s.isLive
              ? "Live · click to exit · Shift+click to leave others on"
              : !s.interactiveSupported
                ? "Live v1 fallback — renders refresh, but clicks do not preserve Compose state"
                : s.otherLiveCount > 0
                  ? s.otherLiveCount +
                    " other live · click to make this one live too · " +
                    "Shift+click to add without unsubscribing the rest"
                  : "Enter live mode (stream renders) · Shift+click to add to multi-stream";
        this.el.btnInteractive.innerHTML = s.isLive
            ? '<i class="codicon codicon-record" aria-hidden="true"></i>'
            : '<i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>';
    }

    applyRecordingButtonState(s: RecordingButtonState): void {
        this.el.btnRecording.hidden = !s.earlyFeatures || !s.inFocus;
        this.el.recordingFormat.hidden = !s.earlyFeatures || !s.inFocus;
        if (!s.earlyFeatures || !s.inFocus) {
            this.el.btnRecording.setAttribute("aria-pressed", "false");
            this.el.btnRecording.classList.remove("recording-on");
            this.el.recordingFormat.disabled = true;
            return;
        }
        this.el.btnRecording.disabled = !s.daemonReady && !s.isRecording;
        this.el.recordingFormat.disabled =
            s.isRecording || (!s.daemonReady && !s.isRecording);
        this.el.btnRecording.setAttribute(
            "aria-pressed",
            s.isRecording ? "true" : "false",
        );
        this.el.btnRecording.classList.toggle("recording-on", s.isRecording);
        this.el.btnRecording.title = !s.daemonReady
            ? "Daemon not ready — recording unavailable"
            : s.isRecording
              ? "Stop recording focused preview"
              : "Record focused preview";
        this.el.btnRecording.innerHTML = s.isRecording
            ? '<i class="codicon codicon-debug-stop" aria-hidden="true"></i>'
            : '<i class="codicon codicon-record-keys" aria-hidden="true"></i>';
    }

    /**
     * D2 — keep the a11y-overlay toggle in lockstep with focus-mode +
     * the focused card identity. Outside focus mode the button hides;
     * inside focus mode `aria-pressed` tracks whether the currently
     * focused preview has the overlay subscription on.
     */
    applyA11yOverlayButtonState(s: A11yOverlayButtonState): void {
        this.el.btnA11yOverlay.hidden = !s.earlyFeatures || !s.inFocus;
        if (!s.earlyFeatures || !s.inFocus) {
            this.el.btnA11yOverlay.setAttribute("aria-pressed", "false");
            return;
        }
        const on =
            s.focusedPreviewId !== null &&
            s.focusedPreviewId === s.a11yOverlayId;
        this.el.btnA11yOverlay.setAttribute(
            "aria-pressed",
            on ? "true" : "false",
        );
        this.el.btnA11yOverlay.title = on
            ? "Hide accessibility overlay"
            : "Show accessibility overlay";
        this.el.btnA11yOverlay.classList.toggle("a11y-overlay-on", on);
    }
}

/**
 * Daemon-ready predicate for the focused module. The webview doesn't
 * know moduleId per preview today; we fall back to "any module ready"
 * because the extension-side panel is single-module-scoped (it only
 * ever holds one module's previews at a time) — so the readiness of
 * the sole module is also the readiness for whatever's focused. If
 * the panel ever shows multiple modules at once, swap this to lookup
 * by the focused card's `data-module-id`.
 */
export function isFocusedModuleReady(
    moduleDaemonReady: ReadonlyMap<string, boolean>,
): boolean {
    for (const ready of moduleDaemonReady.values()) {
        if (ready) return true;
    }
    return false;
}

/**
 * Whether the focused module supports full v2 live mode (with
 * preserved Compose state) vs the v1 fallback where pointer events
 * round-trip through the daemon but renders refresh from scratch.
 * Same module-scoping caveat as [isFocusedModuleReady].
 */
export function isFocusedInteractiveSupported(
    moduleDaemonReady: ReadonlyMap<string, boolean>,
    moduleInteractiveSupported: ReadonlyMap<string, boolean>,
): boolean {
    for (const [moduleId, ready] of moduleDaemonReady.entries()) {
        if (ready && moduleInteractiveSupported.get(moduleId) === true)
            return true;
    }
    return false;
}
