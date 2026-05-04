// Typed handle on the VS Code webview API. `acquireVsCodeApi()` may only
// be called once per webview, so we cache the handle module-locally.

export interface VsCodeApi<TState = unknown> {
    postMessage(msg: unknown): void;
    getState(): TState | undefined;
    setState(state: TState): void;
}

declare global {
    interface Window {
        acquireVsCodeApi?: <T>() => VsCodeApi<T>;
    }
}

let cached: VsCodeApi<unknown> | null = null;

export function getVsCodeApi<T = unknown>(): VsCodeApi<T> {
    if (cached) {
        return cached as VsCodeApi<T>;
    }
    if (typeof window === "undefined" || !window.acquireVsCodeApi) {
        throw new Error("acquireVsCodeApi() not available in this context");
    }
    cached = window.acquireVsCodeApi<T>();
    return cached as VsCodeApi<T>;
}
