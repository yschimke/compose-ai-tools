// Bundle controller — owns the chip ↔ tab ↔ overlay state machine that
// the new panel shell drives.
//
// Three pieces of state:
//
//   1. `activeBundles` — the set of bundle ids whose chip is pressed.
//      Order is sticky-MRU within `activeBundles` (re-press → move to
//      front so the user's last toggle gets the tab focus).
//   2. `enabledKindsByBundle` — per-bundle override of which kinds in
//      that bundle are subscribed. Starts as the bundle's default-ON
//      list; the "Configure…" expander mutates it.
//   3. `activeTab` — id of the tab the user is currently viewing.
//      Always a member of `activeBundles` or `null` (no data tabs).
//
// On state change, the controller emits a `bundle-state-changed`
// CustomEvent so subscribers (the chip bar, the tab row, card overlays)
// can re-render. The controller is the **only** writer of bundle state;
// the chip bar and tab row dispatch user-action events back to it.

import {
    BUNDLES,
    type BundleDescriptor,
    type BundleId,
    bundleForKind,
    defaultOnKindsFor,
    getBundle,
} from "./bundleRegistry";

export interface BundleControllerHost {
    /**
     * Send a `setDataExtensionEnabled` to the extension host for one
     * or more kinds at once. Batching matters for chip activation:
     * issuing one wire message per kind lets the daemon lock the
     * render mode after the first subscribe arrives, so subsequent
     * kinds' data products miss the in-flight render (e.g.
     * `a11y/hierarchy` attaches but `a11y/atf` doesn't). The
     * controller routes `activate` / `deactivate` (all default-ON
     * kinds together) and Configure-expander row toggles (single
     * kind) through this one entry point.
     */
    setKindsEnabled(kinds: readonly string[], enabled: boolean): void;
    /** Persist a snapshot for restore across panel reload. */
    persist(snapshot: BundleSnapshot): void;
}

/**
 * Serialisable shape. Loaded on construction; written via
 * `host.persist` on every state change.
 */
export interface BundleSnapshot {
    /** Active bundle ids in MRU order, most-recent-first. */
    activeBundles: BundleId[];
    /** Per-bundle enabled-kinds override. Empty list = "no kinds in
     *  this bundle subscribed" — different from "key absent" (use
     *  defaults). */
    enabledKindsByBundle: Partial<Record<BundleId, string[]>>;
    /** Active tab id; falls back to `activeBundles[0]` if stale. */
    activeTab: BundleId | null;
}

export type BundleStateListener = (state: BundleState) => void;

export interface BundleState {
    bundles: readonly BundleDescriptor[];
    activeBundles: readonly BundleId[];
    activeTab: BundleId | null;
    enabledKinds(bundleId: BundleId): readonly string[];
    /** All subscribed kinds across all active bundles, deduped. */
    subscribedKinds(): readonly string[];
}

export class BundleController {
    private active: BundleId[];
    private enabled: Map<BundleId, string[]>;
    private tab: BundleId | null;
    private readonly listeners = new Set<BundleStateListener>();

    constructor(
        private readonly host: BundleControllerHost,
        initial?: BundleSnapshot,
    ) {
        this.active =
            initial?.activeBundles?.filter((id) => !!getBundle(id)) ?? [];
        this.enabled = new Map();
        if (initial?.enabledKindsByBundle) {
            for (const [id, kinds] of Object.entries(
                initial.enabledKindsByBundle,
            )) {
                const bundle = getBundle(id as BundleId);
                if (!bundle || !kinds) continue;
                // Filter to kinds the bundle actually owns — guards
                // against catalogue drift on panel reload.
                const filtered = kinds.filter((k) =>
                    bundle.kinds.some((bk) => bk.kind === k),
                );
                this.enabled.set(bundle.id, filtered);
            }
        }
        if (initial?.activeTab && this.active.includes(initial.activeTab)) {
            this.tab = initial.activeTab;
        } else {
            this.tab = this.active[0] ?? null;
        }
    }

    /** State snapshot for subscribers. */
    state(): BundleState {
        return {
            bundles: BUNDLES,
            activeBundles: this.active,
            activeTab: this.tab,
            enabledKinds: (id) => this.enabledKindsFor(id),
            subscribedKinds: () => this.allSubscribed(),
        };
    }

    onChange(listener: BundleStateListener): () => void {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    }

    /**
     * Toggle a bundle chip. ON → subscribe default-ON kinds + open
     * the tab; OFF → unsubscribe everything in the bundle + close
     * the tab. Re-pressing an active chip is the "OFF via chip"
     * branch in the design doc's state-machine table.
     */
    toggleBundle(id: BundleId): void {
        const bundle = getBundle(id);
        if (!bundle) return;
        if (this.active.includes(id)) {
            this.deactivate(id);
        } else {
            this.activate(id);
        }
    }

    /**
     * Tab close (`×`) — same behaviour as toggling the chip off. The
     * design doc makes these redundant on purpose so the dismiss path
     * is reachable from wherever the user's eye lands.
     */
    closeTab(id: BundleId): void {
        if (this.active.includes(id)) this.deactivate(id);
    }

    /**
     * Drop every active bundle. Used when `composePreview.early
     * Features.enabled` is toggled off mid-session — the chip bar /
     * tab row go `hidden`, and this unsubscribes from every kind so
     * the daemon stops streaming data products the user can no longer
     * see. Re-enabling the flag leaves the user back at "no active
     * bundles" rather than restoring stale state from before the
     * disable.
     */
    deactivateAll(): void {
        for (const id of [...this.active]) {
            this.deactivate(id);
        }
    }

