// Unit tests for `refreshCardMetadata` — the manifest-reseed path that
// `<preview-card>`'s reactive `updated()` hook calls when the `preview`
// property is reassigned by `renderPreviews`.
//
// Pattern mirrors `relativeSizing.test.ts` / `staleBadgeDom.test.ts`:
// build a minimal `<preview-card>`-shaped element matching what
// `populatePreviewCard` produces (id, dataset, `.card-title`,
// `.image-container`, optional `.variant-badge`), seed
// `previewStore.cardCaptures` with prior bytes, call
// `refreshCardMetadata`, assert on the patched DOM and on the
// merged-cache identity. happy-dom + the existing `setup-dom.ts`
// global registration give us `document` / `HTMLElement` without an
// extra harness.

import * as assert from "assert";
import { refreshCardMetadata } from "../webview/preview/cardMetadata";
import { sanitizeId } from "../webview/preview/cardData";
import type { CapturePresentation } from "../webview/preview/frameCarousel";
import { previewStore, setCardCaptures } from "../webview/preview/previewStore";
import type { Capture, PreviewInfo, PreviewParams } from "../types";

const baseParams: PreviewParams = {
    name: null,
    device: null,
    widthDp: null,
    heightDp: null,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

function capture(renderOutput: string, label = ""): Capture {
    return {
        advanceTimeMillis: null,
        scroll: null,
        renderOutput,
        label,
    };
}

function preview(
    id: string,
    overrides: {
        functionName?: string;
        className?: string;
        params?: Partial<PreviewParams>;
        captures?: Capture[];
        a11yFindings?: PreviewInfo["a11yFindings"];
    } = {},
): PreviewInfo {
    return {
        id,
        functionName: overrides.functionName ?? "MyPreview",
        className: overrides.className ?? "com.example.PreviewsKt",
        sourceFile: null,
        params: { ...baseParams, ...(overrides.params ?? {}) },
        captures: overrides.captures ?? [capture("a.png", "")],
        a11yFindings: overrides.a11yFindings,
    };
}

/** Build a minimum `<preview-card>`-shaped host element matching the
 *  DOM that `populatePreviewCard` produces — the subset
 *  `refreshCardMetadata` reaches for. */
function buildCard(p: PreviewInfo): HTMLElement {
    const card = document.createElement("div");
    card.id = "preview-" + sanitizeId(p.id);
    card.className = "preview-card";
    card.dataset.previewId = p.id;
    card.dataset.function = p.functionName;
    card.dataset.className = p.className;
    card.dataset.currentIndex = "0";

    const header = document.createElement("div");
    header.className = "card-header";
    const titleRow = document.createElement("div");
    titleRow.className = "card-title-row";
    const title = document.createElement("button");
    title.className = "card-title";
    title.textContent = p.functionName;
    titleRow.appendChild(title);
    header.appendChild(titleRow);
    card.appendChild(header);

    const imgContainer = document.createElement("div");
    imgContainer.className = "image-container";
    card.appendChild(imgContainer);

    document.body.appendChild(card);
    return card;
}

interface ConfigStub {
    earlyFeaturesValue: boolean;
    indicatorCalls: number;
}

function buildConfig(initial: { earlyFeatures?: boolean } = {}) {
    const stub: ConfigStub = {
        earlyFeaturesValue: initial.earlyFeatures ?? false,
        indicatorCalls: 0,
    };
    const config = {
        frameCarousel: {
            updateIndicator: (_card: HTMLElement) => {
                stub.indicatorCalls += 1;
            },
        },
        earlyFeatures: () => stub.earlyFeaturesValue,
    };
    return { stub, config };
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

describe("refreshCardMetadata", () => {
    it("patches dataset, title text, and tooltip from the new PreviewInfo", () => {
        const p0 = preview("preview:1");
        const card = buildCard(p0);

        const p1 = preview("preview:1", {
            functionName: "Updated",
            params: { name: "label", group: "g1", widthDp: 200, heightDp: 100 },
        });
        const { config } = buildConfig();
        refreshCardMetadata(card, p1, config);

        assert.strictEqual(card.dataset.function, "Updated");
        assert.strictEqual(card.dataset.group, "g1");
        const title = card.querySelector<HTMLButtonElement>(".card-title");
        assert.ok(title);
        assert.strictEqual(title!.textContent, "Updated — label");
        // Tooltip is non-empty (buildTooltip stamps the function FQN +
        // params); we don't pin its exact shape since that's covered by
        // cardData.test.ts.
        assert.ok((title!.title || "").length > 0);
    });

    it("merges existing capture imageData by index when capture count is unchanged", () => {
        const p0 = preview("preview:1", {
            captures: [
                capture("a.png", "frame-0"),
                capture("b.png", "frame-1"),
            ],
        });
        const card = buildCard(p0);
        // Seed the store as if `updateImage` had landed bytes for both
        // captures already.
        setCardCaptures("preview:1", [
            {
                renderOutput: "a.png",
                label: "frame-0",
                imageData: "BYTES-0",
                errorMessage: null,
                renderError: null,
            },
            {
                renderOutput: "b.png",
                label: "frame-1",
                imageData: "BYTES-1",
                errorMessage: null,
                renderError: null,
            },
        ]);

        const p1 = preview("preview:1", {
            captures: [
                capture("a.png", "frame-0-renamed"),
                capture("b.png", "frame-1-renamed"),
            ],
        });
        const { config } = buildConfig();
        refreshCardMetadata(card, p1, config);

        const merged = previewStore.getState().cardCaptures.get("preview:1");
        assert.ok(merged);
        assert.strictEqual(merged!.length, 2);
        // Labels reseed from the new manifest.
        assert.strictEqual(merged![0]!.label, "frame-0-renamed");
        assert.strictEqual(merged![1]!.label, "frame-1-renamed");
        // imageData survives the reseed by index.
        assert.strictEqual(merged![0]!.imageData, "BYTES-0");
        assert.strictEqual(merged![1]!.imageData, "BYTES-1");
    });

    it("clamps dataset.currentIndex when the new capture count is shorter", () => {
        const p0 = preview("preview:1", {
            captures: [capture("a.png"), capture("b.png"), capture("c.png")],
        });
        const card = buildCard(p0);
        card.dataset.currentIndex = "2";
        setCardCaptures("preview:1", [
            {
                renderOutput: "a.png",
                label: "",
                imageData: null,
                errorMessage: null,
                renderError: null,
            },
            {
                renderOutput: "b.png",
                label: "",
                imageData: null,
                errorMessage: null,
                renderError: null,
            },
            {
                renderOutput: "c.png",
                label: "",
                imageData: null,
                errorMessage: null,
                renderError: null,
            },
        ]);

        const p1 = preview("preview:1", { captures: [capture("a.png")] });
        const { config } = buildConfig();
        refreshCardMetadata(card, p1, config);

        assert.strictEqual(card.dataset.currentIndex, "0");
    });

    it("appends a .variant-badge when the new params produce a label", () => {
        const p0 = preview("preview:1");
        const card = buildCard(p0);
        assert.strictEqual(card.querySelector(".variant-badge"), null);

        const p1 = preview("preview:1", {
            params: { fontScale: 1.5 },
        });
        const { config } = buildConfig();
        refreshCardMetadata(card, p1, config);

        const badge = card.querySelector(".variant-badge");
        assert.ok(badge);
        assert.ok((badge!.textContent || "").length > 0);
    });

    it("removes an existing .variant-badge when the new params produce no label", () => {
        const p0 = preview("preview:1", { params: { fontScale: 1.5 } });
        const card = buildCard(p0);
        const stale = document.createElement("div");
        stale.className = "variant-badge";
        stale.textContent = "fontScale=1.5";
        card.appendChild(stale);

        const p1 = preview("preview:1");
        const { config } = buildConfig();
        refreshCardMetadata(card, p1, config);

        assert.strictEqual(card.querySelector(".variant-badge"), null);
    });

    it("calls frameCarousel.updateIndicator for animated previews only", () => {
        // Animated requires more than one capture.
        const pStatic = preview("preview:S");
        const pAnimated = preview("preview:A", {
            captures: [capture("a.png"), capture("b.png")],
        });
        const cardS = buildCard(pStatic);
        const cardA = buildCard(pAnimated);

        const { stub, config } = buildConfig();

        refreshCardMetadata(cardS, pStatic, config);
        assert.strictEqual(stub.indicatorCalls, 0);

        refreshCardMetadata(cardA, pAnimated, config);
        assert.strictEqual(stub.indicatorCalls, 1);
    });

    it("rebuilds .a11y-overlay only when earlyFeatures is on and findings exist", () => {
        // Post-#1054 the labelled legend list moved to the A11y bundle
        // tab; only the boxes-on-image overlay layer is rebuilt on the
        // card itself. The findings array still drives whether the
        // overlay container exists, just without the inline legend.
        const findings: PreviewInfo["a11yFindings"] = [
            {
                level: "WARNING",
                type: "TextContrastCheck",
                message: "Low contrast on label.",
                boundsInScreen: "Rect(10, 20 - 110, 60)",
            },
        ];

        const p0 = preview("preview:1");
        const card = buildCard(p0);

        const p1 = preview("preview:1", { a11yFindings: findings });

        // earlyFeatures off — no overlay even with findings.
        const offConfig = buildConfig({ earlyFeatures: false }).config;
        refreshCardMetadata(card, p1, offConfig);
        assert.strictEqual(card.querySelector(".a11y-overlay"), null);

        // earlyFeatures on — empty overlay container appended inside
        // .image-container. No labelled legend on the card.
        const onConfig = buildConfig({ earlyFeatures: true }).config;
        refreshCardMetadata(card, p1, onConfig);
        assert.ok(
            card.querySelector(".image-container .a11y-overlay"),
            "overlay container should be appended into .image-container",
        );
        assert.strictEqual(
            card.querySelector(".a11y-legend"),
            null,
            "labelled legend lives in the A11y bundle tab now (#1054)",
        );

        // Subsequent reseed with empty findings drops the overlay.
        const p2 = preview("preview:1", { a11yFindings: [] });
        refreshCardMetadata(card, p2, onConfig);
        assert.strictEqual(card.querySelector(".a11y-overlay"), null);
    });
});
