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

// Post-#1087 / legacy-paint removal: `applyA11yUpdate` no longer
// stamps `.a11y-overlay` / `.a11y-hierarchy-overlay` DOM. The A11y
// bundle owns the on-image paint via `cardBundleOverlay` driven from
// `refreshA11yBundle`. This helper just keeps the per-preview caches
// + manifest a11yFindings in sync so downstream surfaces (focus
// inspector, bundle tab) find fresh data.

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

    it("silent no-op when earlyFeatures is off (no cache write)", () => {
        buildCard("com.example.A");
        const { config, cache } = buildConfig({
            earlyFeatures: false,
            previews: [buildPreviewInfo("com.example.A")],
        });
        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            [buildNode("Button")],
            config,
        );
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("silent no-op when the previewId has no card in the DOM", () => {
        buildCard("com.example.OTHER");
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.GHOST")],
        });
        assert.doesNotThrow(() =>
            applyA11yUpdate(
                "com.example.GHOST",
                [buildFinding("ERROR", "x")],
                [buildNode("X")],
                config,
            ),
        );
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("writes the findings cache and mutates the matching PreviewInfo when findings are present", () => {
        buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const findings = [
            buildFinding("ERROR", "contrast"),
            buildFinding("WARNING", "label"),
        ];
        const { config, cache } = buildConfig({ previews: [preview] });
        applyA11yUpdate("com.example.A", findings, undefined, config);
        assert.deepStrictEqual(cache.findings.get("com.example.A"), findings);
        // PreviewInfo gets a defensive copy so subsequent mutations
        // of the input array don't leak into the cached manifest.
        assert.deepStrictEqual(preview.a11yFindings, [...findings]);
        assert.notStrictEqual(preview.a11yFindings, findings);
    });

    it("empty findings clears the cache entry", () => {
        buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });
        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );
        assert.strictEqual(cache.findings.size, 1);
        applyA11yUpdate("com.example.A", [], undefined, config);
        assert.strictEqual(cache.findings.has("com.example.A"), false);
    });

    it("undefined findings leaves the findings cache untouched", () => {
        buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });
        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "seed")],
            undefined,
            config,
        );
        const before = cache.findings.get("com.example.A");
        applyA11yUpdate("com.example.A", undefined, [buildNode("X")], config);
        assert.strictEqual(cache.findings.get("com.example.A"), before);
    });

    it("writes the nodes cache when nodes are present", () => {
        buildCard("com.example.A");
        const nodes = [buildNode("Button"), buildNode("Text")];
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });
        applyA11yUpdate("com.example.A", undefined, nodes, config);
        assert.deepStrictEqual(cache.nodes.get("com.example.A"), nodes);
    });

    it("empty nodes clears the nodes cache entry", () => {
        buildCard("com.example.A");
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });
        applyA11yUpdate(
            "com.example.A",
            undefined,
            [buildNode("Button")],
            config,
        );
        assert.strictEqual(cache.nodes.size, 1);
        applyA11yUpdate("com.example.A", undefined, [], config);
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

    it("does NOT call inspector.render when a different card is focused", () => {
        buildCard("com.example.A");
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
    });

    it("does NOT call inspector.render when not in focus mode", () => {
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
