// Viewport-leave throttle helper used by `LiveStateController.onCardLeftViewport`.
// Lifted into its own file so the routing decision is testable in plain
// mocha without dragging `LiveStateController`'s wider transitive imports
// (`interactiveInput.ts`'s pointer-handler module, the vscode handle, etc.)
// into the host tsconfig.
//
// The contract intentionally leaves [liveSet] untouched: the daemon's
// `stream/visibility` "throttle to keyframes-only" mode keeps the held
// session warm so scroll-back-into-view repaints from the cached anchor
// instead of cold-blanking. The local set still says "this card is live"
// so the LIVE badge survives the throttle (see the comment on
// `LiveStateController.onCardLeftViewport`).

import { liveViewportCommand } from "../../daemon/liveCommand";

/**
 * Soft-throttle a live stream once its card has scrolled fully out of
 * view. If [previewId] isn't in [liveSet], no-op — the card wasn't live
 * anyway, so there's nothing to throttle. Otherwise post a
 * `requestStreamVisibility` (`visible=false`) via [post]; the local set
 * is intentionally left unchanged so the LIVE badge survives the throttle.
 */
export function throttleLiveOnViewportLeave(
    previewId: string,
    liveSet: ReadonlySet<string>,
    post: (msg: unknown) => void,
): void {
    if (!liveSet.has(previewId)) return;
    post(liveViewportCommand(previewId, false));
}
