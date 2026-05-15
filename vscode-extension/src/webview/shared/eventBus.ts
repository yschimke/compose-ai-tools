// Typed event bus for webview CustomEvents.
//
// Webview components dispatch CustomEvents that the host wires up in
// `main.ts`. The event name and detail shape are a stringly-typed
// contract — two files that don't import each other and that each have
// their own green unit tests can quietly drift apart (producer ships,
// consumer never wired). The bus replaces raw `dispatchEvent`/
// `addEventListener` for app events with a small typed wrapper around
// `WebviewEventMap`, and a static test (`eventBusContract.test.ts`)
// asserts every `emit("name", …)` site has at least one matching
// `on("name", …)` site somewhere in the webview source tree.
//
// New events: add the name and detail type to `WebviewEventMap`. The
// `emit`/`on` calls are then type-checked at compile time, and the
// contract test fails if either side is missing.
//
// This is opt-in: pre-existing `new CustomEvent("foo", …)` /
// `addEventListener("foo", …)` callsites are left alone; the contract
// test only checks events that flow through `emit`/`on`. Migrate one
// event at a time.

import type { BundleKindToggledDetail } from "../preview/components/BundleExpander";

/**
 * Central registry of typed app events. Each key is the event name
 * passed to `dispatchEvent`; each value is the `CustomEvent.detail`
 * shape. Adding a new entry here is the only "registration" step —
 * the static contract test reads `emit`/`on` callsites directly from
 * source, so there is no runtime side-effect required at module load.
 */
export interface WebviewEventMap {
    "kind-toggled": BundleKindToggledDetail;
}

export type WebviewEventName = keyof WebviewEventMap;

interface EmitOptions {
    /** Default `true` — matches the previous `dispatchEvent` calls. */
    bubbles?: boolean;
    /** Default `true` — matches the previous `dispatchEvent` calls. */
    composed?: boolean;
}

/**
 * Dispatch a typed `CustomEvent` on [target]. Drop-in replacement for
 * `target.dispatchEvent(new CustomEvent(name, { detail, bubbles, composed }))`
 * — the bubbles/composed defaults match the previous callsites so the
 * migration is mechanical.
 */
export function emit<K extends WebviewEventName>(
    target: EventTarget,
    name: K,
    detail: WebviewEventMap[K],
    options: EmitOptions = {},
): void {
    target.dispatchEvent(
        new CustomEvent<WebviewEventMap[K]>(name, {
            detail,
            bubbles: options.bubbles ?? true,
            composed: options.composed ?? true,
        }),
    );
}

/**
 * Subscribe to a typed event on [target]. The handler is invoked with
 * the typed detail and the underlying `CustomEvent` (some callsites
 * need `evt.target` or `evt.stopPropagation`). Returns an unsubscribe
 * function for callers that want one; existing callsites that don't
 * bother with teardown work unchanged.
 */
export function on<K extends WebviewEventName>(
    target: EventTarget,
    name: K,
    handler: (
        detail: WebviewEventMap[K],
        evt: CustomEvent<WebviewEventMap[K]>,
    ) => void,
): () => void {
    const wrapped = (evt: Event): void => {
        const ce = evt as CustomEvent<WebviewEventMap[K]>;
        handler(ce.detail, ce);
    };
    target.addEventListener(name, wrapped);
    return () => target.removeEventListener(name, wrapped);
}
