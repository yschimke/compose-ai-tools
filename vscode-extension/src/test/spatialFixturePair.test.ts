import * as assert from "assert";
import { readFileSync } from "fs";
import * as path from "path";
import { parseSpatialSceneJson } from "../webview/spatial/sceneLoader";
import {
    panelWireframesById,
    parseSpatialSemanticsTreeJson,
} from "../webview/spatial/semanticsTreeLoader";

const extensionRoot = path.resolve(__dirname, "../..");
const fixtureDir = path.join(
    extensionRoot,
    "preview-harness/fixtures/spatial-scene",
);

// The `openSpatialFixture` dev command loads scene.json + its companion
// compose-spatial-semantics.json and overlays per-panel wireframes matched by
// panel id. The overlay silently no-ops if the two fixtures drift apart, so
// pin them together: every scene panel must have a matching wireframe.
describe("spatial-scene fixture pair", () => {
    const scene = parseSpatialSceneJson(
        readFileSync(path.join(fixtureDir, "scene.json"), "utf8"),
    );
    const tree = parseSpatialSemanticsTreeJson(
        readFileSync(
            path.join(fixtureDir, "compose-spatial-semantics.json"),
            "utf8",
        ),
    );

    it("shares previewId across scene and semantics tree", () => {
        assert.strictEqual(tree.previewId, scene.previewId);
    });

    it("provides a wireframe overlay for every scene panel", () => {
        const wireframes = panelWireframesById(tree);
        for (const panel of scene.panels) {
            assert.ok(
                wireframes.has(panel.id),
                `no wireframe overlay for scene panel "${panel.id}"`,
            );
        }
    });
});
