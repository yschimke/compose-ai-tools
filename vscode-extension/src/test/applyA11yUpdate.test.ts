import * as assert from "assert";
import {
    applyA11yUpdate,
    type A11yUpdateConfig,
} from "../webview/preview/applyA11yUpdate";
import { sanitizeId } from "../webview/preview/cardData";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../webview/shared/types";

// Post-#1054 the labelled legend list is no longer painted inline on
// the card — it now lives in the A11y bundle tab. These tests pin the
// surviving on-card surfaces (the boxes-on-image overlay layer) and
// the cache writes that feed both the bundle presenter and the
// existing focus inspector.

function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.id = "preview-" + sanitizeId(previewId);
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

function buildPreviewInfo(id: string): PreviewInfo {
    return {
        id,
        functionName: "Preview",
        className: "com.example.PreviewKt",
        sourceFile: null,
        params: { name: "Preview" },
        captures: [],
    } as unknown as PreviewInfo;
}

function buildFinding(
    level: "ERROR" | "WARNING" | "INFO",
    message: string,
): AccessibilityFinding {
    return {
        level,
        type: "ContrastCheck",
        message,
        viewDescription: null,
        boundsInScreen: "0,0,100,100",
    };
}

function buildNode(label: string): AccessibilityNode {
    return {
        label,
        role: "Button",
        states: ["focusable"],
        merged: true,
        boundsInScreen: "0,0,100,100",
    };
}

interface CacheState {
    findings: Map<string, readonly AccessibilityFinding[]>;
    nodes: Map<string, readonly AccessibilityNode[]>;
}

interface ConfigOverrides {
    earlyFeatures?: boolean;
    inFocus?: boolean;
    focusedCard?: HTMLElement | null;
    previews?: PreviewInfo[];
    inspectorRender?: (card: HTMLElement) => void;
    cache?: CacheState;
}

function buildConfig(overrides: ConfigOverrides = {}): {
    config: A11yUpdateConfig;
    cache: CacheState;
    inspectorCalls: HTMLElement[];
} {
    const cache: CacheState = overrides.cache ?? {
        findings: new Map(),
        nodes: new Map(),
    };
    const inspectorCalls: HTMLElement[] = [];
    const config: A11yUpdateConfig = {
        getAllPreviews: () => overrides.previews ?? [],
        inspector: {
            render: (card) => {
                inspectorCalls.push(card);
                overrides.inspectorRender?.(card);
            },
        },
        earlyFeatures: () => overrides.earlyFeatures ?? true,
        inFocus: () => overrides.inFocus ?? false,
        focusedCard: () => overrides.focusedCard ?? null,
        setCardA11yFindings: (id, findings) => {
            cache.findings.set(id, findings);
        },
        deleteCardA11yFindings: (id) => {
            cache.findings.delete(id);
        },
        setCardA11yNodes: (id, nodes) => {
            cache.nodes.set(id, nodes);
        },
        deleteCardA11yNodes: (id) => {
            cache.nodes.delete(id);
        },
    };
    return { config, cache, inspectorCalls };
}

