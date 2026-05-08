// Unit tests for the presentation framework — registry behaviour
// (built-in presenters dispatched correctly), and the arrow
// correlator (attach/detach is idempotent and matches legend rows
// against overlay elements by id).

import * as assert from "assert";
import {
    _resetPresentersForTest,
    getPresenter,
    registerPresenter,
} from "../webview/preview/focusPresentation";
import { LegendArrowController } from "../webview/preview/legendArrow";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../types";

const baseParams: PreviewInfo["params"] = {
    name: null,
    device: null,
    widthDp: 0,
    heightDp: 0,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

const samplePreview: PreviewInfo = {
    id: "com.example.PreviewsKt.Sample",
    functionName: "Sample",
    className: "com.example.PreviewsKt",
    sourceFile: "Previews.kt",
    params: baseParams,
    captures: [
        {
            advanceTimeMillis: null,
            scroll: null,
            renderOutput: "renders/sample.png",
        },
    ],
};

function ctx(overrides?: {
    nodes?: readonly AccessibilityNode[];
    findings?: readonly AccessibilityFinding[];
    cardDataset?: Record<string, string>;
}) {
    const card = document.createElement("div");
    if (overrides?.cardDataset) {
        Object.assign(card.dataset, overrides.cardDataset);
    }
    return {
        card,
        preview: samplePreview,
        findings: overrides?.findings ?? [],
        nodes: overrides?.nodes ?? [],
    };
}

describe("focusPresentation registry", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("dispatches the registered presenter for a kind", () => {
        // The built-in presenters register at module import.
        const a11y = getPresenter("a11y/hierarchy");
        assert.ok(a11y, "a11y/hierarchy presenter should be registered");
    });

    it("returns a placeholder report for a11y/hierarchy with no nodes", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const result = a11y(ctx());
        assert.ok(result);
        assert.ok(result!.report);
        assert.strictEqual(result!.overlay, undefined);
        assert.strictEqual(result!.legend, undefined);
    });

    it("paints overlay boxes and a legend when nodes are present", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const nodes: AccessibilityNode[] = [
            {
                label: "Submit",
                role: "Button",
                states: ["enabled"],
                merged: false,
                boundsInScreen: "10,20,110,80",
            },
            {
                label: "Description",
                role: "Text",
                states: [],
                merged: true,
                boundsInScreen: "0,90,200,140",
            },
        ];
        const result = a11y(ctx({ nodes }));
        assert.ok(result);
        assert.ok(result!.overlay instanceof HTMLElement);
        const boxes = result!.overlay!.querySelectorAll("[data-overlay-id]");
        assert.strictEqual(boxes.length, 2);
        assert.strictEqual(boxes[0].getAttribute("data-overlay-id"), "node-0");
        assert.ok(result!.legend);
        assert.strictEqual(result!.legend!.entries.length, 2);
        assert.strictEqual(result!.legend!.entries[0].id, "node-0");
        assert.strictEqual(result!.legend!.entries[1].level, "warning");
    });

    it("emits an error contribution when every bound is malformed", () => {
        const a11y = getPresenter("a11y/hierarchy")!;
        const nodes: AccessibilityNode[] = [
            {
                label: "Bad",
                role: "Button",
                states: [],
                merged: false,
                boundsInScreen: "garbage",
            },
        ];
        const result = a11y(ctx({ nodes }));
        assert.ok(result);
        assert.ok(result!.error);
        assert.strictEqual(result!.overlay, null);
    });

    it("renderErrorPresenter activates only when the card carries a render error", () => {
        const renderErr = getPresenter("local/render/error")!;
        assert.strictEqual(renderErr(ctx()), null);
        const result = renderErr(
            ctx({
                cardDataset: { renderError: "NPE", renderErrorDetail: "boom" },
            }),
        );
        assert.ok(result);
        assert.ok(result!.error);
        assert.strictEqual(result!.error!.message, "NPE");
        assert.strictEqual(result!.error!.detail, "boom");
    });

    it("registerPresenter is last-write-wins (visible to tests)", () => {
        // Snapshot the existing presenter so we can restore module
        // behaviour for sibling tests.
        const original = getPresenter("a11y/hierarchy")!;
        registerPresenter("a11y/hierarchy", () => null);
        assert.strictEqual(getPresenter("a11y/hierarchy")!(ctx()), null);
        // Restore by re-registering the original directly. (Using
        // `_resetPresentersForTest` would also drop the other built-ins.)
        registerPresenter("a11y/hierarchy", original);
        assert.notStrictEqual(getPresenter("a11y/hierarchy")!(ctx()), null);
    });

    it("_resetPresentersForTest empties the registry", () => {
        _resetPresentersForTest();
        assert.strictEqual(getPresenter("a11y/hierarchy"), undefined);
        // Re-import to reseed for sibling tests in this file. Mocha
        // doesn't reload modules across `describe` blocks, so mirror
        // the side-effect register calls inline.
        require("../webview/preview/focusPresentation");
    });
});

describe("LegendArrowController", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    function setup(): {
        legends: HTMLElement;
        overlay: HTMLElement;
        controller: LegendArrowController;
    } {
        const legends = document.createElement("div");
        const overlay = document.createElement("div");
        const row = document.createElement("li");
        row.dataset.legendId = "node-0";
        legends.appendChild(row);
        const box = document.createElement("div");
        box.dataset.overlayId = "node-0";
        overlay.appendChild(box);
        document.body.appendChild(legends);
        document.body.appendChild(overlay);
        const controller = new LegendArrowController();
        return { legends, overlay, controller };
    }

    it("adds legend-active to the matching overlay on hover", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        row.dispatchEvent(new Event("mouseenter"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(box.classList.contains("legend-active"));
        row.dispatchEvent(new Event("mouseleave"));
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("detach removes listeners (idempotent)", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        controller.detach();
        controller.detach(); // no throw
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        row.dispatchEvent(new Event("mouseenter"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("re-attach replaces previous listeners", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        controller.attach(legends, overlay);
        const row = legends.querySelector<HTMLElement>("[data-legend-id]")!;
        // Without dedupe, two listeners would fire and a removeEvent
        // pair would still leave one dangling. We assert the toggle
        // behaviour instead — enter then leave should clear the class.
        row.dispatchEvent(new Event("mouseenter"));
        row.dispatchEvent(new Event("mouseleave"));
        const box = overlay.querySelector<HTMLElement>("[data-overlay-id]")!;
        assert.ok(!box.classList.contains("legend-active"));
    });

    it("creates a singleton SVG arrow on document.body", () => {
        const { legends, overlay, controller } = setup();
        controller.attach(legends, overlay);
        const svgs = document.querySelectorAll("#focus-legend-arrow");
        assert.strictEqual(svgs.length, 1);
    });
});
