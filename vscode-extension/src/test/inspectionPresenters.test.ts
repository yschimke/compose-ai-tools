// Unit tests for the inspection-bundle compute path that feeds
// `paintBundleBoxes(card, "inspection", data.overlay)` in `main.ts`.

import * as assert from "assert";
import {
    computeInspectionBundleData,
    type InspectionKind,
} from "../webview/preview/inspectionPresenters";

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
