// Dispatch contract for the host-driven "leave focus mode" message.
//
// The host posts `{ command: "leaveFocusMode" }` when the user switches
// the active editor to a different preview source file (see the
// `onDidChangeActiveTextEditor` handler in `extension.ts`). The webview
// drops the focus layout for the grid browser so the user isn't stranded
// in a focus stage for a file they're no longer editing. This test locks
// in the dispatcher → `ctx.leaveFocusMode()` wiring.

import * as assert from "assert";
import {
    handleExtensionMessage,
    type PreviewMessageContext,
} from "../webview/preview/messageHandlers";
import type { ExtensionToWebview } from "../types";

interface ContextWithSpy {
    ctx: PreviewMessageContext;
    leaveCalls(): number;
}

function buildContextStub(): ContextWithSpy {
    const calls = { leave: 0 };
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
        leaveFocusMode: () => {
            calls.leave += 1;
        },
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
        toggleBundle: noop,
        setSpatialScene: noop,
    };
    return { ctx, leaveCalls: () => calls.leave };
}

describe("handleExtensionMessage leaveFocusMode", () => {
    it("routes leaveFocusMode to ctx.leaveFocusMode", () => {
        const { ctx, leaveCalls } = buildContextStub();
        const msg: ExtensionToWebview = { command: "leaveFocusMode" };
        handleExtensionMessage(msg, ctx);
        assert.strictEqual(leaveCalls(), 1);
    });
});
