import * as assert from "assert";
import {
    buildA11yLegend,
    buildA11yOverlay,
    ensureHierarchyOverlay,
    applyHierarchyOverlay,
} from "../webview/preview/a11yOverlay";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../types";

function previewInfo(
    over: Partial<PreviewInfo> & {
        a11yFindings?: AccessibilityFinding[] | null;
    } = {},
): PreviewInfo {
    return {
        id: "com.example.A#preview",
        functionName: "preview",
        className: "com.example.A",
        sourceFile: null,
        params: {} as PreviewInfo["params"],
        captures: [],
        ...over,
    } as PreviewInfo;
}

function finding(
    over: Partial<AccessibilityFinding> = {},
): AccessibilityFinding {
    return {
        level: "ERROR",
        type: "TouchTargetSize",
        message: "Target is too small",
        boundsInScreen: "10,20,110,220",
        ...over,
    };
}

function node(over: Partial<AccessibilityNode> = {}): AccessibilityNode {
    return {
        label: "Submit",
        role: "Button",
        states: ["enabled"],
        merged: true,
        boundsInScreen: "0,0,100,100",
        ...over,
    };
}

/** Build a `.preview-card` with a child `.image-container` holding an
 *  `<img>` whose natural dimensions are forced. happy-dom defaults
 *  naturalWidth/Height to 0 since no real image loads — we override
 *  per-instance so the percent-coordinate math has something to bite. */
