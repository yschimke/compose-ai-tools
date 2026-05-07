// Unit tests for `paintCardCapture` — the per-frame paint that
// `<preview-card>.paintCapture` delegates to (the message dispatcher
// resolves the card by id and invokes the method).
//
// Pattern follows `relativeSizing.test.ts`: build a small
// `<preview-card>`-shaped element with the DOM `populatePreviewCard`
// produces (`.image-container`, `.skeleton`), seed
// `previewStore.cardCaptures`, call `paintCardCapture`, assert on
// the patched DOM and on cache + revision side effects.
//
// The diff-overlay auto-refresh path posts a vscode message; we
// pass a fake `vscode.postMessage` and assert on the captured calls.

import * as assert from "assert";
import { paintCardCapture } from "../webview/preview/cardImage";
import { sanitizeId } from "../webview/preview/cardData";
import type { CapturePresentation } from "../webview/preview/frameCarousel";
import type { DiffOverlayConfig } from "../webview/preview/diffOverlay";
import type { InteractiveInputConfig } from "../webview/preview/interactiveInput";
import { previewStore, setCardCaptures } from "../webview/preview/previewStore";
import type { VsCodeApi } from "../webview/shared/vscode";
import type { WebviewToExtension } from "../types";

interface ConfigStub {
    indicatorCalls: number;
    isLiveValue: boolean;
    earlyFeaturesValue: boolean;
    postedMessages: WebviewToExtension[];
}

function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.id = "preview-" + sanitizeId(previewId);
    card.className = "preview-card";
    card.dataset.previewId = previewId;
    card.dataset.function = "MyPreview";
    card.dataset.currentIndex = "0";

    const imgContainer = document.createElement("div");
    imgContainer.className = "image-container";
    const skeleton = document.createElement("div");
    skeleton.className = "skeleton";
    imgContainer.appendChild(skeleton);
    card.appendChild(imgContainer);

    document.body.appendChild(card);
    return card;
}

function buildConfig(
    initial: { isLive?: boolean; earlyFeatures?: boolean } = {},
) {
    const stub: ConfigStub = {
        indicatorCalls: 0,
        isLiveValue: initial.isLive ?? false,
        earlyFeaturesValue: initial.earlyFeatures ?? false,
        postedMessages: [],
    };
    const vscode: VsCodeApi<unknown> = {
        postMessage: (msg) => {
            stub.postedMessages.push(msg as WebviewToExtension);
        },
        getState: () => null,
        setState: () => {},
    };
    // attachInteractiveInputHandlers reaches `config.isLive(previewId)`
    // immediately and bails when not live, so a test-only stub that
    // matches the (cards are not live in this fixture) world is enough.
    const interactiveInputConfig: InteractiveInputConfig = {
        isLive: (_id: string) => stub.isLiveValue,
        vscode,
    };
    const config = {
        vscode,
        frameCarousel: {
            updateIndicator: (_card: HTMLElement) => {
                stub.indicatorCalls += 1;
            },
        },
        liveState: {
            isLive: (_id: string) => stub.isLiveValue,
        },
        interactiveInputConfig,
        diffOverlayConfig: {} as DiffOverlayConfig,
        earlyFeatures: () => stub.earlyFeaturesValue,
    };
    return { stub, config };
}

function seedCapture(previewId: string, captures: CapturePresentation[]): void {
    setCardCaptures(previewId, captures);
}

function freshCapture(renderOutput: string): CapturePresentation {
    return {
        renderOutput,
        label: "",
        imageData: null,
        errorMessage: null,
        renderError: null,
    };
}

function resetStore(): void {
    previewStore.setState({
        cardCaptures: new Map<string, CapturePresentation[]>(),
        cardA11yFindings: new Map(),
        cardA11yNodes: new Map(),
        mapsRevision: 0,
    });
}

afterEach(() => {
    document.body.innerHTML = "";
    resetStore();
});

