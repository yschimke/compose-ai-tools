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
    btnDiffHead: HTMLButtonElement;
    btnDiffMain: HTMLButtonElement;
    btnLaunchDevice: HTMLButtonElement;
    btnA11yOverlay: HTMLButtonElement;
    btnInteractive: HTMLButtonElement;
    btnStopInteractive: HTMLButtonElement;
    btnRecording: HTMLButtonElement;
    btnTouchOverlay: HTMLButtonElement;
    btnKeyboardBand: HTMLButtonElement;
    btnControls: HTMLButtonElement;
    btnLauncherWidget: HTMLButtonElement;
    btnExportBundle: HTMLButtonElement;
    btnExitFocus: HTMLButtonElement;
    recordingFormat: HTMLSelectElement;
    focusInspector: HTMLElement;
}

export interface EarlyFeatureVisibilityState {
    earlyFeatures: boolean;
    inFocus: boolean;
    /** Hosted by `BundleViewerPanel`. There's never a Gradle module nor
     *  history behind a bundle, so launch-on-device + diff buttons stay
     *  hidden regardless of daemon state. Daemon-driven buttons (a11y
     *  overlay, recording, focus inspector chips) follow [bundleDaemonReady]
     *  — they only surface once the per-bundle daemon JVM has
     *  initialised. */
    bundleMode: boolean;
    /** True once `bundleDaemonReady` has been observed (or always true in
     *  non-bundle mode). Drives the daemon-backed toolbar buttons in
     *  bundle viewer panels. */
    bundleDaemonReady: boolean;
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

/**
 * Common shape for the three preview-override / interactive-input toggles that
 * follow the focused card (touch overlay, soft-keyboard band, #1203 controls).
 * `advertised` reflects the daemon capability gate — when the daemon doesn't
 * ship the matching planner / interactive-only extension the button stays hidden.
 */
export interface FocusedToggleButtonState {
    inFocus: boolean;
    focusedPreviewId: string | null;
    /** Daemon advertises the backing extension / capability. */
    advertised: boolean;
    /** Current per-preview toggle state for the focused card. */
    enabled: boolean;
}

export class FocusToolbarController {
    constructor(private readonly el: FocusToolbarElements) {}

