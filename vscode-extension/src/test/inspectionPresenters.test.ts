// Unit tests for the Inspection-bundle presenters (compose/semantics,
// layout/inspector, uia/hierarchy). Verifies that each kind:
//
//   - returns null when its payload is missing (so the inspector falls
//     through to the pending placeholder rather than a half-formed
//     Report);
//   - emits a Report whose body is the inspection tree-table;
//   - paints an overlay layer of `data-overlay-id` boxes that the
//     existing `LegendArrowController` can correlate with the table
//     rows;
//   - wires the row-action / source-link affordances (uia/hierarchy
//     "Copy selector" and layout/inspector source-link, respectively).
//
// The post-`openSourceFile` path is exercised by stubbing
// `window.acquireVsCodeApi`; the rest stays DOM-only.

import * as assert from "assert";
import {
    getPresenter,
    type PresenterContext,
} from "../webview/preview/focusPresentation";
// Side-effect import so registerInspectionPresenters has run by the time
// the registry getter is hit, even if focusPresentation.ts's own seed
// call hasn't fired in this test process yet.
import "../webview/preview/inspectionPresenters";
import type { PreviewInfo } from "../types";
import { flushMicrotasks, stubClipboard } from "./helpers/clipboard";

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
    captures: [{ advanceTimeMillis: null, scroll: null, renderOutput: "x" }],
};

function ctx(data: (kind: string) => unknown): PresenterContext {
    return {
        card: document.createElement("div"),
        preview: samplePreview,
        findings: [],
        nodes: [],
        data,
    };
}

describe("compose/semantics presenter", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("returns null when no payload is cached", () => {
        const p = getPresenter("compose/semantics")!;
        assert.strictEqual(p(ctx(() => undefined)), null);
    });

    it("renders a report with one row per semantics node and overlays bounds", () => {
        const p = getPresenter("compose/semantics")!;
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,100,100",
                label: "Sample",
                role: "Button",
                testTag: "submit",
                mergeMode: "Merged",
                clickable: true,
                children: [
                    {
                        nodeId: "2",
                        boundsInRoot: "10,10,40,40",
                        label: "Inner",
                    },
                ],
            },
        };
        const result = p(
            ctx((kind) => (kind === "compose/semantics" ? payload : undefined)),
        );
        assert.ok(result);
        assert.ok(result!.report);
        const rows = result!.report!.body.querySelectorAll(
            "tbody tr[data-legend-id]",
        );
        assert.strictEqual(rows.length, 2);
        assert.strictEqual(rows[0].getAttribute("data-legend-id"), "1");
        // Two parsed bounds → two overlay boxes.
        const boxes = result!.overlay!.querySelectorAll("[data-overlay-id]");
        assert.strictEqual(boxes.length, 2);
        assert.strictEqual(boxes[0].getAttribute("data-overlay-id"), "1");
        // Merged node lands on the warning palette.
        assert.strictEqual(boxes[0].getAttribute("data-level"), "warning");
    });
});

describe("layout/inspector presenter", () => {
    afterEach(() => {
        document.body.innerHTML = "";
        delete (window as unknown as { acquireVsCodeApi?: unknown })
            .acquireVsCodeApi;
    });

    it("returns null when no payload is cached", () => {
        const p = getPresenter("layout/inspector")!;
        assert.strictEqual(p(ctx(() => undefined)), null);
    });

    it("emits a source-link button that posts openSourceFile when clicked", () => {
        const posted: unknown[] = [];
        (
            window as unknown as { acquireVsCodeApi: () => unknown }
        ).acquireVsCodeApi = () => ({
            postMessage: (msg: unknown) => posted.push(msg),
            getState: () => undefined,
            setState: () => undefined,
        });
        const p = getPresenter("layout/inspector")!;
        const payload = {
            root: {
                nodeId: "1",
                component: "Column",
                source: "Sample.kt:42",
                bounds: { left: 0, top: 0, right: 100, bottom: 200 },
                size: { width: 100, height: 200 },
                modifiers: [
                    { name: "padding", value: "8.dp" },
                    { name: "fillMaxWidth" },
                    { name: "background" },
                    { name: "clickable" },
                ],
                children: [],
            },
        };
        const result = p(
            ctx((kind) => (kind === "layout/inspector" ? payload : undefined)),
        );
        assert.ok(result);
        const link = result!.report!.body.querySelector<HTMLButtonElement>(
            ".inspection-source-link",
        )!;
        assert.ok(link);
        assert.strictEqual(link.textContent, "Sample.kt:42");
        link.click();
        assert.deepStrictEqual(posted, [
            {
                command: "openSourceFile",
                fileName: "Sample.kt",
                line: 42,
                className: "com.example.PreviewsKt",
            },
        ]);
        // Modifier-truncation chip: only the first three names inline,
        // a `+N` chip behind a title-attribute tooltip for the rest.
        const more = result!.report!.body.querySelector(
            ".inspection-modifiers-more",
        )!;
        assert.ok(more);
        assert.match(more.textContent ?? "", /^\s*\+1/);
    });
});

describe("uia/hierarchy presenter", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("returns an empty-state report when the payload has no nodes", () => {
        const p = getPresenter("uia/hierarchy")!;
        const result = p(
            ctx((kind) =>
                kind === "uia/hierarchy" ? { nodes: [] } : undefined,
            ),
        );
        assert.ok(result);
        assert.ok(result!.report);
        assert.strictEqual(result!.report!.summary, "No actionable nodes");
        assert.strictEqual(result!.overlay, undefined);
    });

    it("paints one row per node and exposes the Copy selector action", async () => {
        const p = getPresenter("uia/hierarchy")!;
        const payload = {
            nodes: [
                {
                    text: "Submit",
                    testTag: "submit",
                    boundsInScreen: "0,0,100,40",
                    actions: ["click"],
                },
                {
                    text: "Cancel",
                    testTag: "cancel",
                    boundsInScreen: "0,40,100,80",
                    actions: ["click"],
                },
            ],
        };
        const captured = stubClipboard();
        try {
            const result = p(
                ctx((kind) => (kind === "uia/hierarchy" ? payload : undefined)),
            );
            assert.ok(result);
            const rows = result!.report!.body.querySelectorAll(
                "tbody tr[data-legend-id]",
            );
            assert.strictEqual(rows.length, 2);
            const actBtn = rows[0].querySelector<HTMLButtonElement>(
                ".inspection-tree-action-btn",
            )!;
            actBtn.click();
            await flushMicrotasks();
            assert.strictEqual(captured.text, 'By.testTag("submit")');
            const boxes =
                result!.overlay!.querySelectorAll("[data-overlay-id]");
            assert.strictEqual(boxes.length, 2);
            assert.strictEqual(
                boxes[0].getAttribute("data-overlay-id"),
                "uia-0",
            );
        } finally {
            captured.restore();
        }
    });
});
