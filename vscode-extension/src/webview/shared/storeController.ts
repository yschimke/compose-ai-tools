// Lit `ReactiveController` that subscribes a host component to a
// selector over a [Store]. The selector's return value is exposed as
// `controller.value` for use in `render()`. Re-renders fire only when
// the selector's return value changes by reference equality — so
// selectors should return primitives or memoised references for the
// skip-on-no-change behaviour to actually skip.
//
// Subscription is set up in `hostConnected` and torn down in
// `hostDisconnected` so swapping a host out and back in cleanly
// re-subscribes; the initial value is captured at construction time
// from the current store state.

import type { ReactiveController, ReactiveControllerHost } from "lit";
import type { Store } from "./store";

export class StoreController<
    T extends object,
    S,
> implements ReactiveController {
    private unsubscribe?: () => void;
    private last: S;

    /** Latest selector value. Read in `render()`. */
    public value: S;

    constructor(
        private readonly host: ReactiveControllerHost,
        private readonly store: Store<T>,
        private readonly selector: (state: Readonly<T>) => S,
    ) {
        this.value = selector(store.getState());
        this.last = this.value;
        host.addController(this);
    }

    hostConnected(): void {
        this.unsubscribe = this.store.subscribe(() => {
            const next = this.selector(this.store.getState());
            if (next !== this.last) {
                this.last = next;
                this.value = next;
                this.host.requestUpdate();
            }
        });
    }

    hostDisconnected(): void {
        this.unsubscribe?.();
        this.unsubscribe = undefined;
    }
}
