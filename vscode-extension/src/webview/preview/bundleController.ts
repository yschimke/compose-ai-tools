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
    /** Send a `setDataExtensionEnabled` to the extension host. */
    setKindEnabled(kind: string, enabled: boolean): void;
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
        this.host.setKindEnabled(kind, enabled);
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
        const previouslyEnabled = this.enabled.get(id);
        const kinds = previouslyEnabled ?? [...defaultOnKindsFor(id)];
        this.enabled.set(id, kinds);
        for (const k of kinds) this.host.setKindEnabled(k, true);
        this.tab = id;
        this.fire();
    }

    private deactivate(id: BundleId): void {
        for (const k of this.enabledKindsFor(id)) {
            this.host.setKindEnabled(k, false);
        }
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
