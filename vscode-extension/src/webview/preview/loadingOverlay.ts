// Two-stage per-card loading overlay during stealth refresh.
//
// Lifted verbatim from `behavior.ts`'s `markAllLoading` /
// `scheduleOverlayEscalation` / `cancelOverlayEscalation` cluster.
//
//   stage 1 (0–500 ms): `loading-overlay minimal` — tiny corner
//     spinner, no dim, no blur. Most daemon hits land in this
//     window — image swap reads as a clean update, no
//     "dim → undim" flicker.
//   stage 2 (>500 ms):  `loading-overlay subtle` — dim + blur +
//     corner spinner. The build is taking real time, escalate so
//     the user sees the panel is actually working.
//
// Cards whose `updateImage` arrives during stage 1 never see stage 2.
// `cancel()` cuts the timer when the extension explicitly clears
// loading state (e.g. an empty-state notice took over the panel).
//
// Held as a class so the escalation timer doesn't need a module-
// global. Constructor takes nothing — all state is per-instance.

const ESCALATE_MS = 500;

export class LoadingOverlay {
    private escalationTimer: ReturnType<typeof setTimeout> | null = null;

    /**
     * Drop a `.loading-overlay.minimal` onto every card that has
     * existing pixels (skip cards still showing the skeleton — there's
     * nothing useful to cover yet). Then arm the 500ms timer that
     * promotes any still-present minimal overlays to `subtle`.
     */
    markAll(): void {
        for (const card of document.querySelectorAll<HTMLElement>(
            ".preview-card",
        )) {
            const container =
                card.querySelector<HTMLElement>(".image-container");
            if (!container) continue;
            // Skip if already has an overlay (e.g. previous refresh still running)
            if (container.querySelector(".loading-overlay")) continue;
            // Don't add overlay if there's just a skeleton (nothing useful to cover)
            if (
                container.querySelector(".skeleton") &&
                !container.querySelector("img")
            )
                continue;
            const overlay = document.createElement("div");
            overlay.className = "loading-overlay minimal";
            overlay.innerHTML =
                '<div class="spinner" aria-label="Refreshing"></div>';
            container.appendChild(overlay);
        }
        this.scheduleEscalation();
    }

    /** Cut the escalation timer — called from the extension's
     *  `clearLoading` path so cards don't visibly flip to `subtle`
     *  after the loading state has been cleared. */
    cancel(): void {
        if (this.escalationTimer) {
            clearTimeout(this.escalationTimer);
            this.escalationTimer = null;
        }
    }

    private scheduleEscalation(): void {
        if (this.escalationTimer) clearTimeout(this.escalationTimer);
        this.escalationTimer = setTimeout(() => {
            this.escalationTimer = null;
            // Promote every still-present minimal overlay to subtle.
            // Cards whose images already arrived have removed their
            // overlays, so this only touches cards still waiting.
            for (const o of document.querySelectorAll(
                ".loading-overlay.minimal",
            )) {
                o.classList.remove("minimal");
                o.classList.add("subtle");
            }
        }, ESCALATE_MS);
    }
}