function buildCardWithImage(
    naturalWidth: number,
    naturalHeight: number,
): { card: HTMLElement; container: HTMLElement; img: HTMLImageElement } {
    const card = document.createElement("div");
    card.className = "preview-card";
    const container = document.createElement("div");
    container.className = "image-container";
    const img = document.createElement("img");
    Object.defineProperty(img, "naturalWidth", {
        value: naturalWidth,
        configurable: true,
    });
    Object.defineProperty(img, "naturalHeight", {
        value: naturalHeight,
        configurable: true,
    });
    container.appendChild(img);
    card.appendChild(container);
    document.body.appendChild(card);
    return { card, container, img };
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("buildA11yLegend", () => {
    it("returns a detached .a11y-legend with a header counting findings", () => {
        const card = document.createElement("div");
        const legend = buildA11yLegend(
            card,
            previewInfo({
                a11yFindings: [finding(), finding({ level: "WARNING" })],
            }),
        );
        assert.strictEqual(legend.parentNode, null, "should be detached");
        assert.strictEqual(legend.className, "a11y-legend");
        const header = legend.querySelector(".a11y-legend-header")!;
        assert.strictEqual(header.textContent, "Accessibility (2)");
    });

    it("renders one .a11y-row per finding with level class, previewId/findingIdx datasets, and 1-based badge", () => {
        const card = document.createElement("div");
        const legend = buildA11yLegend(
            card,
            previewInfo({
                id: "com.example.A#preview",
                a11yFindings: [
                    finding({ level: "ERROR" }),
                    finding({ level: "WARNING" }),
                    finding({ level: "INFO" }),
                ],
            }),
        );
        const rows = legend.querySelectorAll<HTMLElement>(".a11y-row");
        assert.strictEqual(rows.length, 3);
        const levelClasses = Array.from(rows).map((r) =>
            Array.from(r.classList).find((c) => c.startsWith("a11y-level-")),
        );
        assert.deepStrictEqual(levelClasses, [
            "a11y-level-error",
            "a11y-level-warning",
            "a11y-level-info",
        ]);
        rows.forEach((r, idx) => {
            assert.strictEqual(r.dataset.previewId, "com.example.A#preview");
            assert.strictEqual(r.dataset.findingIdx, String(idx));
            const badge = r.querySelector(".a11y-badge")!;
            assert.strictEqual(badge.textContent, String(idx + 1));
        });
    });

    it("falls back to 'info' level class when finding.level is empty", () => {
        const card = document.createElement("div");
        const legend = buildA11yLegend(
            card,
            previewInfo({ a11yFindings: [finding({ level: "" })] }),
        );
        const row = legend.querySelector(".a11y-row")!;
        assert.ok(row.classList.contains("a11y-level-info"));
    });

    it("renders title (level · type), message, and optional viewDescription", () => {
        const card = document.createElement("div");
        const legend = buildA11yLegend(
            card,
            previewInfo({
                a11yFindings: [
                    finding({
                        level: "ERROR",
                        type: "TouchTargetSize",
                        message: "Target is too small",
                        viewDescription: "Button#submit",
                    }),
                    finding({ viewDescription: null }),
                ],
            }),
        );
        const rows = legend.querySelectorAll(".a11y-row");
        const first = rows[0];
        assert.strictEqual(
            first.querySelector(".a11y-title")!.textContent,
            "ERROR · TouchTargetSize",
        );
        assert.strictEqual(
            first.querySelector(".a11y-msg")!.textContent,
            "Target is too small",
        );
        assert.strictEqual(
            first.querySelector(".a11y-elt")!.textContent,
            "Button#submit",
        );
        const second = rows[1];
        assert.strictEqual(
            second.querySelector(".a11y-elt"),
            null,
            "viewDescription absent → no .a11y-elt child",
        );
    });

    it("hover toggles .a11y-active on matching [data-finding-idx] elements inside the host card", () => {
        // Build a card containing the legend itself plus two overlay boxes,
        // so the hover handler's card-scoped `querySelectorAll` has both
        // .a11y-row and .a11y-box matches to flip.
        const card = document.createElement("div");
        document.body.appendChild(card);
        const findings = [finding(), finding({ level: "WARNING" })];
        const legend = buildA11yLegend(
            card,
            previewInfo({ a11yFindings: findings }),
        );
        card.appendChild(legend);

        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        findings.forEach((_, idx) => {
            const box = document.createElement("div");
            box.className = "a11y-box";
            box.dataset.findingIdx = String(idx);
            overlay.appendChild(box);
        });
        card.appendChild(overlay);

        const rows = legend.querySelectorAll<HTMLElement>(".a11y-row");
        const boxes = card.querySelectorAll<HTMLElement>(".a11y-box");

        rows[0].dispatchEvent(new Event("mouseenter"));
        assert.ok(rows[0].classList.contains("a11y-active"));
        assert.ok(boxes[0].classList.contains("a11y-active"));
        assert.ok(!rows[1].classList.contains("a11y-active"));
        assert.ok(!boxes[1].classList.contains("a11y-active"));

        // Hovering a different row clears the previous active set first.
        rows[1].dispatchEvent(new Event("mouseenter"));
        assert.ok(!rows[0].classList.contains("a11y-active"));
        assert.ok(!boxes[0].classList.contains("a11y-active"));
        assert.ok(rows[1].classList.contains("a11y-active"));
        assert.ok(boxes[1].classList.contains("a11y-active"));

        rows[1].dispatchEvent(new Event("mouseleave"));
        assert.ok(!rows[1].classList.contains("a11y-active"));
        assert.ok(!boxes[1].classList.contains("a11y-active"));
    });

    it("treats null/undefined a11yFindings as an empty list (header only, no rows)", () => {
        const card = document.createElement("div");
        const legend = buildA11yLegend(
            card,
            previewInfo({ a11yFindings: null }),
        );
        assert.strictEqual(
            legend.querySelector(".a11y-legend-header")!.textContent,
            "Accessibility (0)",
        );
        assert.strictEqual(legend.querySelectorAll(".a11y-row").length, 0);
    });
});

describe("buildA11yOverlay", () => {
    it("paints one .a11y-box per finding into the existing .a11y-overlay layer", () => {
        const { card, container, img } = buildCardWithImage(200, 400);
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        container.appendChild(overlay);

        buildA11yOverlay(
            card,
            [finding({ boundsInScreen: "10,20,110,220" })],
            img,
        );
        const box = overlay.querySelector<HTMLElement>(".a11y-box")!;
        assert.ok(box);
        // 10/200 = 5%, 20/400 = 5%, (110-10)/200 = 50%, (220-20)/400 = 50%.
        assert.strictEqual(box.style.left, "5%");
        assert.strictEqual(box.style.top, "5%");
        assert.strictEqual(box.style.width, "50%");
        assert.strictEqual(box.style.height, "50%");
    });

    it("attaches level class, 1-based badge text, and findingIdx dataset", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        container.appendChild(overlay);

        buildA11yOverlay(
            card,
            [
                finding({ level: "ERROR" }),
                finding({ level: "WARNING" }),
                finding({ level: "" }),
            ],
            img,
        );
        const boxes = overlay.querySelectorAll<HTMLElement>(".a11y-box");
        assert.strictEqual(boxes.length, 3);
        assert.ok(boxes[0].classList.contains("a11y-level-error"));
        assert.ok(boxes[1].classList.contains("a11y-level-warning"));
        assert.ok(
            boxes[2].classList.contains("a11y-level-info"),
            "blank level falls back to info",
        );
        boxes.forEach((b, idx) => {
            assert.strictEqual(b.dataset.findingIdx, String(idx));
            assert.strictEqual(
                b.querySelector(".a11y-badge")!.textContent,
                String(idx + 1),
            );
        });
    });

    it("clears any previous boxes on repaint (idempotent re-call)", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        container.appendChild(overlay);

        buildA11yOverlay(card, [finding(), finding()], img);
        assert.strictEqual(overlay.querySelectorAll(".a11y-box").length, 2);
        buildA11yOverlay(card, [finding()], img);
        assert.strictEqual(
            overlay.querySelectorAll(".a11y-box").length,
            1,
            "second call should replace, not append",
        );
    });

    it("does nothing when the card has no .a11y-overlay layer", () => {
        const { card, img } = buildCardWithImage(100, 100);
        assert.doesNotThrow(() => buildA11yOverlay(card, [finding()], img));
        assert.strictEqual(card.querySelector(".a11y-box"), null);
    });

    it("bails (and leaves the overlay empty) when the image has no natural dimensions", () => {
        const { card, container, img } = buildCardWithImage(0, 0);
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        container.appendChild(overlay);
        // Pre-populate so we can also confirm the innerHTML wipe still
        // happens before the natural-dim guard.
        overlay.innerHTML = "<div class='a11y-box stale'></div>";

        buildA11yOverlay(card, [finding()], img);
        assert.strictEqual(
            overlay.querySelectorAll(".a11y-box").length,
            0,
            "stale boxes are cleared",
        );
    });

    it("skips findings with malformed boundsInScreen rather than dropping zero-area boxes", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        const overlay = document.createElement("div");
        overlay.className = "a11y-overlay";
        container.appendChild(overlay);

        buildA11yOverlay(
            card,
            [
                finding({ boundsInScreen: "0,0,50,50" }),
                finding({ boundsInScreen: "garbage" }),
                finding({ boundsInScreen: null }),
                finding({ boundsInScreen: "10,20,30" }),
                finding({ boundsInScreen: "1,2,3,4" }),
            ],
            img,
        );
        const boxes = overlay.querySelectorAll<HTMLElement>(".a11y-box");
        assert.strictEqual(boxes.length, 2);
        // Surviving boxes keep their original 0-based index from the input
        // list, even though intermediate findings were skipped.
        assert.strictEqual(boxes[0].dataset.findingIdx, "0");
        assert.strictEqual(boxes[1].dataset.findingIdx, "4");
    });
});

