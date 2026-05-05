// Viewport tracking for the preview grid.
//
// Lifted verbatim from `behavior.ts`'s `IntersectionObserver` /
// `predictNextIds` / `publishViewport` / `scheduleViewportPublish` /
// scroll-velocity EMA block. See PREDICTIVE.md § 7 — the webview owns
// the geometry, the daemon's scroll-ahead scheduler consumes the
// `viewportUpdated` snapshots; when the daemon path is off the
// extension simply ignores them.
//
// The class is a thin lifecycle wrapper. Per-event work is unchanged:
//
//  - `IntersectionObserver` callback adds / removes ids from
//    `intersecting`, calls back to the host when a previously-live
//    preview leaves the viewport, then `scheduleViewportPublish`s.
//  - A `passive` document `scroll` listener feeds the signed
//    velocity EMA so `predictNextIds` can project the next-page set.
//  - A 120ms `setTimeout` coalesces the publish so a scroll burst
//    doesn't fan out per-card `viewportUpdated` posts.
//
// The host hands in an `onLiveCardScrolledOut` callback for the
// "auto-stop interactive when out of view" rule — keeping the live-set
// mutation in `behavior.ts` (where the rest of the live-state lives)
// rather than copying it over. Re-entering the viewport doesn't
// auto-resume — the user re-clicks if they want it back.

import type { VsCodeApi } from "../shared/vscode";

export interface ViewportTrackerConfig {
    vscode: VsCodeApi<unknown>;
    /**
     * Fired when a card with `previewId` leaves the viewport. The host
     * is expected to check whether that preview is currently in the
     * live set and, if so, toggle live mode off (post setInteractive,
     * update local sets, refresh badges/buttons).
     */
    onCardLeftViewport(previewId: string): void;
}

export class ViewportTracker {
    private readonly intersecting = new Set<string>();
    private readonly observer: IntersectionObserver | null;
    private lastScrollTop = 0;
    private lastScrollAt = 0;
    private scrollVelocity = 0; // px/ms, positive = scrolling down
    private debounce: ReturnType<typeof setTimeout> | null = null;

    constructor(private readonly config: ViewportTrackerConfig) {
        this.observer =
            "IntersectionObserver" in window
                ? new IntersectionObserver(
                      (entries) => this.onIntersect(entries),
                      { root: null, rootMargin: "0px", threshold: 0.1 },
                  )
                : null;
        document.addEventListener("scroll", this.onScroll, { passive: true });
    }

    observe(card: HTMLElement): void {
        this.observer?.observe(card);
    }

    /**
     * Drop a card from the tracker — removes its id from `intersecting`
     * and unhooks the observer. Called when a card is removed from the
     * grid (e.g. its preview was deleted on a re-discovery pass) so the
     * tracker's snapshot doesn't keep referring to detached DOM.
     */
    forget(previewId: string, card: HTMLElement): void {
        this.intersecting.delete(previewId);
        this.observer?.unobserve(card);
    }

    unobserveAll(): void {
        if (!this.observer) return;
        this.intersecting.clear();
        for (const card of document.querySelectorAll<HTMLElement>(
            ".preview-card",
        )) {
            this.observer.unobserve(card);
        }
    }

    private readonly onScroll = (): void => {
        const now = performance.now();
        const top = window.scrollY || document.documentElement.scrollTop || 0;
        const dt = Math.max(1, now - this.lastScrollAt);
        const dy = top - this.lastScrollTop;
        // Light EMA so a single jittery frame doesn't flip the predicted set.
        this.scrollVelocity = this.scrollVelocity * 0.4 + (dy / dt) * 0.6;
        this.lastScrollAt = now;
        this.lastScrollTop = top;
        this.schedulePublish();
    };

    private onIntersect(entries: IntersectionObserverEntry[]): void {
        for (const entry of entries) {
            const id = (entry.target as HTMLElement).dataset.previewId;
            if (!id) continue;
            if (entry.isIntersecting) {
                this.intersecting.add(id);
            } else {
                this.intersecting.delete(id);
                this.config.onCardLeftViewport(id);
            }
        }
        this.schedulePublish();
    }

    private schedulePublish(): void {
        if (this.debounce) return;
        this.debounce = setTimeout(() => {
            this.debounce = null;
            this.publish();
        }, 120);
    }

    private publish(): void {
        const visible = Array.from(this.intersecting);
        const predicted = this.predictNextIds();
        this.config.vscode.postMessage({
            command: "viewportUpdated",
            visible,
            predicted,
        });
    }

    /**
     * Project the next-page IDs based on scroll direction. Velocity is
     * signed (px/ms): positive = scrolling down → predict cards below
     * the lowest currently-visible card; negative = predict above.
     */
    private predictNextIds(): string[] {
        if (Math.abs(this.scrollVelocity) < 0.05) return [];
        const allCards = Array.from(
            document.querySelectorAll<HTMLElement>(".preview-card"),
        ).filter((c) => !c.classList.contains("filtered-out"));
        const visibleCards = allCards.filter((c) => {
            const id = c.dataset.previewId;
            return !!id && this.intersecting.has(id);
        });
        if (visibleCards.length === 0) return [];
        // Cards are in DOM order; pick the ones immediately ahead of the
        // last visible (or before the first) up to a bounded count.
        const PREDICT_AHEAD = 4;
        const ids: string[] = [];
        if (this.scrollVelocity > 0) {
            const lastVisibleIdx = allCards.indexOf(
                visibleCards[visibleCards.length - 1],
            );
            for (
                let i = lastVisibleIdx + 1;
                i < allCards.length && ids.length < PREDICT_AHEAD;
                i++
            ) {
                const id = allCards[i].dataset.previewId;
                if (id && !this.intersecting.has(id)) ids.push(id);
            }
        } else {
            const firstVisibleIdx = allCards.indexOf(visibleCards[0]);
            for (
                let i = firstVisibleIdx - 1;
                i >= 0 && ids.length < PREDICT_AHEAD;
                i--
            ) {
                const id = allCards[i].dataset.previewId;
                if (id && !this.intersecting.has(id)) ids.push(id);
            }
        }
        return ids;
    }
}
