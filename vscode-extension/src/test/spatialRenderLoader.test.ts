import * as assert from "assert";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs";
import { tmpdir } from "os";
import * as path from "path";
import {
    loadSpatialFromCaptures,
    loadSpatialRender,
} from "../spatialRenderLoader";
import { SPATIAL_SCENE_VERSION } from "../webview/shared/spatialScene";
import { SPATIAL_SEMANTICS_TREE_VERSION } from "../webview/shared/spatialSemanticsTree";

const sceneJson = (previewId: string) =>
    JSON.stringify({
        version: SPATIAL_SCENE_VERSION,
        units: "dp",
        previewId,
        camera: {
            kind: "orbit",
            target: { x: 0, y: 0, z: 0 },
            distance: 1200,
            yawDeg: 0,
            pitchDeg: -10,
        },
        panels: [
            {
                id: "top",
                label: "Now Playing",
                poseInRoot: {
                    translation: { x: 0, y: 80, z: 0 },
                    rotation: { x: 0, y: 0, z: 0, w: 1 },
                },
                sizeDp: { width: 560, height: 200 },
                texture: "top.png",
            },
        ],
    });

const treeJson = JSON.stringify({
    version: SPATIAL_SEMANTICS_TREE_VERSION,
    units: "dp",
    root: {
        id: "subspaceRoot",
        kind: "subspaceRoot",
        poseInRoot: {
            translation: { x: 0, y: 0, z: 0 },
            rotation: { x: 0, y: 0, z: 0, w: 1 },
        },
        sizeDp: { width: 0, height: 0, depth: 0 },
        children: [
            {
                id: "top",
                kind: "panel",
                poseInRoot: {
                    translation: { x: 0, y: 0, z: 0 },
                    rotation: { x: 0, y: 0, z: 0, w: 1 },
                },
                sizeDp: { width: 560, height: 200, depth: 0 },
                panelContent: { nodeId: "1", boundsInRoot: "0,0,560,200" },
            },
        ],
    },
});

describe("loadSpatialRender", () => {
    let baseDir: string;

    beforeEach(() => {
        baseDir = mkdtempSync(path.join(tmpdir(), "spatial-render-"));
    });

    afterEach(() => {
        rmSync(baseDir, { recursive: true, force: true });
    });

    // The capture path the XR discovery emits: renders/<id>/composite.png.
    const renderOutput = "renders/com.example.Xr/composite.png";
    const renderDir = () => path.join(baseDir, "renders", "com.example.Xr");

    it("returns null when the capture has no scene.json (ordinary preview)", () => {
        assert.strictEqual(loadSpatialRender(baseDir, "SomePreview.png"), null);
    });

    it("loads scene + companion semantics tree from the render subdir", () => {
        mkdirSync(renderDir(), { recursive: true });
        writeFileSync(path.join(renderDir(), "scene.json"), sceneJson("Xr"));
        writeFileSync(
            path.join(renderDir(), "compose-spatial-semantics.json"),
            treeJson,
        );

        const loaded = loadSpatialRender(baseDir, renderOutput);
        assert.ok(loaded, "expected a loaded spatial render");
        assert.strictEqual(loaded.sceneDir, renderDir());
        assert.strictEqual(loaded.scene.previewId, "Xr");
        assert.deepStrictEqual(
            loaded.scene.panels.map((p) => p.id),
            ["top"],
        );
        assert.ok(loaded.semanticsTree, "expected the companion tree");
        assert.strictEqual(loaded.semanticsTree.root.kind, "subspaceRoot");
    });

    it("loads the scene even when the semantics tree is absent (best-effort)", () => {
        mkdirSync(renderDir(), { recursive: true });
        writeFileSync(path.join(renderDir(), "scene.json"), sceneJson("Xr"));

        const loaded = loadSpatialRender(baseDir, renderOutput);
        assert.ok(loaded);
        assert.strictEqual(loaded.semanticsTree, undefined);
    });

    it("throws when scene.json is present but violates the contract", () => {
        mkdirSync(renderDir(), { recursive: true });
        writeFileSync(path.join(renderDir(), "scene.json"), '{"version":999}');

        assert.throws(() => loadSpatialRender(baseDir, renderOutput));
    });

    describe("loadSpatialFromCaptures", () => {
        it("returns null for an ordinary preview's captures (no scene)", () => {
            assert.strictEqual(
                loadSpatialFromCaptures(baseDir, [
                    { renderOutput: "Preview.png" },
                    { renderOutput: "Preview.dark.png" },
                ]),
                null,
            );
        });

        it("returns null for empty / undefined captures", () => {
            assert.strictEqual(loadSpatialFromCaptures(baseDir, []), null);
            assert.strictEqual(
                loadSpatialFromCaptures(baseDir, undefined),
                null,
            );
        });

        it("loads the first capture that has a scene", () => {
            mkdirSync(renderDir(), { recursive: true });
            writeFileSync(
                path.join(renderDir(), "scene.json"),
                sceneJson("Xr"),
            );

            // A non-XR capture precedes the XR composite; the XR one wins.
            const loaded = loadSpatialFromCaptures(baseDir, [
                { renderOutput: "Preview.png" },
                { renderOutput },
            ]);
            assert.ok(loaded);
            assert.strictEqual(loaded.scene.previewId, "Xr");
        });
    });
});
