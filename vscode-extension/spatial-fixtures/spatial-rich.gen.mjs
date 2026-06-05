// Generator for the `spatial-rich` SpatialScene fixture. Run with:
//   node vscode-extension/spatial-fixtures/spatial-rich.gen.mjs
//
// The canonical contract fixture (preview-harness/fixtures/spatial-scene) is two coplanar panels —
// perfect for the wire format, but it doesn't exercise rotation, orbiter affordances, or a coloured
// environment. This fixture does: angled side panels, two edge-anchored orbiters, and a backdrop
// colour, so the viewer's pose/quaternion mapping and orbiter handling have something to show.
// Emitted against the real contract in src/webview/shared/spatialScene.ts.
//
// Panel textures are rendered as real HTML/CSS surfaces (see panel-render.mjs) with headless
// Chromium, so each face shows actual text + Material cards/controls — and the `spatial-semantics`
// preview's wireframe overlays land on the elements they describe (shared layout in
// panel-content.mjs). Regenerating therefore needs a browser: Playwright's bundled Chromium, or one
// pointed at by `HARNESS_CHROMIUM` (same override the preview-harness snapshot honours).

import { mkdirSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";
import { PANEL_CONTENT } from "./panel-content.mjs";
import { panelHtml } from "./panel-render.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "spatial-rich");
const panelsDir = join(outDir, "panels");

// ~2 px/dp — crisp text without bloating the committed PNGs.
const DENSITY = 2;

// --- quaternion helpers ----------------------------------------------------

const yaw = (deg) => {
    const a = (deg * Math.PI) / 360;
    return { x: 0, y: Math.sin(a), z: 0, w: Math.cos(a) };
};
const pitch = (deg) => {
    const a = (deg * Math.PI) / 360;
    return { x: Math.sin(a), y: 0, z: 0, w: Math.cos(a) };
};
const identity = { x: 0, y: 0, z: 0, w: 1 };

// --- the scene -------------------------------------------------------------

const panels = [
    {
        id: "now-playing",
        label: "Now Playing",
        translation: { x: 0, y: 340, z: -120 },
        rotation: pitch(8),
        sizeDp: { width: 560, height: 180 },
        rgb: [103, 80, 164],
    },
    {
        id: "album-art",
        label: "Album Art",
        translation: { x: 0, y: -40, z: 0 },
        rotation: identity,
        sizeDp: { width: 460, height: 460 },
        rgb: [33, 120, 200],
    },
    {
        id: "queue",
        label: "Up Next",
        translation: { x: -520, y: 40, z: 160 },
        rotation: yaw(32),
        sizeDp: { width: 300, height: 520 },
        rgb: [0, 130, 120],
    },
    {
        id: "lyrics",
        label: "Lyrics",
        translation: { x: 520, y: 40, z: 160 },
        rotation: yaw(-32),
        sizeDp: { width: 300, height: 520 },
        rgb: [150, 60, 70],
    },
];

const orbiters = [
    {
        id: "transport",
        label: "Transport",
        edge: "bottom",
        translation: { x: 0, y: -340, z: 80 },
        rotation: pitch(-18),
        sizeDp: { width: 560, height: 96 },
        rgb: [48, 56, 68],
    },
    {
        id: "volume",
        label: "Volume",
        edge: "end",
        translation: { x: 360, y: -40, z: 40 },
        rotation: yaw(-20),
        sizeDp: { width: 80, height: 320 },
        rgb: [96, 110, 124],
    },
];

// --- texture rendering -----------------------------------------------------

/** Locate a Chromium: explicit override, then any installed pw-browsers build, then the default. */
function resolveChromium() {
    if (process.env.HARNESS_CHROMIUM) return process.env.HARNESS_CHROMIUM;
    const root = "/opt/pw-browsers";
    if (existsSync(root)) {
        const dir = readdirSync(root)
            .filter((d) => d.startsWith("chromium-"))
            .sort()
            .pop();
        if (dir) {
            const exe = join(root, dir, "chrome-linux", "chrome");
            if (existsSync(exe)) return exe;
        }
    }
    try {
        return chromium.executablePath();
    } catch {
        return undefined;
    }
}

async function renderTexture(browser, item) {
    const w = Math.round(item.sizeDp.width * DENSITY);
    const h = Math.round(item.sizeDp.height * DENSITY);
    const html = panelHtml({
        baseRgb: item.rgb,
        sizeDp: item.sizeDp,
        widgets: PANEL_CONTENT[item.id]?.widgets ?? [],
        density: DENSITY,
    });
    const page = await browser.newPage({
        viewport: { width: w, height: h },
        deviceScaleFactor: 1,
    });
    await page.setContent(html, { waitUntil: "load" });
    const buf = await page.screenshot({
        type: "png",
        clip: { x: 0, y: 0, width: w, height: h },
    });
    await page.close();
    writeFileSync(join(outDir, `panels/${item.id}.png`), buf);
}

mkdirSync(panelsDir, { recursive: true });

const browser = await chromium.launch({
    executablePath: resolveChromium(),
    args: ["--no-sandbox", "--force-color-profile=srgb"],
});
try {
    for (const item of [...panels, ...orbiters]) {
        await renderTexture(browser, item);
    }
} finally {
    await browser.close();
}

const scene = {
    version: 1,
    units: "dp",
    previewId: "spatial-fixtures.spatial-rich",
    camera: {
        kind: "orbit",
        target: { x: 0, y: 0, z: 0 },
        distance: 1700,
        yawDeg: 0,
        pitchDeg: -8,
    },
    panels: panels.map((p) => ({
        id: p.id,
        label: p.label,
        poseInRoot: { translation: p.translation, rotation: p.rotation },
        sizeDp: p.sizeDp,
        texture: `panels/${p.id}.png`,
        parentId: null,
    })),
    orbiters: orbiters.map((o) => ({
        id: o.id,
        label: o.label,
        edge: o.edge,
        poseInRoot: { translation: o.translation, rotation: o.rotation },
        sizeDp: o.sizeDp,
        texture: `panels/${o.id}.png`,
    })),
    environment: { kind: "color", color: "#101014" },
};

writeFileSync(
    join(outDir, "scene.json"),
    JSON.stringify(scene, null, 2) + "\n",
);

console.log(
    `wrote ${outDir}/scene.json + ${panels.length} panels + ${orbiters.length} orbiters`,
);
