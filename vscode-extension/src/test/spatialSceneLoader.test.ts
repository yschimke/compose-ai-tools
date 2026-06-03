import * as assert from "assert";
import { readFileSync } from "fs";
import * as path from "path";
import {
    parseSpatialScene,
    parseSpatialSceneJson,
    renderableQuads,
    SpatialSceneParseError,
} from "../webview/spatial/sceneLoader";
import { SPATIAL_SCENE_VERSION } from "../webview/shared/spatialScene";

const extensionRoot = path.resolve(__dirname, "../..");

// The minimum a payload needs to clear the contract's shallow `isSpatialScene`
// guard plus this loader's version check.
function validSceneObject(): Record<string, unknown> {
    return {
        version: SPATIAL_SCENE_VERSION,
        units: "dp",
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
    };
}

describe("parseSpatialScene", () => {
    it("accepts a minimal valid scene and fills optional collections", () => {
        const scene = parseSpatialScene(validSceneObject());
        assert.strictEqual(scene.version, SPATIAL_SCENE_VERSION);
        assert.strictEqual(scene.units, "dp");
        assert.strictEqual(scene.panels.length, 1);
        assert.deepStrictEqual(scene.orbiters, []);
        assert.strictEqual(scene.environment, null);
    });

    it("preserves provided orbiters and environment", () => {
        const obj = validSceneObject();
        obj.orbiters = [
            {
                id: "rail",
                edge: "end",
                poseInRoot: {
                    translation: { x: 100, y: 0, z: 0 },
                    rotation: { x: 0, y: 0, z: 0, w: 1 },
                },
                sizeDp: { width: 80, height: 400 },
                texture: "rail.png",
            },
        ];
        obj.environment = { kind: "color", color: "#101014" };
        const scene = parseSpatialScene(obj);
        assert.strictEqual(scene.orbiters?.length, 1);
        assert.strictEqual(scene.orbiters?.[0].edge, "end");
        assert.deepStrictEqual(scene.environment, {
            kind: "color",
            color: "#101014",
        });
    });

    it("passes through additive optional fields at the same version", () => {
        const obj = validSceneObject();
        obj.previewId = "com.example.Foo";
        (obj.panels as Array<Record<string, unknown>>)[0].parentId = "column";
        const scene = parseSpatialScene(obj);
        assert.strictEqual(scene.previewId, "com.example.Foo");
        assert.strictEqual(scene.panels[0].parentId, "column");
    });

    it("rejects a payload that fails the structural guard", () => {
        const obj = validSceneObject();
        delete obj.panels;
        assert.throws(
            () => parseSpatialScene(obj),
            (err: unknown) =>
                err instanceof SpatialSceneParseError &&
                /not a SpatialScene/.test(err.message),
        );
    });

    it("rejects the wrong units", () => {
        const obj = validSceneObject();
        obj.units = "px";
        assert.throws(
            () => parseSpatialScene(obj),
            (err: unknown) => err instanceof SpatialSceneParseError,
        );
    });

    it("rejects an unsupported version with a clear message", () => {
        const obj = validSceneObject();
        obj.version = SPATIAL_SCENE_VERSION + 1;
        assert.throws(
            () => parseSpatialScene(obj),
            (err: unknown) =>
                err instanceof SpatialSceneParseError &&
                /unsupported SpatialScene version/.test(err.message),
        );
    });
});

describe("parseSpatialSceneJson", () => {
    it("wraps JSON syntax errors as SpatialSceneParseError", () => {
        assert.throws(
            () => parseSpatialSceneJson("{ not json"),
            (err: unknown) =>
                err instanceof SpatialSceneParseError &&
                /invalid JSON/.test(err.message),
        );
    });

    it("parses the committed contract fixture (spatial-scene)", () => {
        const text = readFileSync(
            path.join(
                extensionRoot,
                "preview-harness/fixtures/spatial-scene/scene.json",
            ),
            "utf8",
        );
        const scene = parseSpatialSceneJson(text);
        assert.strictEqual(scene.panels.length, 2);
        assert.deepStrictEqual(
            scene.panels.map((p) => p.id),
            ["top", "bottom"],
        );
        assert.strictEqual(scene.panels[0].texture, "top.png");
    });

    it("parses the committed spatial-rich fixture (panels + orbiters)", () => {
        const text = readFileSync(
            path.join(
                extensionRoot,
                "spatial-fixtures/spatial-rich/scene.json",
            ),
            "utf8",
        );
        const scene = parseSpatialSceneJson(text);
        assert.strictEqual(scene.panels.length, 4);
        assert.strictEqual(scene.orbiters?.length, 2);
        // renderableQuads flattens panels + orbiters for the viewer.
        assert.strictEqual(renderableQuads(scene).length, 6);
    });
});

describe("renderableQuads", () => {
    it("returns panels then orbiters, tolerating a missing orbiters list", () => {
        const scene = parseSpatialScene(validSceneObject());
        const quads = renderableQuads(scene);
        assert.strictEqual(quads.length, 1);
        assert.strictEqual(quads[0].id, "top");
    });
});
