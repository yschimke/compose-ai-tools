// Live-badge DOM stamping helper used by `LiveStateController.applyLiveBadge`.
// Lifted into its own file so the DOM operation is testable under happy-dom
// without dragging `LiveStateController`'s wider transitive imports
// (`interactiveInput.ts`'s pointer-handler module, the vscode handle, etc.)
// into the host tsconfig.
//
// The mutation is intentionally torn-down-then-stamped: we strip every
// `.preview-card.live` decoration on entry so removals (Shift+click off,
// daemon-not-ready, setPreviews dropping a previewId) cleanly wipe the
// prior decoration before we re-stamp from the live set. The per-card
// stop-button injection is delegated to [ensureControls] so callers can
// keep their controller-bound handlers (`stopInteractiveForCard`, the
// pointer-input attachment) without leaking that surface here.

import { sanitizeId } from "./cardData";

/**
 * Re-stamp every `.preview-card.live` decoration in the document from
 * [liveIds]. Cards present in [liveIds] gain `.live` and have
 * [ensureControls] invoked on them; every other previously-live card has
 * `.live` removed and its `.card-live-stop-btn` overlay torn down.
 *
 * Idempotent: calling repeatedly converges on the same final DOM.
 *
 * Silently skips previewIds in [liveIds] that have no matching DOM card —
 * `pruneLive` ahead of a fresh `setPreviews` is the supported way to keep
 * the set in lockstep, but a transient mismatch (e.g. between a daemon
 * teardown and the next manifest) shouldn't blow up the badge pass.
 */
export function stampLiveBadgesOnGrid(
    liveIds: ReadonlySet<string>,
    ensureControls: (card: HTMLElement) => void,
): void {
    document.querySelectorAll(".preview-card.live").forEach((c) => {
        c.classList.remove("live");
        c.querySelector(".card-live-stop-btn")?.remove();
    });
    if (liveIds.size === 0) return;
    liveIds.forEach((previewId) => {
        const card = document.getElementById(
            "preview-" + sanitizeId(previewId),
        );
        if (!(card instanceof HTMLElement)) return;
        card.classList.add("live");
        ensureControls(card);
    });
}
