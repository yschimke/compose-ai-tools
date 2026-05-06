import * as assert from "assert";
import { Store } from "../webview/shared/store";

interface TestState {
    counter: number;
    label: string;
    flag: boolean;
}

const initial: TestState = { counter: 0, label: "init", flag: false };

describe("Store", () => {
    describe("getState", () => {
        it("returns the initial snapshot", () => {
            const store = new Store<TestState>({ ...initial });
            assert.deepStrictEqual(store.getState(), initial);
        });

        it("reflects committed setState patches", () => {
            const store = new Store<TestState>({ ...initial });
            store.setState({ counter: 1 });
            assert.strictEqual(store.getState().counter, 1);
            assert.strictEqual(store.getState().label, "init");
        });

        it("returns a fresh object on every committed setState (reference inequality)", () => {
            const store = new Store<TestState>({ ...initial });
            const before = store.getState();
            store.setState({ counter: 1 });
            const after = store.getState();
            assert.notStrictEqual(before, after);
        });
    });

    describe("setState", () => {
        it("notifies subscribers when a field actually changes", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            store.subscribe(() => calls++);
            store.setState({ counter: 1 });
            assert.strictEqual(calls, 1);
        });

        it("does NOT notify when the patch matches the current state (reference-equality)", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            store.subscribe(() => calls++);
            store.setState({ counter: 0 }); // same value
            assert.strictEqual(calls, 0);
        });

        it("does NOT notify when patching a field with undefined", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            store.subscribe(() => calls++);
            store.setState({ counter: undefined });
            assert.strictEqual(calls, 0);
        });

        it("notifies once per setState even if multiple fields changed", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            store.subscribe(() => calls++);
            store.setState({ counter: 1, label: "next" });
            assert.strictEqual(calls, 1);
        });

        it("does not change state when undefined is the only patch field", () => {
            const store = new Store<TestState>({ ...initial });
            const before = store.getState();
            store.setState({ counter: undefined });
            assert.strictEqual(store.getState(), before);
        });

        it("only calls listeners with no arguments — they re-read via getState()", () => {
            const store = new Store<TestState>({ ...initial });
            let captured: TestState | null = null;
            store.subscribe(() => {
                captured = store.getState();
            });
            store.setState({ counter: 42 });
            assert.deepStrictEqual(captured, {
                counter: 42,
                label: "init",
                flag: false,
            });
        });
    });

    describe("subscribe", () => {
        it("returns an unsubscribe function that stops further notifications", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            const unsubscribe = store.subscribe(() => calls++);
            store.setState({ counter: 1 });
            assert.strictEqual(calls, 1);
            unsubscribe();
            store.setState({ counter: 2 });
            assert.strictEqual(calls, 1);
        });

        it("supports multiple listeners", () => {
            const store = new Store<TestState>({ ...initial });
            let a = 0;
            let b = 0;
            store.subscribe(() => a++);
            store.subscribe(() => b++);
            store.setState({ counter: 1 });
            assert.strictEqual(a, 1);
            assert.strictEqual(b, 1);
        });

        it("dedupes the same listener function (Set-backed)", () => {
            const store = new Store<TestState>({ ...initial });
            let calls = 0;
            const listener = (): void => {
                calls++;
            };
            store.subscribe(listener);
            store.subscribe(listener);
            store.setState({ counter: 1 });
            assert.strictEqual(calls, 1);
        });

        it("unsubscribing one listener leaves the others firing", () => {
            const store = new Store<TestState>({ ...initial });
            let a = 0;
            let b = 0;
            const unA = store.subscribe(() => a++);
            store.subscribe(() => b++);
            unA();
            store.setState({ counter: 1 });
            assert.strictEqual(a, 0);
            assert.strictEqual(b, 1);
        });
    });

    describe("immutability discipline", () => {
        it("setState replaces by reference; in-place collection mutation is invisible", () => {
            // Documents the rule callers must follow: replace collections,
            // don't mutate. This test pins the consequence — `.add(x)` on
            // a Set held by the store doesn't trigger notification because
            // setState() never sees a new reference.
            interface SetState {
                ids: Set<string>;
            }
            const store = new Store<SetState>({ ids: new Set(["a"]) });
            let calls = 0;
            store.subscribe(() => calls++);
            const ids = store.getState().ids;
            (ids as Set<string>).add("b"); // anti-pattern
            // No setState call → no notify, even though the Set mutated.
            assert.strictEqual(calls, 0);
            // The right way:
            store.setState({ ids: new Set(["a", "b", "c"]) });
            assert.strictEqual(calls, 1);
        });
    });
});
