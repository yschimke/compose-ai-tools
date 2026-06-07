import * as assert from "assert";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs";
import { tmpdir } from "os";
import * as path from "path";
import { pathToFileURL } from "url";

// The generator is an ESM dev script under preview-harness/ (not compiled by
// tsc), so load it via a runtime dynamic import — the path is built at runtime
// so TS doesn't try to resolve the .mjs module.
const genUrl = pathToFileURL(
    path.resolve(
        __dirname,
        "../../preview-harness/fixtures/spatial-xr-real.gen.mjs",
    ),
).href;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type BuildXrRealFixture = (renderDir: string, textureBaseUri: string) => any;

// Real dynamic ESM import: wrap in `new Function` so the TS CommonJS target
// doesn't downlevel `import()` to `require()` (which can't load a file:// .mjs).
const esmImport = new Function("u", "return import(u)") as (
    u: string,
) => Promise<{ buildXrRealFixture: BuildXrRealFixture }>;

async function loadBuilder(): Promise<BuildXrRealFixture> {
    const mod = await esmImport(genUrl);
    return mod.buildXrRealFixture;
}

const sceneJson = JSON.stringify({
    version: 1,
    units: "dp",
    previewId: "com.example.SubspaceXrLayout",
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
    version: 1,
    units: "dp",
    root: {
        id: "subspaceRoot",
        kind: "subspaceRoot",
        poseInRoot: {
            translation: { x: 0, y: 0, z: 0 },
            rotation: { x: 0, y: 0, z: 0, w: 1 },
        },
        sizeDp: { width: 0, height: 0, depth: 0 },
        children: [],
    },
});

describe("spatial-xr-real fixture generator", () => {
    let renderDir: string;

    beforeEach(() => {
        renderDir = mkdtempSync(path.join(tmpdir(), "xr-real-"));
    });

    afterEach(() => {
        rmSync(renderDir, { recursive: true, force: true });
    });

    it("builds a setSpatialScene fixture from a real render dir", async () => {
        writeFileSync(path.join(renderDir, "scene.json"), sceneJson);
        writeFileSync(
            path.join(renderDir, "compose-spatial-semantics.json"),
            treeJson,
        );
        const build = await loadBuilder();

        const fixture = build(renderDir, "/spatial-fixtures/spatial-xr-real/");
        const msg = fixture.messages[0];
        assert.strictEqual(msg.command, "setSpatialScene");
        assert.strictEqual(msg.scene.previewId, "com.example.SubspaceXrLayout");
        assert.strictEqual(
            msg.textureBaseUri,
            "/spatial-fixtures/spatial-xr-real/",
        );
        assert.ok(msg.semanticsTree, "companion tree included");
        assert.deepStrictEqual(fixture.actions, [
            { click: "#btn-spatial-toggle" },
        ]);
        assert.strictEqual(fixture.settleMs, 1600);
    });

    it("omits semanticsTree when the companion is absent (best-effort)", async () => {
        writeFileSync(path.join(renderDir, "scene.json"), sceneJson);
        const build = await loadBuilder();

        const fixture = build(renderDir, "/base/");
        assert.strictEqual(fixture.messages[0].semanticsTree, undefined);
    });

    it("throws when the render dir has no scene.json (not an XR render)", async () => {
        mkdirSync(path.join(renderDir, "empty"), { recursive: true });
        const build = await loadBuilder();
        assert.throws(() => build(renderDir, "/base/"));
    });
});
