// Shared test helpers, imported first by every test file.
//
// `test/preload.ts` installs happy-dom and the shared Vue runtime before Mocha links any test.
// Custom-element registration then has `window`/`document`/`customElements` at module evaluation
// time, while this module remains the common reset/flush/storage utility.
//
// Same registrator and version as yschimke/compose-preview-vscode, so the two test suites
// can't drift onto different DOM semantics.

/** Reset the document and the `<html>` classes between tests. */
export function resetDom(): void {
    document.documentElement.className = "";
    document.body.innerHTML = "";
}

/** A minimal in-memory `localStorage` stand-in with a throwing variant. */
export function stubStorage(throwOnWrite = false): {
    get(key: string): string | null;
} {
    const store = new Map<string, string>();
    Object.defineProperty(globalThis, "localStorage", {
        configurable: true,
        value: {
            getItem: (key: string) => store.get(key) ?? null,
            setItem: (key: string, value: string) => {
                if (throwOnWrite) throw new Error("storage blocked");
                store.set(key, value);
            },
            removeItem: (key: string) => void store.delete(key),
        },
    });
    return { get: (key) => store.get(key) ?? null };
}

/** Wait for Vue updates and queued browser work to flush. */
export async function flush(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
}