describe("ensureHierarchyOverlay", () => {
    it("appends an empty .a11y-hierarchy-overlay (aria-hidden) into the container", () => {
        const container = document.createElement("div");
        document.body.appendChild(container);
        ensureHierarchyOverlay(container);
        const layer = container.querySelector<HTMLElement>(
            ".a11y-hierarchy-overlay",
        )!;
        assert.ok(layer);
        assert.strictEqual(layer.getAttribute("aria-hidden"), "true");
        assert.strictEqual(layer.children.length, 0);
    });

    it("is idempotent: a second call does not double-add the layer", () => {
        const container = document.createElement("div");
        ensureHierarchyOverlay(container);
        ensureHierarchyOverlay(container);
        assert.strictEqual(
            container.querySelectorAll(".a11y-hierarchy-overlay").length,
            1,
        );
    });

    it("is a no-op when container is null", () => {
        assert.doesNotThrow(() => ensureHierarchyOverlay(null));
    });
});

describe("applyHierarchyOverlay", () => {
    it("paints one .a11y-hierarchy-box per node with percent-of-natural bounds", () => {
        const { card, container, img } = buildCardWithImage(200, 200);
        ensureHierarchyOverlay(container);
        applyHierarchyOverlay(
            card,
            [node({ boundsInScreen: "20,40,120,140" })],
            img,
        );
        const box = container.querySelector<HTMLElement>(
            ".a11y-hierarchy-box",
        )!;
        assert.ok(box);
        assert.strictEqual(box.style.left, "10%");
        assert.strictEqual(box.style.top, "20%");
        assert.strictEqual(box.style.width, "50%");
        assert.strictEqual(box.style.height, "50%");
    });

    it("flags unmerged nodes with .a11y-hierarchy-unmerged", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        ensureHierarchyOverlay(container);
        applyHierarchyOverlay(
            card,
            [
                node({ merged: true, boundsInScreen: "0,0,10,10" }),
                node({ merged: false, boundsInScreen: "10,10,20,20" }),
            ],
            img,
        );
        const boxes = container.querySelectorAll<HTMLElement>(
            ".a11y-hierarchy-box",
        );
        assert.ok(!boxes[0].classList.contains("a11y-hierarchy-unmerged"));
        assert.ok(boxes[1].classList.contains("a11y-hierarchy-unmerged"));
    });

    it("composes label / role / states into the box title (skipping empties)", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        ensureHierarchyOverlay(container);
        applyHierarchyOverlay(
            card,
            [
                node({
                    label: "Submit",
                    role: "Button",
                    states: ["enabled", "focused"],
                    boundsInScreen: "0,0,10,10",
                }),
                node({
                    label: "",
                    role: null,
                    states: [],
                    boundsInScreen: "10,10,20,20",
                }),
            ],
            img,
        );
        const boxes = container.querySelectorAll<HTMLElement>(
            ".a11y-hierarchy-box",
        );
        assert.strictEqual(
            boxes[0].title,
            "Submit · Button · enabled, focused",
        );
        assert.strictEqual(
            boxes[1].title,
            "",
            "no parts → no title attribute set",
        );
    });

    it("clears the layer on repaint and skips nodes with malformed bounds", () => {
        const { card, container, img } = buildCardWithImage(100, 100);
        ensureHierarchyOverlay(container);
        applyHierarchyOverlay(card, [node(), node()], img);
        assert.strictEqual(
            container.querySelectorAll(".a11y-hierarchy-box").length,
            2,
        );
        applyHierarchyOverlay(
            card,
            [
                node({ boundsInScreen: "0,0,10,10" }),
                node({ boundsInScreen: "bogus" }),
            ],
            img,
        );
        assert.strictEqual(
            container.querySelectorAll(".a11y-hierarchy-box").length,
            1,
            "second call should replace and skip malformed bounds",
        );
    });

    it("bails when the .a11y-hierarchy-overlay layer is missing or the image lacks natural dims", () => {
        const noLayer = buildCardWithImage(100, 100);
        assert.doesNotThrow(() =>
            applyHierarchyOverlay(noLayer.card, [node()], noLayer.img),
        );

        const noDims = buildCardWithImage(0, 0);
        ensureHierarchyOverlay(noDims.container);
        applyHierarchyOverlay(noDims.card, [node()], noDims.img);
        assert.strictEqual(
            noDims.container.querySelectorAll(".a11y-hierarchy-box").length,
            0,
        );
    });
});
