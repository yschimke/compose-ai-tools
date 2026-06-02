// Auto-light contract for the Errors bundle chip — verifies that
// `setImageError` / `setError` calls `ctx.promoteErrorsBundle()` so
// the panel surfaces the chip as soon as a render fails, without
// waiting for the daemon's `test/failure` payload to arrive (the old
// `updateDataProducts`-driven path only fired when the chip was
// already on — see the comment removed from main.ts).

import * as assert from "assert";
import {
    handleExtensionMessage,
    type PreviewMessageContext,
} from "../webview/preview/messageHandlers";
import { sanitizeId } from "../webview/preview/cardData";
import type { ExtensionToWebview } from "../types";

function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.id = "preview-" + sanitizeId(previewId);
    card.dataset.currentIndex = "0";
    const container = document.createElement("div");
    container.className = "image-container";
    card.appendChild(container);
    return card;
}

interface ContextWithSpy {
    ctx: PreviewMessageContext;
    promoteCalls(): number;
}

function buildContextStub(): ContextWithSpy {
    const calls = { promote: 0 };
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
        promoteErrorsBundle: () => {
            calls.promote += 1;
        },
        toggleBundle: noop,
        setSpatialScene: noop,
    };
    return { ctx, promoteCalls: () => calls.promote };
}

describe("handleErrorMessage promoteErrorsBundle", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("calls promoteErrorsBundle on setImageError", () => {
        const card = buildCard("preview-A");
        document.body.appendChild(card);
        const { ctx, promoteCalls } = buildContextStub();
        const msg: ExtensionToWebview = {
            command: "setImageError",
            previewId: "preview-A",
            captureIndex: 0,
            message: "Render failed",
        };
        handleExtensionMessage(msg, ctx);
        assert.strictEqual(promoteCalls(), 1);
        assert.ok(
            card.classList.contains("has-error"),
            "card should be flagged with has-error",
        );
        assert.strictEqual(card.dataset.renderError, "Render failed");
    });

    it("calls promoteErrorsBundle on setError (preview-wide)", () => {
        const card = buildCard("preview-B");
        document.body.appendChild(card);
        const { ctx, promoteCalls } = buildContextStub();
        const msg: ExtensionToWebview = {
            command: "setError",
            previewId: "preview-B",
            message: "Daemon crashed",
        };
        handleExtensionMessage(msg, ctx);
        assert.strictEqual(promoteCalls(), 1);
    });

    it("does not call promoteErrorsBundle when the previewId has no card", () => {
        // No DOM card present — the handler short-circuits before
        // building the error panel, so the promote callback also
        // doesn't fire. (No card = no preview to flag, and we'd
        // rather not light the bundle for a phantom failure.)
        const { ctx, promoteCalls } = buildContextStub();
        const msg: ExtensionToWebview = {
            command: "setImageError",
            previewId: "preview-missing",
            captureIndex: 0,
            message: "Render failed",
        };
        handleExtensionMessage(msg, ctx);
        assert.strictEqual(promoteCalls(), 0);
    });
});
