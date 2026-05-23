// Generator for `grid-default.json`. Run with:
//   node preview-harness/fixtures/grid-default.gen.mjs > preview-harness/fixtures/grid-default.json
//
// Emits a `setPreviews` plus per-preview `updateImage` so the panel
// renders with solid-color placeholder PNGs — enough to discuss
// layout, chrome, and typography without rebuilding the Android
// sample to get real captures.
//
// updateImage.imageData is RAW base64 (no `data:` prefix) — the
// webview's `paintCardCapture` prepends `data:<mime>;base64,` itself.

import { deflateSync } from "node:zlib";

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

function solidPngBase64(w, h, r, g, b) {
    const raw = Buffer.alloc(h * (1 + w * 3));
    for (let y = 0; y < h; y++) {
        let off = y * (1 + w * 3);
        raw[off++] = 0; // PNG scanline filter: None
        for (let x = 0; x < w; x++) {
            raw[off++] = r;
            raw[off++] = g;
            raw[off++] = b;
        }
    }
    function chunk(type, payload) {
        const len = Buffer.alloc(4);
        len.writeUInt32BE(payload.length, 0);
        const tb = Buffer.from(type, "ascii");
        const crc = Buffer.alloc(4);
        crc.writeUInt32BE(crc32(Buffer.concat([tb, payload])), 0);
        return Buffer.concat([len, tb, payload, crc]);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(w, 0);
    ihdr.writeUInt32BE(h, 4);
    ihdr[8] = 8; // bit depth
    ihdr[9] = 2; // colour type: RGB
    const png = Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk("IHDR", ihdr),
        chunk("IDAT", deflateSync(raw)),
        chunk("IEND", Buffer.alloc(0)),
    ]);
    return png.toString("base64");
}

// Mirrors the real preview functions in
// `samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt` so the
// rendered panel looks like a realistic grid for someone working on the
// CMP sample, not a `com.example` placeholder. The ids/class names match
// the canonical id shape `${className}.${functionName}_${name}` (or just
// `.${functionName}` when `@Preview` carries no `name`).
const previews = [
    {
        id: "com.example.samplecmp.PreviewsKt.RedBoxPreview_Red Box",
        fn: "RedBoxPreview",
        cls: "com.example.samplecmp.PreviewsKt",
        file: "Previews.kt",
        name: "Red Box",
        group: null,
        rgb: [244, 67, 54],
    },
    {
        id: "com.example.samplecmp.PreviewsKt.BlueBoxPreview_Blue Box",
        fn: "BlueBoxPreview",
        cls: "com.example.samplecmp.PreviewsKt",
        file: "Previews.kt",
        name: "Blue Box",
        group: null,
        rgb: [33, 150, 243],
    },
    {
        id: "com.example.samplecmp.PreviewsKt.AppPreview",
        fn: "AppPreview",
        cls: "com.example.samplecmp.PreviewsKt",
        file: "Previews.kt",
        name: null,
        group: null,
        rgb: [33, 33, 33],
    },
    {
        id: "com.example.samplecmp.PreviewsKt.WallpaperDemoPreview_Wallpaper Demo",
        fn: "WallpaperDemoPreview",
        cls: "com.example.samplecmp.PreviewsKt",
        file: "Previews.kt",
        name: "Wallpaper Demo",
        group: null,
        rgb: [103, 80, 164],
    },
];

const setPreviews = {
    command: "setPreviews",
    moduleDir: "/workspace/samples/cmp",
    heavyStaleIds: [],
    previews: previews.map((p) => ({
        id: p.id,
        functionName: p.fn,
        className: p.cls,
        sourceFile: p.file,
        params: {
            name: p.name,
            device: null,
            widthDp: 0,
            heightDp: 0,
            fontScale: 1.0,
            showSystemUi: false,
            showBackground: true,
            backgroundColor: 0,
            uiMode: p.uiMode ?? 0,
            locale: null,
            group: p.group,
        },
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: `renders/${p.id}.png`,
                label: "",
            },
        ],
    })),
};

const updates = previews.map((p) => ({
    command: "updateImage",
    previewId: p.id,
    captureIndex: 0,
    imageData: solidPngBase64(160, 200, p.rgb[0], p.rgb[1], p.rgb[2]),
}));

const fixture = {
    name: "grid-default",
    description:
        "Four-card grid: three color variants plus a greeting, after setPreviews + per-card updateImage.",
    dataset: {
        earlyFeatures: "false",
        minimalMode: "false",
    },
    messages: [setPreviews, ...updates],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
