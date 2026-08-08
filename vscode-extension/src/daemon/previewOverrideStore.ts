import type {
    PermissionsOverride,
    PreviewOverrides,
    RemoteComposeOverride,
    RemoteNamedValueWire,
} from "./daemonProtocol";
import type {
    PermissionsChangeDetail,
    RemoteComposeChangeDetail,
} from "../types";
import { mergePermissionsChange } from "./permissionsMerge";
import { mergeRemoteComposeChange } from "./remoteComposeMerge";
import { composePreviewOverrides } from "./previewOverrides";

/** The last `compose/remotecompose` payload the daemon attached for a preview. */
export interface RemoteComposeSnapshot {
    profile?: RemoteComposeOverride["profile"];
    namedValues?: Record<string, RemoteNamedValueWire>;
}

/**
 * The host-authoritative per-preview override state for the edit→render loop.
 *
 * This is one of the state seams described in `docs/AGENTS.md` § "State seams",
 * and it exists because `extension.ts` had grown four module-level collections
 * keyed by the same `previewId`, with the same activation-lifetime scope, that
 * were only ever read together through one `buildPreviewOverrides` helper:
 * `permissionsOverridesByPreview`, `remoteComposeOverridesByPreview`,
 * `latestRemoteComposeByPreview`, and `clearBackgroundByPreview`. Four parallel
 * maps that are always consulted as a unit are one object.
 *
 * Keeping them together also puts the subtle part in one testable place. The
 * daemon treats each of these as **authoritative per render** and reverts any an
 * override bag omits, so every edit has to resend the whole composed set — which
 * is why {@link compose} exists and why a handler that forgot it would silently
 * drop the user's other overrides. And {@link applyRemoteComposeChange} carries
 * the first-edit seeding rule that is easy to get wrong: see its docs.
 *
 * Deliberately **not** owned here: `lottie`. It is daemon-sticky
 * (`LottieProgressController` remembers the scrub across renders), so omitting
 * it from a later render never regresses the pinned frame, and there is no
 * host-side value to resend. See {@link composePreviewOverrides}.
 *
 * Scope is the extension activation, mirroring the daemon-side controllers'
 * own reset-on-session-close scope.
 */
export class PreviewOverrideStore {
    private readonly permissions = new Map<string, PermissionsOverride>();
    private readonly remoteCompose = new Map<string, RemoteComposeOverride>();
    private readonly latestRemoteCompose = new Map<
        string,
        RemoteComposeSnapshot
    >();
    private readonly clearBackground = new Set<string>();

    /**
     * The composed override bag for an edit-driven snapshot `renderNow`, or
     * `undefined` when nothing is active.
     *
     * Every explicit edit handler sends this rather than just the field it
     * changed, because the daemon reverts any authoritative override a render
     * omits — so sending one field alone would drop the others.
     *
     * The auto re-render paths (save / warm-up / heavy opt-in) deliberately stay
     * override-free so a source-change render is never dropped by the daemon's
     * override-in-flight coalescing.
     */
    compose(previewId: string): PreviewOverrides | undefined {
        return composePreviewOverrides({
            permissions: this.permissions.get(previewId),
            remoteCompose: this.remoteCompose.get(previewId),
            clearBackground: this.clearBackground.has(previewId),
        });
    }

    /**
     * Record the latest `compose/remotecompose` payload the daemon attached, so
     * a later first edit can seed from it. Called from `onDataProductsAttached`.
     */
    noteRemoteComposePayload(
        previewId: string,
        snapshot: RemoteComposeSnapshot,
    ): void {
        this.latestRemoteCompose.set(previewId, snapshot);
    }

    /**
     * Merge a named-value edit into the preview's remoteCompose override and
     * return the new bag.
     *
     * `RemoteComposeController.set(...)` applies **full-replacement** semantics
     * on each `renderNow.overrides.remoteCompose`, so a bag built from nothing
     * but the edited field would erase every other value the user's code bound.
     * On the *first* edit for a preview there is no prior override, so we seed
     * from the last-attached payload and the result is "snapshot + this edit".
     *
     * After that first edit the override bag is the source of truth and the
     * snapshot must NOT be consulted again — re-seeding would shadow the user's
     * own edits with stale values from the last attached payload.
     */
    applyRemoteComposeChange(
        previewId: string,
        change: RemoteComposeChangeDetail,
    ): RemoteComposeOverride {
        const prior = this.remoteCompose.get(previewId);
        let seed: RemoteComposeOverride | undefined = prior;
        if (!seed) {
            const snapshot = this.latestRemoteCompose.get(previewId);
            if (snapshot) {
                seed = {
                    profile: snapshot.profile,
                    namedValues: snapshot.namedValues
                        ? { ...snapshot.namedValues }
                        : undefined,
                };
            }
        }
        const next = mergeRemoteComposeChange(seed, change);
        this.remoteCompose.set(previewId, next);
        return next;
    }

    /** Merge a permissions edit into the preview's bag and return the result. */
    applyPermissionsChange(
        previewId: string,
        change: PermissionsChangeDetail,
    ): PermissionsOverride {
        const next = mergePermissionsChange(
            this.permissions.get(previewId),
            change,
        );
        this.permissions.set(previewId, next);
        return next;
    }

    /** Set the focus-toolbar "clear background" (crisp outline) toggle. */
    setClearBackground(previewId: string, enabled: boolean): void {
        if (enabled) this.clearBackground.add(previewId);
        else this.clearBackground.delete(previewId);
    }

    /** The preview's current remoteCompose override, if any. */
    remoteComposeFor(previewId: string): RemoteComposeOverride | undefined {
        return this.remoteCompose.get(previewId);
    }

    /** The preview's current permissions override, if any. */
    permissionsFor(previewId: string): PermissionsOverride | undefined {
        return this.permissions.get(previewId);
    }

    /** Whether "clear background" is on for the preview. */
    isClearBackground(previewId: string): boolean {
        return this.clearBackground.has(previewId);
    }
}
