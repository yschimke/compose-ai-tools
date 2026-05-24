// Dispatcher routing for the `triggerBundleToggle` message — proves
// the extension-side test trigger
// (`ComposePreviewTestApi.triggerWebviewBundleToggle`) reaches the
// webview's `BundleController.toggleBundle` via
// `handleExtensionMessage`. Without this contract the e2e bundle-chain
// suite would silently pass when the trigger drops on the floor.

import * as assert from "assert";
import {
    handleExtensionMessage,
    type PreviewMessageContext,
} from "../webview/preview/messageHandlers";
import type { ExtensionToWebview } from "../types";

interface ContextWithSpy {
    ctx: PreviewMessageContext;
    toggleCalls(): string[];
}

function buildContextStub(): ContextWithSpy {
    const calls: string[] = [];
    const noop = () => {};
    const ctx: PreviewMessageContext = {
        vscode: {
            postMessage: noop,
            getState: () => undefined,
            setState: noop,
        } as PreviewMessageContext["vscode"],
        grid: {} as PreviewMessageContext["grid"],
        filterToolbar: {
            setFunctionOptions: noop,
            setGroupOptions: noop,
            setFunctionValue: noop,
        } as PreviewMessageContext["filterToolbar"],
        liveState: {} as PreviewMessageContext["liveState"],
        staleBadge: {} as PreviewMessageContext["staleBadge"],
        loadingOverlay: {} as PreviewMessageContext["loadingOverlay"],
        diffOverlayConfig: {} as PreviewMessageContext["diffOverlayConfig"],
        streamingPainter: {} as PreviewMessageContext["streamingPainter"],
        earlyFeatures: () => true,
        getA11yOverlayId: () => null,
        setA11yOverlayId: noop,
        setAllPreviews: noop,
        setModuleDir: noop,
        setLastScopedPreviewId: noop,
        renderPreviews: noop,
        applyRelativeSizing: noop,
        applyFilters: noop,
        applyLayout: noop,
        applyPendingFocusRestore: noop,
        applyInteractiveButtonState: noop,
        applyRecordingButtonState: noop,
        saveFilterState: noop,
        restoreFilterState: noop,
        ensureNotBlank: noop,
        applyA11yUpdate: noop,
        updateDataProducts: noop,
        applyFontPreviewBytes: noop,
        focusOnCard: noop,
        deactivateAllBundles: noop,
        refreshBundleState: noop,
        promoteErrorsBundle: noop,
        toggleBundle: (bundleId: string) => {
            calls.push(bundleId);
        },
    };
    return { ctx, toggleCalls: () => calls };
}

describe("handleExtensionMessage triggerBundleToggle", () => {
    it("routes triggerBundleToggle to ctx.toggleBundle with the bundleId", () => {
        const { ctx, toggleCalls } = buildContextStub();
        const msg: ExtensionToWebview = {
            command: "triggerBundleToggle",
            bundleId: "a11y",
        };
        handleExtensionMessage(msg, ctx);
        assert.deepStrictEqual(toggleCalls(), ["a11y"]);
    });

    it("forwards the literal bundleId — no normalisation or validation in the dispatch layer", () => {
        // Validation belongs in `BundleController.toggleBundle` (it
        // short-circuits unknown ids via `getBundle`). Keeping the
        // dispatcher transparent matches the rest of the message
        // handlers and lets future bundle ids land without
        // touching this file.
        const { ctx, toggleCalls } = buildContextStub();
        handleExtensionMessage(
            { command: "triggerBundleToggle", bundleId: "history" },
            ctx,
        );
        handleExtensionMessage(
            { command: "triggerBundleToggle", bundleId: "not-a-bundle" },
            ctx,
        );
        assert.deepStrictEqual(toggleCalls(), ["history", "not-a-bundle"]);
    });
});
