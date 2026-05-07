// DOM mutator backing `StaleBadgeController.apply`. Lifted into its own
// file so the badge add/remove operation is testable under happy-dom
// without dragging the controller's wider transitive imports (the
// `vscode` handle's `acquireVsCodeApi` global, etc.) into the host
// tsconfig.
//
// The function is intentionally narrow: it owns the DOM mutation and
// idempotency rules, and takes the click handler as a callback so the
// controller can keep its `requestHeavyRefresh` wiring without leaking
// the `vscode` handle into this file.

/**
 * Toggles the "stale heavy capture — click to refresh" affordance on a
 * single card. Idempotent — when the desired state already matches the
 * DOM (badge present iff [isStale]) the call is a no-op.
 *
 * Adds `.is-stale` to [card] and a `.card-stale-btn` button to its
 * `.card-title-row` when [isStale] is true; removes both when false.
 *
 * Silently skips when [card] has no `.card-title-row` child — defensive
 * against transient DOM states where the card hasn't finished mounting
 * yet (the imperative copy in `behavior.ts` did the same).
 */
export function applyStaleBadge(
    card: HTMLElement,
    isStale: boolean,
    onClick: () => void,
): void {
    const titleRow = card.querySelector(".card-title-row");
    if (!titleRow) return;
    const existing = card.querySelector(".card-stale-btn");
    if (isStale && !existing) {
        const btn = document.createElement("button");
        btn.className = "icon-button card-stale-btn";
        btn.title = "Stale heavy capture — click to keep fresh while focused";
        btn.setAttribute("aria-label", "Keep stale capture fresh");
        btn.innerHTML =
            '<i class="codicon codicon-warning" aria-hidden="true"></i>';
        btn.addEventListener("click", (evt) => {
            evt.preventDefault();
            evt.stopPropagation();
            onClick();
        });
        titleRow.appendChild(btn);
        card.classList.add("is-stale");
    } else if (!isStale && existing) {
        existing.remove();
        card.classList.remove("is-stale");
    }
}
