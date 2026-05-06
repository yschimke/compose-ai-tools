import * as assert from "assert";
import type { ReactiveControllerHost } from "lit";
import { Store } from "../webview/shared/store";
import { StoreController } from "../webview/shared/storeController";

interface TestState {
    counter: number;
    label: string;
    nested: { id: string };
}

const initial: TestState = {
    counter: 0,
    label: "init",
    nested: { id: "a" },
};

/** Minimal ReactiveControllerHost stub. Captures the controller and
 *  the requestUpdate calls so tests can assert subscription behaviour. */
class HostStub implements ReactiveControllerHost {
    requestUpdateCalls = 0;
    addControllerCalls = 0;
    addController(): void {
        this.addControllerCalls++;
    }
    removeController(): void {
        // unused
    }
    requestUpdate(): void {
        this.requestUpdateCalls++;
    }
    updateComplete = Promise.resolve(true);
}

function makeStore(): Store<TestState> {
    return new Store<TestState>({ ...initial, nested: { ...initial.nested } });
}

describe("StoreController", () => {
    describe("construction", () => {
        it("captures the initial selector value before subscribing", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            assert.strictEqual(controller.value, 0);
        });

        it("registers itself with the host via addController", () => {
            const host = new HostStub();
            const store = makeStore();
            new StoreController(host, store, (s) => s.counter);
            assert.strictEqual(host.addControllerCalls, 1);
        });

        it("does NOT subscribe to the store at construction time", () => {
            // hostConnected drives the subscription. Constructing the
            // controller without connecting it should leave the store
            // listener-less.
            const host = new HostStub();
            const store = makeStore();
            new StoreController(host, store, (s) => s.counter);
            store.setState({ counter: 42 });
            assert.strictEqual(host.requestUpdateCalls, 0);
        });
    });

    describe("hostConnected → store change", () => {
        it("requestUpdate fires when the selector value changes", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            store.setState({ counter: 1 });
            assert.strictEqual(host.requestUpdateCalls, 1);
            assert.strictEqual(controller.value, 1);
        });

        it("requestUpdate does NOT fire when the selector returns the same value", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            // Mutate an unrelated field; selector value is unchanged.
            store.setState({ label: "next" });
            assert.strictEqual(host.requestUpdateCalls, 0);
            assert.strictEqual(controller.value, 0);
        });

        it("requestUpdate fires on each new selector value, not on every store change", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            store.setState({ counter: 1 }); // counter 0 → 1: fires
            store.setState({ counter: 1, label: "x" }); // counter 1 → 1: skips
            store.setState({ counter: 2 }); // counter 1 → 2: fires
            assert.strictEqual(host.requestUpdateCalls, 2);
            assert.strictEqual(controller.value, 2);
        });

        it("uses reference-equality on selector return values", () => {
            // A nested object whose reference is replaced should trigger
            // an update even if the inner contents are deep-equal.
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.nested,
            );
            controller.hostConnected();
            // Same id, but a fresh object reference → update fires.
            store.setState({ nested: { id: "a" } });
            assert.strictEqual(host.requestUpdateCalls, 1);
        });
    });

    describe("hostDisconnected", () => {
        it("unsubscribes from the store so further changes don't fire requestUpdate", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            controller.hostDisconnected();
            store.setState({ counter: 99 });
            assert.strictEqual(host.requestUpdateCalls, 0);
        });

        it("hostDisconnected is idempotent (calling twice is safe)", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            controller.hostDisconnected();
            // Second call should not throw.
            assert.doesNotThrow(() => controller.hostDisconnected());
        });

        it("re-connecting after disconnect resubscribes cleanly", () => {
            const host = new HostStub();
            const store = makeStore();
            const controller = new StoreController(
                host,
                store,
                (s) => s.counter,
            );
            controller.hostConnected();
            controller.hostDisconnected();
            controller.hostConnected();
            store.setState({ counter: 1 });
            assert.strictEqual(host.requestUpdateCalls, 1);
        });
    });
});
