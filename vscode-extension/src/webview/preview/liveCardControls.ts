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
 * `.image-container`. This is the per-card entry point for issue #1203
 * interactive-only data extensions (canonical case: `input.keyboard`).
 *
 * Clicking the button toggles the panel-side "controls" flag for the
 * preview. When that flag flips on, [onToggle] is responsible for both
 * (a) entering live mode for the card (interactive dispatch can't land
 * outside a held composition — see `LiveStateController.extensionRequiresInteractive`)
 * and (b) attaching the keyboard listener.
 *
 * The button stays in the DOM across capability / live-state changes;
 * [enabled] only controls the pressed visual state. Visibility is driven
 * by adding / removing the element rather than re-creating it, so the
 * click handler is bound exactly once per card.
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
 * Remove the controls-toggle button from [card] when the daemon stops
 * advertising any interactive-only extension. Idempotent: no-op when the
 * button isn't present.
 */
export function removeControlsToggleButton(card: HTMLElement): void {
    const btn = card.querySelector(".card-controls-toggle-btn");
    if (btn) btn.remove();
}
