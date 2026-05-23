// Generator for `remotecompose-state.json`. Run with:
//   node preview-harness/fixtures/remotecompose-state.gen.mjs > preview-harness/fixtures/remotecompose-state.json
//
// Drives focus mode + the Remote Compose bundle so the rendered tab body
// shows the editable named-values table, the profile `<select>`, and
// the host-actions audit log. The card image is a stylised remote-
// compose watch face mock (220×220) so the focused preview visually
// matches the kind of document the data extension wraps — a small
// circular widget with a labeled button.

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

function encodePng(width, height, pixels) {
    const raw = Buffer.alloc(height * (1 + width * 3));
    for (let y = 0; y < height; y++) {
        let off = y * (1 + width * 3);
        raw[off++] = 0;
        for (let x = 0; x < width; x++) {
            const i = (y * width + x) * 3;
            raw[off++] = pixels[i];
            raw[off++] = pixels[i + 1];
            raw[off++] = pixels[i + 2];
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
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;
    ihdr[9] = 2;
    return Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk("IHDR", ihdr),
        chunk("IDAT", deflateSync(raw)),
        chunk("IEND", Buffer.alloc(0)),
    ]).toString("base64");
}

const W = 220;
const H = 220;
const CX = W / 2;
const CY = H / 2;
const R = 105;
const BTN = { left: 60, top: 132, right: 160, bottom: 168 };

const pixels = new Uint8Array(W * H * 3);
for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
        const dx = x - CX;
        const dy = y - CY;
        const d2 = dx * dx + dy * dy;
        let r = 16;
        let g = 18;
        let b = 24;
        if (d2 <= R * R) {
            // Inside the watch face circle — dark "AMOLED" background
            // with a thin ring.
            r = 24;
            g = 26;
            b = 36;
            const ring = Math.sqrt(d2);
            if (ring > R - 2) {
                r = 90;
                g = 100;
                b = 120;
            }
            // Tick marks every 30 degrees.
            const angle = Math.atan2(dy, dx);
            for (let i = 0; i < 12; i++) {
                const ta = (i * Math.PI) / 6;
                const da = Math.atan2(
                    Math.sin(angle - ta),
                    Math.cos(angle - ta),
                );
                if (Math.abs(da) < 0.02 && ring > R - 14 && ring < R - 4) {
                    r = 200;
                    g = 210;
                    b = 230;
                }
            }
            // The button rect (uses the daemon-seeded `seedColor`
            // named value at #3366FF; we draw the same colour here so
            // the panel's editable color cell and the rendered preview
            // visually agree).
            if (
                x >= BTN.left &&
                x < BTN.right &&
                y >= BTN.top &&
                y < BTN.bottom
            ) {
                r = 0x33;
                g = 0x66;
                b = 0xff;
            }
        }
        const idx = (y * W + x) * 3;
        pixels[idx] = r;
        pixels[idx + 1] = g;
        pixels[idx + 2] = b;
    }
}

const imageData = encodePng(W, H, pixels);

const PREVIEW_ID =
    "com.example.sampleremotecompose.PreviewsKt.RemoteButtonWithBorderPreview";

const fixture = {
    name: "remotecompose-state",
    description:
        "Focused card with the Remote Compose bundle active. The tab body renders three sections (profile selector, editable named-values table, host-actions audit log) driven by a single `compose/remotecompose` data product. Use this when iterating on the editable cells (profile `<select>`, typed inputs for float/dp/int/string/bool/color) or the host-action row layout.",
    dataset: {
        earlyFeatures: "true",
        minimalMode: "false",
    },
    messages: [
        {
            command: "setPreviews",
            moduleDir: "/workspace/samples/remotecompose",
            heavyStaleIds: [],
            previews: [
                {
                    id: PREVIEW_ID,
                    functionName: "RemoteButtonWithBorderPreview",
                    className:
                        "com.example.sampleremotecompose.PreviewsKt",
                    sourceFile: "Previews.kt",
                    params: {
                        name: null,
                        device: null,
                        widthDp: 220,
                        heightDp: 220,
                        fontScale: 1,
                        showSystemUi: false,
                        showBackground: true,
                        backgroundColor: 0,
                        uiMode: 0,
                        locale: null,
                        group: null,
                    },
                    captures: [
                        {
                            advanceTimeMillis: null,
                            scroll: null,
                            renderOutput:
                                "renders/com.example.sampleremotecompose.PreviewsKt.RemoteButtonWithBorderPreview.png",
                            label: "",
                        },
                    ],
                },
            ],
        },
        {
            command: "updateImage",
            previewId: PREVIEW_ID,
            captureIndex: 0,
            imageData,
        },
        {
            command: "updateDataProducts",
            previewId: PREVIEW_ID,
            dataProducts: [
                {
                    kind: "compose/remotecompose",
                    payload: {
                        profile: "androidx",
                        namedValues: {
                            seedColor: {
                                kind: "color",
                                argb: "#FF3366FF",
                            },
                            cornerRadius: {
                                kind: "dp",
                                value: 8.0,
                            },
                            opacity: {
                                kind: "float",
                                value: 0.85,
                            },
                            label: {
                                kind: "string",
                                value: "Bordered",
                            },
                            tapCount: {
                                kind: "int",
                                value: 3,
                            },
                            enabled: {
                                kind: "bool",
                                value: true,
                            },
                        },
                        hostActions: [
                            {
                                payload: "testAction",
                                handlerId: 1,
                                firedAtMillis: 1716470401000,
                            },
                            {
                                payload: "testAction",
                                handlerId: 1,
                                firedAtMillis: 1716470404500,
                            },
                            {
                                payload: "longPress",
                                handlerId: 2,
                                firedAtMillis: 1716470407200,
                            },
                        ],
                    },
                },
            ],
        },
    ],
    actions: [
        {
            click:
                `.preview-card[data-preview-id="${PREVIEW_ID}"] .card-focus-btn`,
        },
        {
            click: 'bundle-chip-bar button[data-bundle="remotecompose"]',
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
