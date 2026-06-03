// Generator for the `spatial-rich` SpatialScene fixture. Run with:
//   node vscode-extension/spatial-fixtures/spatial-rich.gen.mjs
//
// The canonical contract fixture (preview-harness/fixtures/spatial-scene) is
// two coplanar panels — perfect for the wire format, but it doesn't exercise
// rotation, orbiter affordances, or a coloured environment. This fixture does:
// angled side panels, two edge-anchored orbiters, and a backdrop colour, so the
// viewer's pose/quaternion mapping and orbiter handling have something to show.
// Emitted against the real contract in src/webview/shared/spatialScene.ts.

import { deflateSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "spatial-rich");
const panelsDir = join(outDir, "panels");

// --- minimal PNG encoder (RGB, no deps) ----------------------------------

function crc32(buf) {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++)
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c >>> 0;
    }
    let c = 0xffffffff;
    for (const b of buf) c = t[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, payload) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(payload.length, 0);
    const tb = Buffer.from(type, "ascii");
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([tb, payload])), 0);
    return Buffer.concat([len, tb, payload, crc]);
}

function encodePng(w, h, rgb) {
    const raw = Buffer.alloc(h * (1 + w * 3));
    for (let y = 0; y < h; y++) {
        raw[y * (1 + w * 3)] = 0; // scanline filter: None
        Buffer.from(rgb.subarray(y * w * 3, (y + 1) * w * 3)).copy(
            raw,
            y * (1 + w * 3) + 1,
        );
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(w, 0);
    ihdr.writeUInt32BE(h, 4);
    ihdr[8] = 8; // bit depth
    ihdr[9] = 2; // colour type RGB
    return Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk("IHDR", ihdr),
        chunk("IDAT", deflateSync(raw)),
        chunk("IEND", Buffer.alloc(0)),
    ]);
}

// A colour field with a lighter top band + thin border, so texture orientation
// (top vs bottom) is obvious in the viewer.
function panelTexture(w, h, [r, g, b]) {
    const rgb = Buffer.alloc(w * h * 3);
    const band = Math.floor(h * 0.22);
    const border = 6;
    for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
            const i = (y * w + x) * 3;
            const edge =
                x < border || y < border || x >= w - border || y >= h - border;
            const k = edge ? 0.45 : y < band ? 1.35 : 1.0;
            rgb[i] = Math.min(255, Math.round(r * k));
            rgb[i + 1] = Math.min(255, Math.round(g * k));
            rgb[i + 2] = Math.min(255, Math.round(b * k));
        }
    }
    return encodePng(w, h, rgb);
}

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
        rgb: [33, 150, 243],
    },
    {
        id: "queue",
        label: "Up Next",
        translation: { x: -520, y: 40, z: 160 },
        rotation: yaw(32),
        sizeDp: { width: 300, height: 520 },
        rgb: [0, 150, 136],
    },
    {
        id: "lyrics",
        label: "Lyrics",
        translation: { x: 520, y: 40, z: 160 },
        rotation: yaw(-32),
        sizeDp: { width: 300, height: 520 },
        rgb: [244, 67, 54],
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
        rgb: [55, 71, 79],
    },
    {
        id: "volume",
        label: "Volume",
        edge: "end",
        translation: { x: 360, y: -40, z: 40 },
        rotation: yaw(-20),
        sizeDp: { width: 80, height: 320 },
        rgb: [120, 144, 156],
    },
];

mkdirSync(panelsDir, { recursive: true });

function writeTexture(item) {
    const file = `panels/${item.id}.png`;
    writeFileSync(
        join(outDir, file),
        // ~0.5 px/dp — crisp enough to read, light enough for a committed fixture.
        panelTexture(
            Math.round(item.sizeDp.width * 0.5),
            Math.round(item.sizeDp.height * 0.5),
            item.rgb,
        ),
    );
    return file;
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
        texture: writeTexture(p),
        parentId: null,
    })),
    orbiters: orbiters.map((o) => ({
        id: o.id,
        label: o.label,
        edge: o.edge,
        poseInRoot: { translation: o.translation, rotation: o.rotation },
        sizeDp: o.sizeDp,
        texture: writeTexture(o),
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
