// Pure state-transition planners for the live (interactive) and
// recording sets in `LiveStateController`. Lifted out so the
// single-target / multi-target / single-target-toggle-off semantics
// can be unit-tested without DOM, vscode, or the LiveStateController's
// wider DOM-bound surface.
//
// The two functions here are pure — they take the current Set, the
// target id, and the shift modifier, and return a plan describing:
//   - which prior ids should be turned off on the wire
//   - whether the target itself should turn on or off
//   - what the post-transition Set looks like
//
// The DOM-bound `LiveStateController` consumes the plan: it posts the
// `requestStreamStart` / `requestStreamStop` / `setRecording` wire
// commands the plan calls for, replaces its internal Set with `next`,
// and re-runs the badge / button hooks. This module owns no state of
// its own.

/** Plan returned by the transition functions. Callers apply it by:
 *  1. For each `id` in `deactivate`: post `requestStreamStop(id)`
 *     (or `setRecording(id, false, format)` for the recording variant).
 *  2. Replace the local Set with `next`.
 *  3. Post `requestStreamStart(targetId)` / `requestStreamStop(targetId)`
 *     based on `turnOnTarget` (or `setRecording`).
 */
export interface LiveToggleResult {
    /** Ids to send the "off" wire message for. Empty in the multi-
     *  target Shift+click path. */
    deactivate: readonly string[];
    /** Whether the target id should turn on (true) or off (false). */
    turnOnTarget: boolean;
    /** New live / recording set after the transition. Always a fresh
     *  Set instance — callers can replace by reference. */
    next: Set<string>;
}

/**
 * Plan a live-mode toggle for [targetId] on the current live set.
 *
 * - `shift = false` (single-target): if any other ids are currently
 *   live, they're queued for deactivation. The target either turns on
 *   (when not currently live) or toggles off (when already live —
 *   single-target's "tap the live card again to stop"). The next set
 *   contains only the target if it's turning on, otherwise empty.
 *
 * - `shift = true` (multi-target): nothing else in the live set is
 *   touched. The target toggles in or out of the set.
 */
export function planLiveToggle(
    current: ReadonlySet<string>,
    targetId: string,
    shift: boolean,
): LiveToggleResult {
    const wasLive = current.has(targetId);
    const turnOnTarget = !wasLive;

    if (!shift) {
        // Single-target: deactivate every prior live id except the
        // target (the target's own state is set by `turnOnTarget`).
        const deactivate: string[] = [];
        for (const prior of current) {
            if (prior !== targetId) deactivate.push(prior);
        }
        const next = new Set<string>();
        if (turnOnTarget) next.add(targetId);
        return { deactivate, turnOnTarget, next };
    }

    // Multi-target: leave others alone, toggle just the target.
    const next = new Set(current);
    if (turnOnTarget) {
        next.add(targetId);
    } else {
        next.delete(targetId);
    }
    return { deactivate: [], turnOnTarget, next };
}

/**
 * Plan a recording toggle for [targetId] on the current recording set.
 *
 * Recording is currently single-target only — the panel doesn't expose
 * a Shift+click path for recording the way it does for live. The plan
 * mirrors `planLiveToggle` with `shift = false`: turn the target on or
 * off; deactivate any prior ids when turning a new target on.
 */
export function planRecordingToggle(
    current: ReadonlySet<string>,
    targetId: string,
): LiveToggleResult {
    const wasRecording = current.has(targetId);
    const turnOnTarget = !wasRecording;

    const deactivate: string[] = [];
    if (turnOnTarget) {
        // Switching on: stop any other recording first so we never have
        // two going at once.
        for (const prior of current) {
            if (prior !== targetId) deactivate.push(prior);
        }
    }

    const next = new Set<string>();
    if (turnOnTarget) next.add(targetId);
    return { deactivate, turnOnTarget, next };
}