    /** Switch the visible tab. */
    selectTab(id: BundleId | null): void {
        if (id === null) {
            this.tab = null;
            this.fire();
            return;
        }
        if (!this.active.includes(id)) return;
        this.tab = id;
        this.fire();
    }

    /**
     * Per-kind toggle from the bundle's "Configure…" expander. The
     * bundle stays active even if the user disables every kind —
     * the chip remains pressed and the tab visible (so they can
     * re-enable kinds without re-opening the bundle).
     */
    setKindEnabled(bundleId: BundleId, kind: string, enabled: boolean): void {
        const bundle = getBundle(bundleId);
        if (!bundle) return;
        if (!bundle.kinds.some((k) => k.kind === kind)) return;
        const current = [...this.enabledKindsFor(bundleId)];
        const idx = current.indexOf(kind);
        if (enabled && idx === -1) {
            current.push(kind);
        } else if (!enabled && idx !== -1) {
            current.splice(idx, 1);
        } else {
            return;
        }
        this.enabled.set(bundleId, current);
        this.host.setKindsEnabled([kind], enabled);
        this.fire();
    }

    /**
     * Routes a daemon-side change of subscription back into the
     * bundle state — keeps the controller honest when subscriptions
     * are mutated outside the chip bar (e.g. the focus-inspector
     * checkbox path still in use during migration).
     */
    handleExternalKindToggle(kind: string, enabled: boolean): void {
        const id = bundleForKind(kind);
        if (!id) return;
        const current = [...this.enabledKindsFor(id)];
        const idx = current.indexOf(kind);
        const wasActive = this.active.includes(id);
        let changed = false;
        if (enabled && idx === -1) {
            current.push(kind);
            changed = true;
        } else if (!enabled && idx !== -1) {
            current.splice(idx, 1);
            changed = true;
        }
        if (changed) this.enabled.set(id, current);
        // Promote the bundle to active whenever an external path turns
        // a kind on, even if the kind was already in the bundle's
        // default-ON set — the user's intent ("I subscribed to X") is
        // the same as toggling the chip on.
        if (enabled && !wasActive) {
            this.active = [id, ...this.active];
            if (this.tab === null) this.tab = id;
            this.fire();
            return;
        }
        if (changed) this.fire();
    }

    private activate(id: BundleId): void {
        // MRU: most-recently activated lives at index 0 so the chip
        // bar shows it first.
        this.active = [id, ...this.active.filter((x) => x !== id)];
        // `??` falls back on null/undefined only, not on an empty
        // list — so a snapshot that persisted `{a11y: []}` (the user
        // turned every kind off via Configure, or a kind name drifted
        // out of the registry and was filtered to nothing on load)
        // would land here with `kinds = []`, skip the
        // `setKindsEnabled` call below, and leave the chip pressed
        // against a bundle that has no subscriptions and never paints
        // anything. Treat empty-stored as "no preference" and snap
        // back to the bundle's defaults — pressing the chip should
        // always mean "I want this bundle's data."
        const previouslyEnabled = this.enabled.get(id);
        const kinds =
            previouslyEnabled && previouslyEnabled.length > 0
                ? previouslyEnabled
                : [...defaultOnKindsFor(id)];
        this.enabled.set(id, kinds);
        // Batched on purpose — the extension host's
        // `handleSetDataExtensionEnabled` ships a single `data/subscribe`
        // sequence followed by one `renderNow`, so all subscriptions
        // land before the daemon decides which data products to attach.
        // Per-kind dispatch races the daemon's mode-lock-on-first-
        // subscribe; the second kind's product (e.g. `a11y/atf`)
        // misses the render and the bundle shows partial data.
        if (kinds.length > 0) this.host.setKindsEnabled(kinds, true);
        this.tab = id;
        this.fire();
    }

    private deactivate(id: BundleId): void {
        const kinds = this.enabledKindsFor(id);
        if (kinds.length > 0) this.host.setKindsEnabled([...kinds], false);
        this.active = this.active.filter((x) => x !== id);
        // Preserve the per-kind enable set on the dropped bundle so
        // a re-press restores the user's last configuration rather
        // than the bundle defaults. (Same intuition as a closed tab
        // remembering its scroll position.)
        if (this.tab === id) {
            this.tab = this.active[0] ?? null;
        }
        this.fire();
    }

    private enabledKindsFor(id: BundleId): readonly string[] {
        const stored = this.enabled.get(id);
        if (stored) return stored;
        return defaultOnKindsFor(id);
    }

    private allSubscribed(): readonly string[] {
        const out = new Set<string>();
        for (const id of this.active) {
            for (const k of this.enabledKindsFor(id)) out.add(k);
        }
        return [...out];
    }

    private fire(): void {
        const snapshot: BundleSnapshot = {
            activeBundles: [...this.active],
            enabledKindsByBundle: Object.fromEntries(
                [...this.enabled.entries()].map(([k, v]) => [k, [...v]]),
            ) as Partial<Record<BundleId, string[]>>,
            activeTab: this.tab,
        };
        this.host.persist(snapshot);
        const state = this.state();
        for (const listener of this.listeners) listener(state);
    }
}
