// Pure planner for the single-target follow-focus teardown that
// `LiveStateController.enforceSingleTargetFollowFocus` performs when the
// user navigates focus off a live card. Lifted into its own file so the
// branch logic (size === 1, focus match short-circuit, lone-id pickoff)
// can be unit-tested without DOM, vscode, or the controller's wider
// transitive imports.
//
// Contract: this module owns no state and performs no side effects. The
// controller wraps it — invokes the planner, posts the live "off" wire
// command for [teardownId], clears its local set, and re-stamps badges.
//
// Multi-target (size > 1) is treated as an explicit Shift+click opt-in
// upstream — those streams persist across focus navigation until the
// user toggles them off, so this planner returns null for any size that
// isn't exactly 1.

/** Result of [planFollowFocusTeardown]. `null` means "no-op": either
 *  there isn't exactly one live target, or the lone live target is the
 *  focused card. A non-null result names the lone live id the caller
 *  should tear down. */
export interface FollowFocusTeardownPlan {
    /** The lone live previewId that should be torn down. The caller
     *  posts the live "off" command for this id, drops it from the
     *  local set, and re-stamps the live badges. */
    teardownId: string;
}

/**
 * Decide whether the single-target follow-focus teardown should fire.
 *
 * Returns a plan only when:
 *  - exactly one previewId is live (multi-target opt-in is preserved),
 *  - and either no card is focused, or the focused card's previewId is
 *    different from the lone live one.
 *
 * Returns null otherwise (empty live set, multi-target live set, or
 * focused card already matches the lone live id).
 */
export function planFollowFocusTeardown(
    liveSet: ReadonlySet<string>,
    focusedPreviewId: string | null,
): FollowFocusTeardownPlan | null {
    if (liveSet.size !== 1) return null;
    const lone = liveSet.values().next().value;
    if (lone === undefined) return null;
    if (focusedPreviewId !== null && focusedPreviewId === lone) return null;
    return { teardownId: lone };
}
