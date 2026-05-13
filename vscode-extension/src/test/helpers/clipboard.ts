// Test helper — stub `navigator.clipboard.writeText` so the copy-to-
// clipboard paths in inspectionTreeTable / inspectionPresenters can be
// asserted against. happy-dom's `navigator` is a getter on globalThis,
// so the property has to be poked via `Object.defineProperty` on the
// navigator object itself rather than reassigning `globalThis.navigator`.

export interface ClipboardStub {
    /** Most recently captured text, populated when the SUT calls writeText. */
    text: string;
    restore(): void;
}

export function stubClipboard(): ClipboardStub {
    const stub: ClipboardStub = {
        text: "",
        restore: () => {},
    };
    const previousDescriptor = Object.getOwnPropertyDescriptor(
        navigator,
        "clipboard",
    );
    Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        get: () => ({
            writeText: async (t: string) => {
                stub.text = t;
            },
        }),
    });
    stub.restore = () => {
        if (previousDescriptor) {
            Object.defineProperty(navigator, "clipboard", previousDescriptor);
        } else {
            delete (navigator as unknown as { clipboard?: unknown }).clipboard;
        }
    };
    return stub;
}

/** Two-tick microtask flush — `await flushMicrotasks()` lets an `await`-chain
 *  inside the SUT settle before the test inspects the captured state. */
export async function flushMicrotasks(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
}
