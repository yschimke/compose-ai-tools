/**
 * Structural reference to a Gradle module — only the `modulePath` key is used by the scheduler
 * state classes. Accepts both full `ModuleInfo` instances from the gradle service and the
 * minimal `{ modulePath: string }` shapes the daemon-events handlers reconstruct from
 * `moduleId: string`.
 */
export interface ModuleRef {
    readonly modulePath: string;
}

/**
 * Coalesces concurrent `warmModule` calls so a second caller arriving mid-bootstrap returns
 * the in-flight promise instead of starting a second `composePreviewDaemonStart` (which the
 * first invocation would then cancel). Promises are cleared on settle so a later retry after
 * a cancellation starts fresh rather than getting a rejected promise back.
 */
export class WarmCoalescer<T> {
    private readonly inFlight = new Map<string, Promise<T>>();

    /**
     * If a warm for [module] is already in flight, return its promise. Otherwise start one via
     * [factory], remember it for the duration of the call, and return the new promise.
     */
    getOrStart(module: ModuleRef, factory: () => Promise<T>): Promise<T> {
        const key = module.modulePath;
        const existing = this.inFlight.get(key);
        if (existing) {
            return existing;
        }
        const promise = factory().finally(() => {
            // Identity check guards against the (rare) case where a second warm started after
            // ours settled but before our `finally` ran — we'd otherwise delete its entry.
            if (this.inFlight.get(key) === promise) {
                this.inFlight.delete(key);
            }
        });
        this.inFlight.set(key, promise);
        return promise;
    }

    /** True if a warm for [module] is currently in flight. Used by callers that want to
     *  branch on "is the bootstrap already happening?" before kicking auxiliary UX. */
    isInFlight(module: ModuleRef): boolean {
        return this.inFlight.has(module.modulePath);
    }
}

/**
 * Per-`(module, previewId)` idempotency cache for the scheduler's speculative renders. Bounded
 * by the scroll-ahead budget enforced at the caller; this class just tracks "have we already
 * speculated this pair so a scroll-back doesn't re-queue identical work?".
 */
export class SpeculationCache {
    private readonly seen = new Set<string>();

    has(module: ModuleRef, previewId: string): boolean {
        return this.seen.has(this.key(module, previewId));
    }

    mark(module: ModuleRef, previewId: string): void {
        this.seen.add(this.key(module, previewId));
    }

    /** Drop one pair — used when a render for it completes so a subsequent file-change can
     *  re-render via the reactive path. */
    forget(module: ModuleRef, previewId: string): void {
        this.seen.delete(this.key(module, previewId));
    }

    /** Drop every pair under [module]. Used on `onClasspathDirty` and `onChannelClosed` so a
     *  re-spawned daemon doesn't think it's already pre-warmed those IDs. */
    forgetModule(module: ModuleRef): void {
        const prefix = `${module.modulePath}::`;
        for (const k of [...this.seen]) {
            if (k.startsWith(prefix)) this.seen.delete(k);
        }
    }

    size(): number {
        return this.seen.size;
    }

    private key(module: ModuleRef, previewId: string): string {
        return `${module.modulePath}::${previewId}`;
    }
}

/**
 * Per-module dedup memo for the two protocol notifications whose payload often repeats:
 * `setVisible` (visible IDs) and `setFocus` (focused IDs). Both fire on every editor focus
 * change / scroll event, but the daemon only reacts when the set actually changed — caching
 * the last value here cuts wire chatter without the call site having to manage two parallel
 * maps. Cleared per-module on `onChannelClosed` so a re-spawned daemon sees fresh state.
 */
export class VisibilityMemo {
    private readonly visible = new Map<string, string[]>();
    private readonly focus = new Map<string, string[]>();

    /** True iff the last `setVisible` for [module] was the same set of ids. */
    sameVisibleAsLast(module: ModuleRef, ids: readonly string[]): boolean {
        return sameSet(this.visible.get(module.modulePath), ids);
    }

    /** Remember the last `setVisible` ids for [module]. */
    recordVisible(module: ModuleRef, ids: readonly string[]): void {
        this.visible.set(module.modulePath, [...ids]);
    }

    /** True iff the last `setFocus` for [module] was the same set of ids. */
    sameFocusAsLast(module: ModuleRef, ids: readonly string[]): boolean {
        return sameSet(this.focus.get(module.modulePath), ids);
    }

    /** Remember the last `setFocus` ids for [module]. */
    recordFocus(module: ModuleRef, ids: readonly string[]): void {
        this.focus.set(module.modulePath, [...ids]);
    }

    /** Drop every memoised value for [module]. Used on `onChannelClosed` so a fresh daemon
     *  spawn sees the next visible/focus call as "different" and re-issues it. */
    forgetModule(module: ModuleRef): void {
        const key = module.modulePath;
        this.visible.delete(key);
        this.focus.delete(key);
    }
}

/** Set equality with order-independence and undefined-as-empty. */
function sameSet(
    a: readonly string[] | undefined,
    b: readonly string[],
): boolean {
    if (!a || a.length !== b.length) return false;
    const set = new Set(a);
    return b.every((id) => set.has(id));
}