describe("applyA11yUpdate", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("is a silent no-op when earlyFeatures() is false (no DOM mutation, no cache write)", () => {
        const card = buildCard("com.example.A");
        const { config, cache } = buildConfig({
            earlyFeatures: false,
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "low contrast")],
            [buildNode("Button")],
            config,
        );

        assert.strictEqual(card.querySelector(".a11y-overlay"), null);
        assert.strictEqual(card.querySelector(".a11y-hierarchy-overlay"), null);
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("is a silent no-op when the previewId has no card in the DOM", () => {
        const other = buildCard("com.example.OTHER");
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.GHOST")],
        });

        assert.doesNotThrow(() =>
            applyA11yUpdate(
                "com.example.GHOST",
                [buildFinding("ERROR", "missing card")],
                [buildNode("Ghost")],
                config,
            ),
        );

        assert.strictEqual(other.querySelector(".a11y-overlay"), null);
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("appends .a11y-overlay placeholder, writes cache, and mutates the matching PreviewInfo when findings are present", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const findings = [
            buildFinding("ERROR", "contrast"),
            buildFinding("WARNING", "label"),
        ];
        const { config, cache } = buildConfig({
            previews: [preview],
        });

        applyA11yUpdate("com.example.A", findings, undefined, config);

        const overlay = card.querySelector(".a11y-overlay");
        assert.ok(overlay, ".a11y-overlay placeholder appended");
        assert.strictEqual(overlay!.getAttribute("aria-hidden"), "true");
        // The placeholder is empty — actual paint runs from the
        // PreviewCard's mapsRevision subscription, not from this helper.
        assert.strictEqual(overlay!.children.length, 0);
        // Labelled legend list is no longer rendered on the card; it
        // lives in the A11y bundle tab now.
        assert.strictEqual(card.querySelector(".a11y-legend"), null);

        assert.deepStrictEqual(cache.findings.get("com.example.A"), findings);
        // The matching PreviewInfo has its a11yFindings field replaced
        // (with a fresh array, not the readonly arg directly).
        assert.deepStrictEqual(preview.a11yFindings, [...findings]);
        assert.notStrictEqual(preview.a11yFindings, findings);
    });

    it("does not duplicate the overlay placeholder on a second findings update", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config } = buildConfig({ previews: [preview] });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "first")],
            undefined,
            config,
        );
        applyA11yUpdate(
            "com.example.A",
            [
                buildFinding("ERROR", "second-a"),
                buildFinding("INFO", "second-b"),
            ],
            undefined,
            config,
        );

        assert.strictEqual(card.querySelectorAll(".a11y-overlay").length, 1);
    });

    it("removes overlay and clears cache when findings is an empty array", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );
        assert.ok(card.querySelector(".a11y-overlay"));
        assert.strictEqual(cache.findings.size, 1);

        applyA11yUpdate("com.example.A", [], undefined, config);

        assert.strictEqual(card.querySelector(".a11y-overlay"), null);
        assert.strictEqual(cache.findings.has("com.example.A"), false);
    });

    it("leaves the findings side untouched when findings is undefined", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "seed")],
            undefined,
            config,
        );
        assert.ok(card.querySelector(".a11y-overlay"));
        const cachedBefore = cache.findings.get("com.example.A");

        // Nodes-only update — findings side must not be touched.
        applyA11yUpdate("com.example.A", undefined, [buildNode("X")], config);

        assert.ok(
            card.querySelector(".a11y-overlay"),
            "overlay not removed by a nodes-only update",
        );
        assert.strictEqual(cache.findings.get("com.example.A"), cachedBefore);
    });

    it("ensures .a11y-hierarchy-overlay layer and writes the nodes cache when nodes are present", () => {
        const card = buildCard("com.example.A");
        const nodes = [buildNode("Button"), buildNode("Text")];
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate("com.example.A", undefined, nodes, config);

        const layer = card.querySelector(".a11y-hierarchy-overlay");
        assert.ok(layer, ".a11y-hierarchy-overlay attached");
        assert.strictEqual(layer!.getAttribute("aria-hidden"), "true");
        // Labelled hierarchy legend list lives in the A11y bundle tab,
        // not on the card.
        assert.strictEqual(card.querySelector(".a11y-hierarchy-legend"), null);

        assert.deepStrictEqual(cache.nodes.get("com.example.A"), nodes);
    });

    it("removes .a11y-hierarchy-overlay and clears the nodes cache when nodes is an empty array", () => {
        const card = buildCard("com.example.A");
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate(
            "com.example.A",
            undefined,
            [buildNode("Button")],
            config,
        );
        assert.ok(card.querySelector(".a11y-hierarchy-overlay"));
        assert.strictEqual(cache.nodes.size, 1);

        applyA11yUpdate("com.example.A", undefined, [], config);

        assert.strictEqual(card.querySelector(".a11y-hierarchy-overlay"), null);
        assert.strictEqual(cache.nodes.has("com.example.A"), false);
    });

    it("calls inspector.render(card) when in focus mode and the focused card matches", () => {
        const card = buildCard("com.example.A");
        const { config, inspectorCalls } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
            inFocus: true,
            focusedCard: card,
        });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );

        assert.strictEqual(inspectorCalls.length, 1);
        assert.strictEqual(inspectorCalls[0], card);
    });

    it("does NOT call inspector.render when in focus mode but a different card is focused", () => {
        const cardA = buildCard("com.example.A");
        const cardB = buildCard("com.example.B");
        const { config, inspectorCalls } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
            inFocus: true,
            focusedCard: cardB,
        });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );

        assert.strictEqual(inspectorCalls.length, 0);
        // Sanity: the matching card got its overlay placeholder regardless.
        assert.ok(cardA.querySelector(".a11y-overlay"));
    });

    it("does NOT call inspector.render when not in focus mode even if focusedCard returns the matching card", () => {
        const card = buildCard("com.example.A");
        const { config, inspectorCalls } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
            inFocus: false,
            focusedCard: card,
        });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );

        assert.strictEqual(inspectorCalls.length, 0);
    });
});
