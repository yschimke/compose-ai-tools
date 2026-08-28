// happy-dom global registration, imported first by every test file.
//
// Lit needs `window`/`document`/`customElements` at *module evaluation* time —
// `@customElement` calls `customElements.define` as a side effect of importing
// the component — so the DOM has to exist before the component module is
// imported, not merely before the test runs. Each test file therefore does
// `import "./setup.js"` above its component imports, and mocha's file-at-a-time
// ordering does the rest.
//
// Same registrator and version as yschimke/compose-preview-vscode, so the two test suites
// can't drift onto different DOM semantics.

import { GlobalRegistrator } from "@happy-dom/global-registrator";

if (!globalThis.document) {
    GlobalRegistrator.register({ url: "https://preview.example/catalog/" });
}

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

/** Wait for Lit to flush pending reactive updates. */
export async function flush(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
}
