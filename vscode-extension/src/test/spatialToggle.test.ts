// Behavioural tests for the 2D ⇄ 3D toggle controller. The viewer bundle and
// the `<spatial-view>` element are faked via injected effects so the suite runs
// under happy-dom without three.js — it pins the controller's FSM (button
// reveal, lazy single load, show/hide, scene application), not the 3D render.

import * as assert from "assert";
import {
    SpatialToggleController,
    type SpatialToggleEffects,
} from "../webview/preview/spatialToggle";
import { SPATIAL_SCENE_VERSION } from "../webview/shared/spatialScene";
import { parseSpatialScene } from "../webview/spatial/sceneLoader";

function scene() {
    return parseSpatialScene({
        version: SPATIAL_SCENE_VERSION,
        units: "dp",
        camera: {
            kind: "orbit",
            target: { x: 0, y: 0, z: 0 },
            distance: 1000,
            yawDeg: 0,
            pitchDeg: -10,
        },
        panels: [
            {
                id: "p1",
                poseInRoot: {
                    translation: { x: 0, y: 0, z: 0 },
                    rotation: { x: 0, y: 0, z: 0, w: 1 },
                },
                sizeDp: { width: 100, height: 100 },
                texture: "p1.png",
            },
        ],
    });
}

interface Harness {
    controller: SpatialToggleController;
    button: HTMLButtonElement;
    mount: HTMLElement;
    twoDStage: HTMLElement;
    loadCount: () => number;
    focusEvents: string[];
}

function makeHarness(): Harness {
    const button = document.createElement("button");
    const mount = document.createElement("div");
    const twoDStage = document.createElement("div");

    let loads = 0;
    let defined = false;
    const focusEvents: string[] = [];

    const effects: SpatialToggleEffects = {
        isDefined: () => defined,
        loadBundle: async () => {
            loads += 1;
            defined = true;
        },
        whenDefined: async () => undefined,
        // A fake `<spatial-view>` — a plain div we can read `scene` off of.
        createView: () => document.createElement("div") as never,
    };

    const controller = new SpatialToggleController({
        toggleButton: button,
        mount,
        twoDStage,
        bundleSrc: "/media/webview/spatial.js",
        nonce: "test-nonce",
        onPanelFocus: (id) => focusEvents.push(id),
        effects,
    });

    return {
        controller,
        button,
        mount,
        twoDStage,
        loadCount: () => loads,
        focusEvents,
    };
}

describe("SpatialToggleController", () => {
    it("hides the toggle button until a scene arrives", () => {
        const h = makeHarness();
        assert.strictEqual(h.button.hidden, true);
        h.controller.setScene(scene(), "https://base/");
        assert.strictEqual(h.button.hidden, false);
        assert.strictEqual(h.controller.hasScene, true);
    });

    it("starts in 2D with the 3D mount hidden", () => {
        const h = makeHarness();
        assert.strictEqual(h.controller.currentMode, "2d");
        assert.strictEqual(h.mount.children.length, 0);
    });

    it("mounts the viewer and hides the 2D stage on switch to 3D", async () => {
        const h = makeHarness();
        h.controller.setScene(scene(), "https://base/");
        await h.controller.setMode("3d");

        assert.strictEqual(h.controller.currentMode, "3d");
        assert.strictEqual(h.loadCount(), 1, "bundle loaded once");
        assert.strictEqual(h.mount.children.length, 1, "view mounted");
        assert.strictEqual(h.mount.hidden, false);
        assert.strictEqual(h.twoDStage.hidden, true);
        assert.strictEqual(h.button.getAttribute("aria-pressed"), "true");
        // The fake view received the scene.
        const view = h.mount.children[0] as HTMLElement & { scene?: unknown };
        assert.ok(view.scene, "scene applied to view");
    });

    it("restores the 2D stage on switch back to 2D", async () => {
        const h = makeHarness();
        h.controller.setScene(scene(), "https://base/");
        await h.controller.setMode("3d");
        await h.controller.setMode("2d");

        assert.strictEqual(h.controller.currentMode, "2d");
        assert.strictEqual(h.mount.hidden, true);
        assert.strictEqual(h.twoDStage.hidden, false);
        assert.strictEqual(h.button.getAttribute("aria-pressed"), "false");
    });

    it("loads the bundle only once across multiple toggles", async () => {
        const h = makeHarness();
        h.controller.setScene(scene(), "https://base/");
        await h.controller.toggle(); // → 3d
        await h.controller.toggle(); // → 2d
        await h.controller.toggle(); // → 3d again
        assert.strictEqual(h.loadCount(), 1);
        assert.strictEqual(h.mount.children.length, 1, "view reused");
    });

    it("creates a single view when toggled twice during a slow load", async () => {
        // Reproduces the race the controller guards against: two switches to
        // 3D before the lazy bundle load resolves must not mount two views.
        const button = document.createElement("button");
        const mount = document.createElement("div");
        let release!: () => void;
        const gate = new Promise<void>((r) => (release = r));
        let loads = 0;
        let defined = false;

        const controller = new SpatialToggleController({
            toggleButton: button,
            mount,
            twoDStage: document.createElement("div"),
            bundleSrc: "/media/webview/spatial.js",
            nonce: null,
            effects: {
                isDefined: () => defined,
                loadBundle: async () => {
                    loads += 1;
                    await gate; // stay pending until released
                    defined = true;
                },
                whenDefined: async () => undefined,
                createView: () => document.createElement("div") as never,
            },
        });
        controller.setScene(scene(), "https://base/");

        const a = controller.setMode("3d");
        const b = controller.setMode("3d");
        release();
        await Promise.all([a, b]);

        assert.strictEqual(loads, 1, "bundle loaded once");
        assert.strictEqual(
            mount.children.length,
            1,
            "exactly one view mounted",
        );
    });

    it("clicking the toggle button flips the mode", async () => {
        const h = makeHarness();
        h.controller.setScene(scene(), "https://base/");
        h.button.click();
        // The click handler runs toggle() asynchronously (await chain through
        // the fake bundle load); let the task queue drain before asserting.
        await new Promise((r) => setTimeout(r, 0));
        assert.strictEqual(h.controller.currentMode, "3d");
    });

    it("applies a late scene update while already in 3D", async () => {
        const h = makeHarness();
        h.controller.setScene(scene(), "https://base/");
        await h.controller.setMode("3d");
        const view = h.mount.children[0] as HTMLElement & { scene?: unknown };
        const first = view.scene;
        h.controller.setScene(scene(), "https://other/");
        assert.notStrictEqual(view.scene, first, "view scene re-applied");
    });

    it("throws on 3D entry when no bundle source is configured", async () => {
        const button = document.createElement("button");
        const controller = new SpatialToggleController({
            toggleButton: button,
            mount: document.createElement("div"),
            twoDStage: document.createElement("div"),
            bundleSrc: null,
            nonce: null,
            effects: {
                isDefined: () => false,
                loadBundle: async () => {},
                whenDefined: async () => undefined,
                createView: () => document.createElement("div") as never,
            },
        });
        await assert.rejects(() => controller.setMode("3d"), /bundle source/);
    });
});
