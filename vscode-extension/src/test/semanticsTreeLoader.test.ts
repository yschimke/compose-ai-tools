import * as assert from "assert";
import { readFileSync } from "fs";
import * as path from "path";
import {
    flattenPanels,
    panelWireframesById,
    parseSpatialSemanticsTree,
    parseSpatialSemanticsTreeJson,
    SpatialSemanticsTreeParseError,
} from "../webview/spatial/semanticsTreeLoader";
import { SPATIAL_SEMANTICS_TREE_VERSION } from "../webview/shared/spatialSemanticsTree";

const extensionRoot = path.resolve(__dirname, "../..");

/** The minimum a payload needs to clear the contract guard + this loader's version check. */
function validTreeObject(): Record<string, unknown> {
    return {
        version: SPATIAL_SEMANTICS_TREE_VERSION,
        units: "dp",
        previewId: "Demo",
        root: {
            id: "subspaceRoot",
            kind: "subspaceRoot",
            poseInRoot: {
                translation: { x: 0, y: 0, z: 0 },
                rotation: { x: 0, y: 0, z: 0, w: 1 },
            },
            sizeDp: { width: 0, height: 0 },
            children: [
                {
                    id: "panel",
                    kind: "panel",
                    poseInRoot: {
                        translation: { x: 0, y: 0, z: 0 },
                        rotation: { x: 0, y: 0, z: 0, w: 1 },
                    },
                    sizeDp: { width: 100, height: 50 },
                    panelContent: {
                        nodeId: "1",
                        boundsInRoot: "0,0,100,50",
                        children: [
                            { nodeId: "2", boundsInRoot: "10,10,40,30" },
                        ],
                    },
                },
            ],
        },
    };
}

function loadFixtureTree() {
    const text = readFileSync(
        path.resolve(
            extensionRoot,
            "preview-harness/fixtures/spatial-semantics-tree/tree.json",
        ),
        "utf8",
    );
    return parseSpatialSemanticsTreeJson(text);
}

describe("semanticsTreeLoader", () => {
    describe("parseSpatialSemanticsTree", () => {
        it("accepts a valid tree", () => {
            const tree = parseSpatialSemanticsTree(validTreeObject());
            assert.strictEqual(tree.version, SPATIAL_SEMANTICS_TREE_VERSION);
            assert.strictEqual(tree.root.id, "subspaceRoot");
        });

        it("rejects a version mismatch with an actionable message", () => {
            const bad = { ...validTreeObject(), version: 999 };
            assert.throws(
                () => parseSpatialSemanticsTree(bad),
                (err: unknown) =>
                    err instanceof SpatialSemanticsTreeParseError &&
                    /unsupported SpatialSemanticsTree version 999/.test(
                        (err as Error).message,
                    ),
            );
        });

        it("rejects a payload with no root", () => {
            const bad = {
                version: SPATIAL_SEMANTICS_TREE_VERSION,
                units: "dp",
            };
            assert.throws(
                () => parseSpatialSemanticsTree(bad),
                SpatialSemanticsTreeParseError,
            );
        });

        it("rejects non-objects", () => {
            assert.throws(
                () => parseSpatialSemanticsTree(null),
                SpatialSemanticsTreeParseError,
            );
            assert.throws(
                () => parseSpatialSemanticsTree("nope"),
                SpatialSemanticsTreeParseError,
            );
        });
    });

    describe("parseSpatialSemanticsTreeJson", () => {
        it("wraps JSON syntax errors", () => {
            assert.throws(
                () => parseSpatialSemanticsTreeJson("{ not json"),
                (err: unknown) =>
                    err instanceof SpatialSemanticsTreeParseError &&
                    /invalid JSON/.test((err as Error).message),
            );
        });

        it("parses the committed fixture", () => {
            const tree = loadFixtureTree();
            assert.strictEqual(tree.previewId, "NowPlayingSpatialPreview");
            assert.strictEqual(tree.root.kind, "column");
        });
    });

    describe("flattenPanels", () => {
        it("returns only content-hosting panels, depth-first", () => {
            const panels = flattenPanels(loadFixtureTree());
            assert.deepStrictEqual(
                panels.map((p) => p.id),
                ["now-playing", "transport"],
            );
        });

        it("skips pure container nodes", () => {
            // The synthetic tree's subspaceRoot has no panelContent; only its panel child does.
            const panels = flattenPanels(
                parseSpatialSemanticsTree(validTreeObject()),
            );
            assert.deepStrictEqual(
                panels.map((p) => p.id),
                ["panel"],
            );
        });
    });

    describe("panelWireframesById", () => {
        it("derives per-panel boxes keyed by panel id from the fixture", () => {
            const byId = panelWireframesById(loadFixtureTree());
            assert.deepStrictEqual([...byId.keys()].sort(), [
                "now-playing",
                "transport",
            ]);

            const np = byId.get("now-playing")!;
            assert.deepStrictEqual(np.contentSize, { width: 560, height: 200 });
            // Root (10) + two children (11, 12), namespaced by panel id.
            assert.deepStrictEqual(
                np.boxes.map((b) => b.id),
                ["now-playing:10", "now-playing:11", "now-playing:12"],
            );
            assert.deepStrictEqual(np.boxes[1].bounds, {
                left: 16,
                top: 16,
                right: 300,
                bottom: 52,
            });
            assert.ok(np.boxes.every((b) => b.level === "info"));
        });

        it("draws no box for an unplaced panel subtree", () => {
            // Same rule the 2D inspector overlay follows: a node measured but
            // never placed has no position, so its bounds read as the panel's
            // origin. A panel hosting a `SubcomposeLayout` trial measure
            // (Wear `AlertDialogContent`) puts a whole phantom tree there.
            const tree = validTreeObject();
            const panel = (tree.root as Record<string, unknown>)
                .children as Record<string, unknown>[];
            (panel[0].panelContent as Record<string, unknown>).children = [
                { nodeId: "2", boundsInRoot: "10,10,40,30" },
                {
                    nodeId: "3",
                    boundsInRoot: "0,0,40,30",
                    placed: false,
                    children: [{ nodeId: "4", boundsInRoot: "0,0,20,20" }],
                },
            ];
            const byId = panelWireframesById(parseSpatialSemanticsTree(tree));
            assert.deepStrictEqual(
                byId.get("panel")!.boxes.map((b) => b.id),
                ["panel:1", "panel:2"],
            );
        });

        it("flags a mergeDescendants node as a warning-level box", () => {
            const byId = panelWireframesById(loadFixtureTree());
            const transport = byId.get("transport")!;
            const root = transport.boxes.find((b) => b.id === "transport:20")!;
            assert.strictEqual(root.level, "warning");
            const play = transport.boxes.find((b) => b.id === "transport:21")!;
            assert.strictEqual(play.level, "info");
            // The clickable "Play" button's tooltip carries its label + role.
            assert.strictEqual(play.tooltip, "Play [Button]");
        });

        it("skips a panel whose content root has unparseable bounds", () => {
            const obj = validTreeObject() as any;
            obj.root.children[0].panelContent.boundsInRoot = "garbage";
            const byId = panelWireframesById(parseSpatialSemanticsTree(obj));
            assert.strictEqual(byId.size, 0);
        });
    });
});
