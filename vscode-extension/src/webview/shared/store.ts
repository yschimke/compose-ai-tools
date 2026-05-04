// Tiny synchronous reactive store. Components subscribe via
// [StoreController] to get re-render notifications keyed off a
// selector; non-component callers (e.g. the imperative `behavior.ts`
// during the migration) use `getState` / `setState` directly.
//
// Patches go through reference-equality comparison, so callers MUST
// replace collections rather than mutate them in place — i.e.
// `setState({ ids: new Set([...current, id]) })`, not
// `current.add(id) + setState({ ids: current })`. Same rule scoped
// objects need: pass a fresh `{ ...prev, x: y }`.
//
// The store is intentionally untyped beyond its single generic
// parameter — no actions, no reducers, no selectors-as-strings. The
// preview panel and the history panel each get their own typed store
// instance (`previewStore`, eventually `historyStore`) so the field
// shape is checked at the call site.

export type Listener = () => void;

export class Store<T extends object> {
    private readonly listeners = new Set<Listener>();
    private state: T;

    constructor(initial: T) {
        this.state = initial;
    }

    /** Current snapshot. Treat as read-only — mutating fields in
     *  place will not notify subscribers and may corrupt selector
     *  reference-equality checks. */
    getState(): Readonly<T> {
        return this.state;
    }

    /**
     * Apply a shallow patch to the current state. Reference-compares
     * each provided field against the current state; if nothing
     * actually changed, subscribers are NOT notified. Otherwise a
     * fresh object is allocated and listeners are fired synchronously.
     */
    setState(patch: Partial<T>): void {
        let changed = false;
        const keys = Object.keys(patch) as Array<keyof T>;
        for (const key of keys) {
            const next = patch[key];
            if (next !== undefined && this.state[key] !== next) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        this.state = { ...this.state, ...patch };
        for (const listener of this.listeners) {
            listener();
        }
    }

    /**
     * Subscribe to all state changes. Returns an unsubscribe function.
     * Components should generally use [StoreController] rather than
     * calling this directly — it handles the connect/disconnect dance
     * and the selector reference-equality check.
     */
    subscribe(listener: Listener): () => void {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    }
}
