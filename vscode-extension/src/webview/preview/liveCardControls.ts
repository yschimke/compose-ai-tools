// Per-card live stop-button DOM helper used by
// `LiveStateController.ensureLiveCardControls`.
//
// Lifted into its own file (mirroring `liveBadge.ts`) so the DOM
// mutation is testable under happy-dom without dragging
// `LiveStateController`'s wider transitive imports
// (`interactiveInput.ts`'s pointer-handler module, the vscode handle,
// the live preview ID set, etc.) into the host tsconfig.
//
// Scope is intentionally narrow: this helper owns the stop-button
// element lifecycle (idempotent injection + click → onStop with
// `preventDefault` / `stopPropagation`) and nothing else. The caller
// is still responsible for re-attaching pointer/wheel input handlers
// after the button is in place — that wiring lives in `liveState.ts`.

/**
 * Idempotently inject a `.card-live-stop-btn` overlay into [card]'s
 * `.image-container`. Wires a click handler that calls [onStop] with
 * the card after suppressing default + propagation (so the click
 * doesn't bubble up into card-selection / focus handlers).
 *
 * No-op if [card] has no `.image-container` child or if the button is
 * already present. Safe to call repeatedly — never duplicates the
 * button, never re-binds the click handler.
 */
export function ensureLiveCardControls(
    card: HTMLElement,
    onStop: (card: HTMLElement) => void,
): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    if (container.querySelector(".card-live-stop-btn")) return;
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "icon-button card-live-stop-btn";
    btn.title = "Stop live preview";
    btn.setAttribute("aria-label", "Stop live preview");
    btn.innerHTML =
        '<i class="codicon codicon-debug-stop" aria-hidden="true"></i>';
    btn.addEventListener("click", (evt) => {
        evt.preventDefault();
        evt.stopPropagation();
        onStop(card);
    });
    container.appendChild(btn);
}

/**
 * Idempotently inject a `.card-controls-toggle-btn` overlay into [card]'s
 * `.image-container`. Used in grid / flow / column layouts where the focus
 * toolbar is hidden — the per-card overlay is the only path to the #1203
 * interactive-controls toggle outside focus mode. In focus mode the
 * focus-controls bar owns the toggle and the per-card button is stripped
 * by `removeControlsToggleButton`.
 */
export function ensureControlsToggleButton(
    card: HTMLElement,
    opts: {
        enabled: boolean;
        onToggle: (card: HTMLElement, next: boolean) => void;
    },
): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    let btn = container.querySelector<HTMLButtonElement>(
        ".card-controls-toggle-btn",
    );
    if (!btn) {
        btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-button card-controls-toggle-btn";
        btn.innerHTML =
            '<i class="codicon codicon-keyboard" aria-hidden="true"></i>';
        btn.addEventListener("click", (evt) => {
            evt.preventDefault();
            evt.stopPropagation();
            const wasEnabled = btn!.getAttribute("aria-pressed") === "true";
            opts.onToggle(card, !wasEnabled);
        });
        container.appendChild(btn);
    }
    btn.setAttribute("aria-pressed", opts.enabled ? "true" : "false");
    btn.title = opts.enabled
        ? "Turn off interactive controls (keyboard input)"
        : "Turn on interactive controls (keyboard input). Enters live mode.";
    btn.setAttribute("aria-label", btn.title);
}

/**
 * Idempotent removal of the `.card-controls-toggle-btn`. Used both to strip
 * stale state from older webview snapshots and to hide the button when the
 * focus-controls bar takes over in focus mode.
 */
export function removeControlsToggleButton(card: HTMLElement): void {
    const btn = card.querySelector(".card-controls-toggle-btn");
    if (btn) btn.remove();
}

/**
 * Idempotently inject a `.card-touch-overlay-toggle-btn` overlay into [card]'s
 * `.image-container`. Same focus-bar-vs-grid story as the controls toggle.
 */
export function ensureTouchOverlayToggleButton(
    card: HTMLElement,
    opts: {
        enabled: boolean;
        onToggle: (card: HTMLElement, next: boolean) => void;
    },
): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    let btn = container.querySelector<HTMLButtonElement>(
        ".card-touch-overlay-toggle-btn",
    );
    if (!btn) {
        btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-button card-touch-overlay-toggle-btn";
        btn.innerHTML =
            '<i class="codicon codicon-target" aria-hidden="true"></i>';
        btn.addEventListener("click", (evt) => {
            evt.preventDefault();
            evt.stopPropagation();
            const wasEnabled = btn!.getAttribute("aria-pressed") === "true";
            opts.onToggle(card, !wasEnabled);
        });
        container.appendChild(btn);
    }
    btn.setAttribute("aria-pressed", opts.enabled ? "true" : "false");
    btn.title = opts.enabled
        ? "Turn off touch-event visualization"
        : "Turn on touch-event visualization (paints rings at dispatched pointers)";
    btn.setAttribute("aria-label", btn.title);
}

/** Idempotent removal of the touch-overlay toggle. */
export function removeTouchOverlayToggleButton(card: HTMLElement): void {
    const btn = card.querySelector(".card-touch-overlay-toggle-btn");
    if (btn) btn.remove();
}

/**
 * Idempotently inject a `.card-keyboard-band-toggle-btn` overlay into [card]'s
 * `.image-container`. Same focus-bar-vs-grid story as the touch-overlay toggle.
 */
export function ensureKeyboardBandToggleButton(
    card: HTMLElement,
    opts: {
        enabled: boolean;
        onToggle: (card: HTMLElement, next: boolean) => void;
    },
): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    let btn = container.querySelector<HTMLButtonElement>(
        ".card-keyboard-band-toggle-btn",
    );
    if (!btn) {
        btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-button card-keyboard-band-toggle-btn";
        btn.innerHTML =
            '<i class="codicon codicon-symbol-keyword" aria-hidden="true"></i>';
        btn.addEventListener("click", (evt) => {
            evt.preventDefault();
            evt.stopPropagation();
            const wasEnabled = btn!.getAttribute("aria-pressed") === "true";
            opts.onToggle(card, !wasEnabled);
        });
        container.appendChild(btn);
    }
    btn.setAttribute("aria-pressed", opts.enabled ? "true" : "false");
    btn.title = opts.enabled
        ? "Hide soft-keyboard band"
        : "Force soft-keyboard band visible";
    btn.setAttribute("aria-label", btn.title);
}

/** Idempotent removal of the keyboard-band toggle. */
export function removeKeyboardBandToggleButton(card: HTMLElement): void {
    const btn = card.querySelector(".card-keyboard-band-toggle-btn");
    if (btn) btn.remove();
}