describe("paintCardCapture", () => {
    it("swaps img.src, drops the skeleton, and bumps mapsRevision when captureIndex matches the displayed capture", () => {
        const card = buildCard("preview:1");
        seedCapture("preview:1", [freshCapture("a.png")]);
        const startRevision = previewStore.getState().mapsRevision;
        const { stub, config } = buildConfig();

        paintCardCapture(card, "preview:1", 0, "BYTES-AAAA", config);

        const img = card.querySelector<HTMLImageElement>(
            ".image-container img",
        );
        assert.ok(img, "img element should be created when none exists");
        assert.ok(
            img!.src.startsWith("data:image/png;base64,BYTES-AAAA"),
            "img.src should carry the new base64 bytes",
        );
        assert.strictEqual(
            card.querySelector(".skeleton"),
            null,
            "skeleton should be removed once bytes land",
        );
        assert.strictEqual(img!.className, "fade-in");
        assert.strictEqual(stub.indicatorCalls, 1);
        const cap = previewStore.getState().cardCaptures.get("preview:1")![0]!;
        assert.strictEqual(cap.imageData, "BYTES-AAAA");
        assert.ok(
            previewStore.getState().mapsRevision > startRevision,
            "mapsRevision must advance so the component re-paints overlays",
        );
    });

    it("uses 'live-frame' className instead of 'fade-in' when liveState.isLive(previewId)", () => {
        const card = buildCard("preview:1");
        seedCapture("preview:1", [freshCapture("a.png")]);
        const { config } = buildConfig({ isLive: true });

        paintCardCapture(card, "preview:1", 0, "BYTES", config);

        const img = card.querySelector<HTMLImageElement>(
            ".image-container img",
        );
        assert.ok(img);
        assert.strictEqual(img!.className, "live-frame");
    });

    it("caches bytes but skips the img swap when captureIndex differs from dataset.currentIndex", () => {
        const card = buildCard("preview:1");
        card.dataset.currentIndex = "0";
        seedCapture("preview:1", [
            freshCapture("a.png"),
            freshCapture("b.png"),
        ]);
        const { stub, config } = buildConfig();

        paintCardCapture(card, "preview:1", 1, "BYTES-B", config);

        // No <img> element created — skeleton still in place since the
        // displayed capture (index 0) didn't get fresh bytes.
        assert.strictEqual(
            card.querySelector(".image-container img"),
            null,
            "img should not be created when paint is for a non-displayed capture",
        );
        assert.ok(
            card.querySelector(".skeleton"),
            "skeleton should remain since the displayed capture didn't paint",
        );
        // Carousel indicator still refreshed so the strip can highlight
        // which captures have bytes.
        assert.strictEqual(stub.indicatorCalls, 1);
        // Cache for capture[1] populated.
        const caps = previewStore.getState().cardCaptures.get("preview:1")!;
        assert.strictEqual(caps[1]!.imageData, "BYTES-B");
        // Cache for capture[0] untouched.
        assert.strictEqual(caps[0]!.imageData, null);
    });

    it("clears stale .error-message and .has-error when fresh bytes land", () => {
        const card = buildCard("preview:1");
        card.classList.add("has-error");
        const container = card.querySelector(".image-container")!;
        const errorMsg = document.createElement("div");
        errorMsg.className = "error-message";
        errorMsg.textContent = "Render pending";
        container.appendChild(errorMsg);
        seedCapture("preview:1", [freshCapture("a.png")]);
        const { config } = buildConfig();

        paintCardCapture(card, "preview:1", 0, "BYTES", config);

        assert.strictEqual(
            card.querySelector(".error-message"),
            null,
            "error message should be torn down on fresh bytes",
        );
        assert.strictEqual(
            card.classList.contains("has-error"),
            false,
            "has-error class should be cleared",
        );
    });

    it("re-issues a requestPreviewDiff message when an open head/main diff overlay exists", () => {
        const card = buildCard("preview:1");
        const container = card.querySelector(".image-container")!;
        const overlay = document.createElement("div");
        overlay.className = "preview-diff-overlay";
        overlay.dataset.against = "main";
        container.appendChild(overlay);
        seedCapture("preview:1", [freshCapture("a.png")]);

        const { stub, config } = buildConfig({ earlyFeatures: true });

        paintCardCapture(card, "preview:1", 0, "BYTES", config);

        const requestDiffs = stub.postedMessages.filter(
            (
                m,
            ): m is Extract<
                WebviewToExtension,
                { command: "requestPreviewDiff" }
            > => m.command === "requestPreviewDiff",
        );
        assert.strictEqual(requestDiffs.length, 1);
        assert.strictEqual(requestDiffs[0]!.previewId, "preview:1");
        assert.strictEqual(requestDiffs[0]!.against, "main");
    });

    it("does NOT re-issue a diff request when earlyFeatures is off", () => {
        const card = buildCard("preview:1");
        const container = card.querySelector(".image-container")!;
        const overlay = document.createElement("div");
        overlay.className = "preview-diff-overlay";
        overlay.dataset.against = "main";
        container.appendChild(overlay);
        seedCapture("preview:1", [freshCapture("a.png")]);

        const { stub, config } = buildConfig({ earlyFeatures: false });

        paintCardCapture(card, "preview:1", 0, "BYTES", config);

        const requestDiffs = stub.postedMessages.filter(
            (m) => m.command === "requestPreviewDiff",
        );
        assert.strictEqual(requestDiffs.length, 0);
    });
});
