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

/** Build a `<div class="preview-card" id="preview-...">` with an
 *  `.image-container` child (where the a11y overlays land) and append
 *  it to `document.body`. Returns the card. */
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

/** Minimal `PreviewInfo` shaped just enough for `buildA11yLegend` to
 *  consume it. The legend reads `id` for the row dataset and
 *  `a11yFindings` for the row content; nothing else is touched. */
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

/** Stand-in for the per-preview a11y caches in `previewStore`. The real
 *  store bumps a `mapsRevision` counter on every write — these helpers
 *  just record into plain Maps so tests can assert what was written. */
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
        assert.strictEqual(card.querySelector(".a11y-legend"), null);
        assert.strictEqual(card.querySelector(".a11y-hierarchy-overlay"), null);
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("is a silent no-op when the previewId has no card in the DOM", () => {
        // Stamp a different card so we can assert it was untouched.
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
        assert.strictEqual(other.querySelector(".a11y-legend"), null);
        assert.strictEqual(cache.findings.size, 0);
        assert.strictEqual(cache.nodes.size, 0);
    });

    it("appends .a11y-overlay placeholder, stamps .a11y-legend, writes cache, and mutates the matching PreviewInfo when findings are present", () => {
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

        const legend = card.querySelector(".a11y-legend");
        assert.ok(legend, ".a11y-legend stamped onto the card");
        // Two findings → one header + two rows.
        assert.strictEqual(
            legend!.querySelectorAll(".a11y-row").length,
            findings.length,
        );

        assert.deepStrictEqual(cache.findings.get("com.example.A"), findings);

        // The matching PreviewInfo has its a11yFindings field replaced
        // (with a fresh array, not the readonly arg directly).
        assert.deepStrictEqual(preview.a11yFindings, [...findings]);
        assert.notStrictEqual(preview.a11yFindings, findings);
    });

    it("re-stamps the legend on a second call rather than duplicating it", () => {
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

        const legends = card.querySelectorAll(".a11y-legend");
        assert.strictEqual(legends.length, 1);
        assert.strictEqual(legends[0].querySelectorAll(".a11y-row").length, 2);
        // The .a11y-overlay placeholder also stays unique (the helper
        // only appends one when none exists).
        assert.strictEqual(card.querySelectorAll(".a11y-overlay").length, 1);
    });

    it("removes overlay + legend and clears cache when findings is an empty array", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });

        // Stamp some findings first so there's something to remove.
        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            undefined,
            config,
        );
        assert.ok(card.querySelector(".a11y-overlay"));
        assert.ok(card.querySelector(".a11y-legend"));
        assert.strictEqual(cache.findings.size, 1);

        applyA11yUpdate("com.example.A", [], undefined, config);

        assert.strictEqual(card.querySelector(".a11y-overlay"), null);
        assert.strictEqual(card.querySelector(".a11y-legend"), null);
        assert.strictEqual(cache.findings.has("com.example.A"), false);
    });

    it("leaves the findings side untouched when findings is undefined", () => {
        const card = buildCard("com.example.A");
        const preview = buildPreviewInfo("com.example.A");
        const { config, cache } = buildConfig({ previews: [preview] });

        // Seed findings.
        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "seed")],
            undefined,
            config,
        );
        assert.ok(card.querySelector(".a11y-legend"));
        const cachedBefore = cache.findings.get("com.example.A");

        // Nodes-only update — findings side must not be touched.
        applyA11yUpdate("com.example.A", undefined, [buildNode("X")], config);

        assert.ok(
            card.querySelector(".a11y-legend"),
            "legend not removed by a nodes-only update",
        );
        assert.ok(
            card.querySelector(".a11y-overlay"),
            "overlay not removed by a nodes-only update",
        );
        assert.strictEqual(cache.findings.get("com.example.A"), cachedBefore);
    });

    it("ensures .a11y-hierarchy-overlay, stamps the hierarchy legend, and writes the nodes cache when nodes are present", () => {
        const card = buildCard("com.example.A");
        const nodes = [buildNode("Button"), buildNode("Text")];
        const { config, cache } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate("com.example.A", undefined, nodes, config);

        const layer = card.querySelector(".a11y-hierarchy-overlay");
        assert.ok(layer, ".a11y-hierarchy-overlay attached");
        assert.strictEqual(layer!.getAttribute("aria-hidden"), "true");

        const legend = card.querySelector(".a11y-hierarchy-legend");
        assert.ok(legend, ".a11y-hierarchy-legend stamped onto the card");
        assert.strictEqual(
            legend!.querySelectorAll(".a11y-hierarchy-row").length,
            nodes.length,
        );

        assert.deepStrictEqual(cache.nodes.get("com.example.A"), nodes);
    });

    it("re-stamps the hierarchy legend on a second nodes update rather than duplicating it", () => {
        const card = buildCard("com.example.A");
        const { config } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate("com.example.A", undefined, [buildNode("A")], config);
        applyA11yUpdate(
            "com.example.A",
            undefined,
            [buildNode("X"), buildNode("Y"), buildNode("Z")],
            config,
        );

        const legends = card.querySelectorAll(".a11y-hierarchy-legend");
        assert.strictEqual(legends.length, 1);
        assert.strictEqual(
            legends[0].querySelectorAll(".a11y-hierarchy-row").length,
            3,
        );
    });

    it("does not stamp the hierarchy legend when a findings legend already exists (findings take precedence)", () => {
        const card = buildCard("com.example.A");
        const { config } = buildConfig({
            previews: [buildPreviewInfo("com.example.A")],
        });

        applyA11yUpdate(
            "com.example.A",
            [buildFinding("ERROR", "x")],
            [buildNode("Button")],
            config,
        );

        assert.ok(
            card.querySelector(".a11y-legend:not(.a11y-hierarchy-legend)"),
        );
        assert.strictEqual(card.querySelector(".a11y-hierarchy-legend"), null);
    });

    it("removes .a11y-hierarchy-overlay, drops the hierarchy legend, and clears the nodes cache when nodes is an empty array", () => {
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
        assert.ok(card.querySelector(".a11y-hierarchy-legend"));
        assert.strictEqual(cache.nodes.size, 1);

        applyA11yUpdate("com.example.A", undefined, [], config);

        assert.strictEqual(card.querySelector(".a11y-hierarchy-overlay"), null);
        assert.strictEqual(card.querySelector(".a11y-hierarchy-legend"), null);
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
        // Sanity: the matching card got its legend regardless.
        assert.ok(cardA.querySelector(".a11y-legend"));
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
