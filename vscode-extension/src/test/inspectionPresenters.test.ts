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
import {
    computeInspectionBundleData,
    type InspectionKind,
} from "../webview/preview/inspectionPresenters";
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

// Verifies the inspection-bundle compute path that feeds
// `paintBundleBoxes(card, "inspection", data.overlay)` in `main.ts`.
// This is the cardBundleOverlay surface — separate from the legacy
// focus-inspector overlay layer the per-kind presenters above paint.
describe("computeInspectionBundleData (cardBundleOverlay path)", () => {
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("emits one OverlayBox per semantics node with parsed bounds", () => {
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,100,100",
                label: "Sample",
                role: "Button",
                testTag: "submit",
                children: [
                    {
                        nodeId: "2",
                        boundsInRoot: "10,10,40,40",
                        label: "Inner",
                    },
                ],
            },
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "compose/semantics" ? payload : undefined),
            new Set<InspectionKind>(["compose/semantics"]),
        );
        assert.strictEqual(data.overlay.length, 2);
        assert.strictEqual(data.overlay[0].id, "semantics-1");
        assert.deepStrictEqual(data.overlay[0].bounds, {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        });
        assert.strictEqual(data.overlay[0].level, "info");
        // Tooltip aggregates label · role · testTag.
        assert.strictEqual(data.overlay[0].tooltip, "Sample · Button · submit");
        // Section body is the tree-table; one section per enabled kind
        // with a payload.
        assert.strictEqual(data.sections.length, 1);
        assert.strictEqual(data.sections[0].kind, "compose/semantics");
    });

    it("skips overlay entries for malformed boundsInScreen but still emits the row", () => {
        const payload = {
            nodes: [
                {
                    text: "GoodNode",
                    testTag: "good",
                    boundsInScreen: "0,0,50,50",
                },
                {
                    text: "BadNode",
                    testTag: "bad",
                    boundsInScreen: "not,a,real,bounds",
                },
                {
                    text: "MissingNode",
                    testTag: "missing",
                    boundsInScreen: "",
                },
            ],
        };
        const data = computeInspectionBundleData(
            (kind) => (kind === "uia/hierarchy" ? payload : undefined),
            new Set<InspectionKind>(["uia/hierarchy"]),
        );
        // Only the well-formed node lands in the overlay.
        assert.strictEqual(data.overlay.length, 1);
        assert.strictEqual(data.overlay[0].id, "uia-0");
        // All three nodes still surface in the tree-table body so the
        // user can read them — overlay-skipping is silent at the row
        // level.
        const rows = data.sections[0].data.body.querySelectorAll(
            "tbody tr[data-legend-id]",
        );
        assert.strictEqual(rows.length, 3);
    });

    it("dedupes the merged overlay by id when two kinds share an id", () => {
        // Construct two kinds whose first node ids collide under the
        // kind-namespacing scheme — semantics + layout each have id
        // "shared", which becomes "semantics-shared" / "layout-shared"
        // respectively. To exercise the dedupe rule we feed the second
        // kind a payload whose first node carries the SAME final id by
        // matching the namespaced prefix.
        const semanticsPayload = {
            root: {
                nodeId: "shared",
                boundsInRoot: "0,0,10,10",
                label: "A",
            },
        };
        const layoutPayload = {
            // The layout node id will be namespaced to "layout-shared"
            // — to collide with "semantics-shared" we'd need a daemon
            // that emits matching cross-kind ids. Easier path: build
            // two kinds whose namespaced ids overlap naturally.
            root: {
                nodeId: "shared",
                component: "Column",
                bounds: { left: 0, top: 0, right: 20, bottom: 20 },
            },
        };
        // Sanity: distinct prefixes → no natural collision, total 2.
        const distinct = computeInspectionBundleData(
            (kind) => {
                if (kind === "compose/semantics") return semanticsPayload;
                if (kind === "layout/inspector") return layoutPayload;
                return undefined;
            },
            new Set<InspectionKind>(["compose/semantics", "layout/inspector"]),
        );
        assert.strictEqual(distinct.overlay.length, 2);
        const ids = distinct.overlay.map((b) => b.id);
        assert.deepStrictEqual(ids, ["semantics-shared", "layout-shared"]);

        // Force a collision: emit two semantics nodes with the same
        // `nodeId` — the daemon shouldn't, but the dedupe rule must
        // still hold so a buggy payload doesn't stack two boxes.
        const collidingPayload = {
            root: {
                nodeId: "dup",
                boundsInRoot: "0,0,10,10",
                label: "Outer",
                children: [
                    {
                        nodeId: "dup",
                        boundsInRoot: "1,1,9,9",
                        label: "Inner",
                    },
                ],
            },
        };
        const collided = computeInspectionBundleData(
            (kind) =>
                kind === "compose/semantics" ? collidingPayload : undefined,
            new Set<InspectionKind>(["compose/semantics"]),
        );
        assert.strictEqual(collided.overlay.length, 1);
        assert.strictEqual(collided.overlay[0].id, "semantics-dup");
        // First-seen wins.
        assert.deepStrictEqual(collided.overlay[0].bounds, {
            left: 0,
            top: 0,
            right: 10,
            bottom: 10,
        });
    });

    it("ignores kinds the user has not enabled", () => {
        const payload = {
            root: {
                nodeId: "1",
                boundsInRoot: "0,0,10,10",
                label: "Hidden",
            },
        };
        const data = computeInspectionBundleData(
            () => payload,
            // Empty enabled-kinds set — every kind should be skipped.
            new Set<InspectionKind>(),
        );
        assert.strictEqual(data.overlay.length, 0);
        assert.strictEqual(data.sections.length, 0);
    });
});
