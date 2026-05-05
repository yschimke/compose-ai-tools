// Per-card "stale heavy capture" badge.
//
// Lifted verbatim from `behavior.ts`'s `requestHeavyRefresh` /
// `applyStaleBadge` / `updateStaleBadges` trio. The badge is the only
// way the user can recover a fresh GIF / long-scroll image without
// editing source — clicking it posts `refreshHeavy` so the extension
// re-runs the heavy render path. Living inside the title row puts it
// in the same affordance band as the open-source button.
//
// `applyStaleBadge` is idempotent: it skips when the desired state
// already matches the DOM, so re-running on an unchanged card is
// cheap. Called both at card-creation time (so cards born stale have
// the badge from the start) and from `updateStaleBadges` after each
// `setPreviews` (state can flip when the user toggles fast↔full
// saves).
//
// The module is shaped as a single `StaleBadgeController` so the
// `vscode` handle stays scoped — none of the three helpers need to
// reach into closure.

import type { VsCodeApi } from "../shared/vscode";

export class StaleBadgeController {
    constructor(private readonly vscode: VsCodeApi<unknown>) {}

    /** Posts `refreshHeavy` for [card]'s preview id. No-op when the
     *  card has no `data-preview-id` (shouldn't happen in practice
     *  but stays defensive — the imperative copy did the same). */
    requestHeavyRefresh(card: HTMLElement): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        this.vscode.postMessage({
            command: "refreshHeavy",
            previewId,
        });
    }

    /**
     * Toggles the "stale render — click to refresh" affordance on a
     * single card. Idempotent — skips when the desired state already
     * matches the DOM.
     */
    apply(card: HTMLElement, isStale: boolean): void {
        const titleRow = card.querySelector(".card-title-row");
        if (!titleRow) return;
        const existing = card.querySelector(".card-stale-btn");
        if (isStale && !existing) {
            const btn = document.createElement("button");
            btn.className = "icon-button card-stale-btn";
            btn.title =
                "Stale heavy capture — click to keep fresh while focused";
            btn.setAttribute("aria-label", "Keep stale capture fresh");
            btn.innerHTML =
                '<i class="codicon codicon-warning" aria-hidden="true"></i>';
            btn.addEventListener("click", (evt) => {
                evt.preventDefault();
                evt.stopPropagation();
                this.requestHeavyRefresh(card);
            });
            titleRow.appendChild(btn);
            card.classList.add("is-stale");
        } else if (!isStale && existing) {
            existing.remove();
            card.classList.remove("is-stale");
        }
    }

    /**
     * Apply the heavy-stale badge state across all cards inside [grid]
     * after `setPreviews` fires. The extension passes a list of preview
     * ids whose heavy captures weren't refreshed this run; everything
     * else gets its badge cleared.
     */
    updateAll(
        grid: ParentNode,
        heavyStaleIds: readonly string[] | null | undefined,
    ): void {
        const stale = new Set(heavyStaleIds ?? []);
        for (const card of grid.querySelectorAll<HTMLElement>(
            ".preview-card",
        )) {
            this.apply(card, stale.has(card.dataset.previewId ?? ""));
        }
    }
}
