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
