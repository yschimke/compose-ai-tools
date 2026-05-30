// Pure (no DOM, no I/O) merge helper for the panel's permissions tab-body
// edit pipeline. Lives in `daemon/` (host tsconfig) so the routing decision
// is testable in plain mocha — `handleSetPermissionsOverride` in
// `extension.ts` calls in here and forwards the merged bag to a
// `renderNow.overrides.permissions` dispatch.
//
// Why a separate helper: the cumulative bag is the load-bearing invariant
// of the edit pipeline ("user grants CAMERA, then denies RECORD_AUDIO,
// then clears CAMERA — the next `renderNow` payload must reflect all three
// edits in order"). Inline in `extension.ts` it's only reachable through a
// full extension-host test fixture; here it's a one-call unit test per
// behaviour.

import type {
    PermissionGrantOverride,
    PermissionsOverride,
} from "./daemonProtocol";
import type { PermissionsChangeDetail } from "../types";

/**
 * Apply one panel-side [change] to [prior], returning the next cumulative
 * permissions-override bag. Pure — neither argument is mutated; the result
 * is a fresh object every call so callers can stash it without aliasing
 * the prior snapshot.
 *
 * Semantics:
 *  * `field: "setGrant"` — set the permission entry to the requested grant.
 *    Overwrites an existing entry for the same name.
 *  * `field: "clearGrant"` — remove the named permission from the bag (the
 *    next render falls back to Robolectric's manifest baseline for it).
 *  * `field: "clearAll"` — return an empty `PermissionsOverride()` so the
 *    daemon strips every panel-pinned grant on the next render.
 *
 * `prior` may be `null` / `undefined` (first edit for a preview); the
 * result then carries just the change. A `clearGrant` on a missing prior
 * is a no-op that still returns an empty bag rather than `undefined`, so
 * the caller can forward it as a normal override (clears any in-flight
 * grants the daemon may have pinned in a previous session).
 */
export function mergePermissionsChange(
    prior: PermissionsOverride | null | undefined,
    change: PermissionsChangeDetail,
): PermissionsOverride {
    const baseGrants: Record<string, PermissionGrantOverride> = {
        ...(prior?.grants ?? {}),
    };
    if (change.field === "clearAll") {
        return { grants: {} };
    }
    if (change.field === "clearGrant") {
        delete baseGrants[change.permission];
        return { grants: baseGrants };
    }
    baseGrants[change.permission] = change.grant;
    return { grants: baseGrants };
}
