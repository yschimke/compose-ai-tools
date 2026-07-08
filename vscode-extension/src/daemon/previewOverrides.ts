import type {
    PermissionsOverride,
    PreviewOverrides,
    RemoteComposeOverride,
} from "./daemonProtocol";

/**
 * Assemble the unified per-preview {@link PreviewOverrides} bag from the individual
 * host-authoritative override sources, so an edit-driven snapshot `renderNow` resends the full
 * set instead of one field. Without this, editing one override drops the others: the daemon
 * treats each of these as authoritative-per-render and reverts any it doesn't receive.
 *
 * Only the host-authoritative overrides participate, because the daemon drops each when a
 * later render omits it, so they MUST be resent every time:
 *   - `permissions`   — Robolectric grant map, re-seeded from `grants` each composition.
 *   - `remoteCompose` — full-replacement named-value map + profile.
 *   - `clearBackground` — per-render transparent-background ("crisp outline") toggle.
 *
 * `lottie` is intentionally excluded: it's daemon-sticky (`LottieProgressController`
 * remembers the scrub across renders), so omitting it from a later render never regresses
 * the pinned frame — and there's no host-side store to resend it from anyway.
 *
 * Returns `undefined` when no override is active, preserving the "no overrides" wire shape
 * (and the batch-render fast path) for the common case.
 */
export function composePreviewOverrides(parts: {
    permissions?: PermissionsOverride;
    remoteCompose?: RemoteComposeOverride;
    clearBackground?: boolean;
}): PreviewOverrides | undefined {
    const overrides: PreviewOverrides = {};
    if (parts.permissions) overrides.permissions = parts.permissions;
    if (parts.remoteCompose) overrides.remoteCompose = parts.remoteCompose;
    if (parts.clearBackground) overrides.clearBackground = true;
    return Object.keys(overrides).length > 0 ? overrides : undefined;
}
