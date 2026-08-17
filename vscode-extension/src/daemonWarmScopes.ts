/**
 * Which (module, file) scopes have had their previews pre-rendered by the
 * daemon, and **for which previews**.
 *
 * ### Why this is a ledger and not a `Set`
 *
 * The daemon view-open warm-up exists to stop a focus bounce re-rendering cards
 * that are already on screen. It was suppressed by a bare set of scope keys, so
 * "this file has been warmed" was the whole memory — and a scope, once warmed,
 * stayed warmed for the life of the daemon. Only a daemon death (classpath
 * dirty, channel closed) cleared it.
 *
 * That is wrong for the case where the file's previews *change* under a living
 * daemon, which is the ordinary act of adding a `@Preview` to the file you are
 * looking at. Discovery found the new id and the panel drew its card; the
 * warm-up then declined to render, because the scope was already warm. The card
 * sat there with no pixels behind it until the user navigated away and back, or
 * something restarted the daemon. It also made the
 * `B. edit a @Preview source, refresh, expect new id` end-to-end scenario fail
 * intermittently — whenever the daemon happened to survive the edit, the PNG the
 * test waits for was never written.
 *
 * Recording the id set turns the question from "has this file been warmed" into
 * "has it been warmed for *these* previews", which suppresses the bounce (same
 * ids, same answer) without swallowing a real change.
 */
export class DaemonWarmScopeLedger {
    private readonly warmed = new Map<string, string>();

    /**
     * Whether [ids] still need a warm-up render in [scopeKey].
     *
     * True when the scope has never been warmed, or was warmed for a different
     * set of previews. Order is not significant — discovery is free to reorder
     * a manifest without that meaning anything changed.
     */
    shouldWarm(scopeKey: string, ids: readonly string[]): boolean {
        return this.warmed.get(scopeKey) !== fingerprint(ids);
    }

    /** Record that [scopeKey] has been warmed for exactly [ids]. */
    markWarmed(scopeKey: string, ids: readonly string[]): void {
        this.warmed.set(scopeKey, fingerprint(ids));
    }

    /**
     * Forget one scope — used when a warm-up throws, so the next attempt is not
     * suppressed by a render that never happened.
     */
    forget(scopeKey: string): void {
        this.warmed.delete(scopeKey);
    }

    /**
     * Forget every scope belonging to [moduleId]. Called when its daemon dies:
     * a new JVM has rendered nothing, whatever the previous one covered.
     */
    clearModule(moduleId: string): void {
        const prefix = `${moduleId}::`;
        for (const key of [...this.warmed.keys()]) {
            if (key.startsWith(prefix)) {
                this.warmed.delete(key);
            }
        }
    }

    /** Scope key for a module path and its module-relative source file. */
    static scopeKey(modulePath: string, filterFile: string): string {
        return `${modulePath}::${filterFile}`;
    }
}

/**
 * Order-independent fingerprint of an id set.
 *
 * The separator is a newline, which cannot occur in a preview id (they are
 * Kotlin FQNs plus a variant suffix), so two different sets cannot fingerprint
 * alike. A separator that *can* appear in an id would let `["a", "b"]` and
 * `["a<sep>b"]` collide.
 */
function fingerprint(ids: readonly string[]): string {
    return [...ids].sort().join("\n");
}