    applyEarlyFeatureVisibility(s: EarlyFeatureVisibilityState): void {
        const bundle = s.bundleMode;
        // Buttons that intrinsically need a Gradle module — stay hidden
        // in bundle mode regardless of daemon state.
        this.el.btnDiffHead.hidden = bundle || !s.earlyFeatures;
        this.el.btnDiffMain.hidden = bundle || !s.earlyFeatures;
        this.el.btnLaunchDevice.hidden = bundle || !s.earlyFeatures;
        this.el.btnExportBundle.hidden =
            bundle || !s.earlyFeatures || !s.inFocus;
        // Daemon-backed buttons — visible in either host once the
        // backing daemon is ready (or, in non-bundle mode, always —
        // sidebar panels gate daemon-readiness per-button via the
        // individual `applyXxxButtonState` hooks).
        const daemonHidden = bundle && !s.bundleDaemonReady;
        // a11y is the one bundle graduated out of `earlyFeatures` — the
        // chip-bar filter at `main.ts:2415` already surfaces it in basic
        // mode, and the focus-toolbar button is the matching focus-mode
        // entry point. Other early-features buttons (recording, export
        // bundle, …) stay gated until those features graduate too.
        this.el.btnA11yOverlay.hidden = daemonHidden || !s.inFocus;
        this.el.btnRecording.hidden =
            daemonHidden || !s.earlyFeatures || !s.inFocus;
        this.el.recordingFormat.hidden =
            daemonHidden || !s.earlyFeatures || !s.inFocus;
        // Touch overlay / keyboard band / controls toggles — the per-button
        // `applyXxxButtonState` hooks below refine visibility based on the
        // daemon advertising the backing extension and a focused preview being
        // present, but force them hidden up front in bundle mode until the
        // bundle daemon is ready.
        if (daemonHidden || !s.inFocus) {
            this.el.btnTouchOverlay.hidden = true;
            this.el.btnKeyboardBand.hidden = true;
            this.el.btnControls.hidden = true;
            this.el.btnLauncherWidget.hidden = true;
        }
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
    // applyA11yOverlayButtonState applies the in-mode pressed-state +
    // tooltip refresh; the higher-level visibility gate is owned by
    // applyButtonVisibility above (and intentionally no longer requires
    // earlyFeatures, since a11y is the graduated bundle).
    applyA11yOverlayButtonState(s: A11yOverlayButtonState): void {
        this.el.btnA11yOverlay.hidden = !s.inFocus;
        if (!s.inFocus) {
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

    /**
     * Touch-event visualization toggle for the focused preview. Mirrors the
     * Replaces the legacy per-card overlay that used to sit on top of the
     * rendered preview, so the icon stops covering the frame.
     */
    applyTouchOverlayButtonState(s: FocusedToggleButtonState): void {
        const visible =
            s.inFocus && s.advertised && s.focusedPreviewId !== null;
        this.el.btnTouchOverlay.hidden = !visible;
        if (!visible) {
            this.el.btnTouchOverlay.setAttribute("aria-pressed", "false");
            return;
        }
        this.el.btnTouchOverlay.setAttribute(
            "aria-pressed",
            s.enabled ? "true" : "false",
        );
        this.el.btnTouchOverlay.title = s.enabled
            ? "Turn off touch-event visualization"
            : "Turn on touch-event visualization (paints rings at dispatched pointers)";
        this.el.btnTouchOverlay.setAttribute(
            "aria-label",
            this.el.btnTouchOverlay.title,
        );
    }

    /** Soft-keyboard band override toggle for the focused preview. */
    applyKeyboardBandButtonState(s: FocusedToggleButtonState): void {
        const visible =
            s.inFocus && s.advertised && s.focusedPreviewId !== null;
        this.el.btnKeyboardBand.hidden = !visible;
        if (!visible) {
            this.el.btnKeyboardBand.setAttribute("aria-pressed", "false");
            return;
        }
        this.el.btnKeyboardBand.setAttribute(
            "aria-pressed",
            s.enabled ? "true" : "false",
        );
        this.el.btnKeyboardBand.title = s.enabled
            ? "Hide soft-keyboard band"
            : "Force soft-keyboard band visible";
        this.el.btnKeyboardBand.setAttribute(
            "aria-label",
            this.el.btnKeyboardBand.title,
        );
    }

    /**
     * Launcher-widget container-size picker toggle. `enabled` means the focused
     * preview currently carries a `LauncherWidgetOverride` (picker has been
     * used at least once for this card); the button paints its pressed state
     * to flag the non-default sizing. Clicking opens / closes the popover —
     * that wiring (and the `aria-expanded` flip) lives in `main.ts`.
     */
    applyLauncherWidgetButtonState(s: FocusedToggleButtonState): void {
        const visible =
            s.inFocus && s.advertised && s.focusedPreviewId !== null;
        this.el.btnLauncherWidget.hidden = !visible;
        if (!visible) {
            this.el.btnLauncherWidget.setAttribute("aria-pressed", "false");
            return;
        }
        this.el.btnLauncherWidget.setAttribute(
            "aria-pressed",
            s.enabled ? "true" : "false",
        );
        this.el.btnLauncherWidget.title = s.enabled
            ? "Launcher-widget cell size — click to change or reset"
            : "Pick launcher-widget cell size";
        this.el.btnLauncherWidget.setAttribute(
            "aria-label",
            this.el.btnLauncherWidget.title,
        );
    }

    /** Issue #1203 — interactive controls (keyboard input) toggle. */
    applyControlsButtonState(s: FocusedToggleButtonState): void {
        const visible =
            s.inFocus && s.advertised && s.focusedPreviewId !== null;
        this.el.btnControls.hidden = !visible;
        if (!visible) {
            this.el.btnControls.setAttribute("aria-pressed", "false");
            return;
        }
        this.el.btnControls.setAttribute(
            "aria-pressed",
            s.enabled ? "true" : "false",
        );
        this.el.btnControls.title = s.enabled
            ? "Turn off interactive controls (keyboard input)"
            : "Turn on interactive controls (keyboard input). Enters live mode.";
        this.el.btnControls.setAttribute(
            "aria-label",
            this.el.btnControls.title,
        );
    }
}

// Pure predicates over `moduleDaemonReady` / `moduleInteractiveSupported`
// live in `./moduleReadiness.ts` so they can be unit-tested under the host
// tsconfig. Re-exported here for callers that already imported them from
// `focusToolbar`.
export {
    isFocusedInteractiveSupported,
    isFocusedModuleReady,
} from "./moduleReadiness";
