// Pure (no DOM, no I/O) merge helper for the panel's Remote Compose
// tab-body edit pipeline. Lives in `daemon/` (host tsconfig) so the
// routing decision is testable in plain mocha — the extension's
// `handleSetRemoteComposeNamedValue` calls in here and then dispatches
// the merged bag to the right daemon path.
//
// Why a separate helper instead of inlining: the merge is the
// load-bearing invariant of the edit pipeline ("the user can edit
// `seedColor`, then `cornerRadius`, then `seedColor` again, and all
// three values must be reflected in the next `renderNow` /
// `interactive/setRemoteCompose` payload"). Inline in
// `handleSetRemoteComposeNamedValue` it's only reachable through a
// full extension-host test fixture; here it's a one-call unit test
// per behaviour.

import type {
    RemoteComposeOverride,
    RemoteNamedValueWire,
} from "./daemonProtocol";
import type { RemoteComposeChangeDetail } from "../types";

/**
 * Apply one panel-side [change] to [prior], returning the next cumulative
 * override bag. Pure — neither argument is mutated, the result is a
 * fresh object every call so callers can stash it without aliasing
 * the prior snapshot.
 *
 * Semantics:
 *  * `field: "profile"` replaces the profile facet; named values
 *    + accept-list are preserved.
 *  * `field: "namedValue"` merges the single typed entry into the
 *    named-values map; profile + accept-list are preserved. Existing
 *    entries for the same name are overwritten.
 *
 * `prior` may be `null` / `undefined` (first edit for a preview); the
 * result then carries just the change.
 */
export function mergeRemoteComposeChange(
    prior: RemoteComposeOverride | null | undefined,
    change: RemoteComposeChangeDetail,
): RemoteComposeOverride {
    const base: RemoteComposeOverride = prior ?? {};
    const next: RemoteComposeOverride = {
        profile: base.profile,
        namedValues: { ...(base.namedValues ?? {}) },
        acceptedHostActions: base.acceptedHostActions,
    };
    if (change.field === "profile") {
        next.profile = change.value;
    } else {
        next.namedValues = {
            ...(next.namedValues ?? {}),
            [change.name]: change.value as RemoteNamedValueWire,
        };
    }
    return next;
}
