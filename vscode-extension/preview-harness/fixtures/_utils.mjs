// Shared helpers for the preview-harness fixture generators. Each
// `*.gen.mjs` file emits a JSON document with the same shape:
// `setPreviews` + `updateImage` + (optional) `updateDataProducts` +
// `actions`. The shell of the PNG encoder, preview-card stub, and
// fixture wrapper repeats between fixtures, so we factor it here.
// Per-fixture generators stay focused on the data-product payload
// they're demonstrating.

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

/**
 * Encode an RGB pixel buffer as PNG, base64 (no `data:` prefix —
 * `paintCardCapture` prepends the data URL itself).
 */
export function encodePng(width, height, pixels) {
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

export function inRect(x, y, r) {
    return x >= r.left && x < r.right && y >= r.top && y < r.bottom;
}

export function rectBounds(r) {
    return `${r.left},${r.top},${r.right},${r.bottom}`;
}

/**
 * Build a stylised 360×600 mobile mock with header / two buttons /
 * footer regions. Returns the geometry + the encoded PNG so each
 * fixture can place data-product bounds against shared coords.
 */
export function buildMobileMock() {
    const W = 360;
    const H = 600;
    const HEADER = { left: 0, top: 0, right: W, bottom: 80 };
    const BTN1 = { left: 40, top: 240, right: 160, bottom: 296 };
    const BTN2 = { left: 200, top: 240, right: 320, bottom: 296 };
    const FOOTER = { left: 0, top: 520, right: W, bottom: H };
    const ROOT = { left: 0, top: 0, right: W, bottom: H };

    const pixels = new Uint8Array(W * H * 3);
    for (let y = 0; y < H; y++) {
        for (let x = 0; x < W; x++) {
            let r = 245;
            let g = 246;
            let b = 250;
            if (inRect(x, y, HEADER)) {
                r = 60;
                g = 90;
                b = 168;
            } else if (inRect(x, y, BTN1)) {
                r = 14;
                g = 99;
                b = 156;
            } else if (inRect(x, y, BTN2)) {
                r = 220;
                g = 220;
                b = 220;
            } else if (inRect(x, y, FOOTER)) {
                r = 200;
                g = 200;
                b = 200;
            }
            const i = (y * W + x) * 3;
            pixels[i] = r;
            pixels[i + 1] = g;
            pixels[i + 2] = b;
        }
    }
    return {
        W,
        H,
        HEADER,
        BTN1,
        BTN2,
        FOOTER,
        ROOT,
        png: encodePng(W, H, pixels),
    };
}

/**
 * Build a focused-card descriptor + a hidden sibling so focus mode
 * has a card to switch into. The sibling stays as a skeleton — its
 * `updateImage` isn't included, and focus-mode layout means it
 * never paints anyway.
 */
export function buildPreviewPair({ focusId, width, height, fnName, file }) {
    const params = {
        name: null,
        device: null,
        widthDp: width,
        heightDp: height,
        fontScale: 1.0,
        showSystemUi: false,
        showBackground: true,
        backgroundColor: 0,
        uiMode: 0,
        locale: null,
        group: null,
    };
    const focused = {
        id: focusId,
        functionName: fnName,
        className: file.replace(/\.kt$/, "Kt"),
        sourceFile: file,
        params,
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: `renders/${focusId}.png`,
                label: "",
            },
        ],
    };
    const sibling = {
        ...focused,
        id: focusId + "Sibling",
        functionName: fnName + "Sibling",
        captures: [
            {
                ...focused.captures[0],
                renderOutput: `renders/${focusId}Sibling.png`,
            },
        ],
    };
    return { focused, sibling };
}

/**
 * The `dataset` block every fixture sets on `<preview-app>` so the
 * panel boots with the bundle UI gated on.
 */
export const EARLY_FEATURES_DATASET = {
    earlyFeatures: "true",
    minimalMode: "false",
};

/**
 * Helper that returns the action object that focuses a card via its
 * `.card-focus-btn` handle (clicking the image would toggle
 * interactive mode instead).
 */
export function focusAction(previewId) {
    return {
        click: `.preview-card[data-preview-id="${previewId}"] .card-focus-btn`,
    };
}

/**
 * Activate a bundle chip — keyed by `data-bundle` per
 * `BundleChipBar.renderChip`.
 */
export function activateBundleAction(bundleId) {
    return { click: `bundle-chip-bar button[data-bundle="${bundleId}"]` };
}

/**
 * Assertion sugar: build an `expectedPosts` entry that matches a
 * `setDataExtensionEnabled` call where the wire `kinds` array
 * contains [kind]. Bundle activation batches every default-ON kind
 * into a single post (see `BundleController.activate` /
 * `handleSetDataExtensionEnabled`), so the contract matcher has to
 * look inside the `kinds` array rather than equality-check a
 * singular `kind` field — the helper hides that with a `$includes`
 * marker the runner understands.
 */
export function expectSetDataExtension(previewId, kind, enabled) {
    return {
        command: "setDataExtensionEnabled",
        previewId,
        kinds: { $includes: kind },
        enabled,
    };
}

/**
 * Assertion sugar for `forbiddenPosts`: assert that [kind] was
 * never included in any enable-side `setDataExtensionEnabled`
 * post — i.e. the chip activation did not subscribe a default-OFF
 * kind. Uses the same `$includes` marker as `expectSetDataExtension`
 * so it survives the batched-kinds wire shape.
 */
export function forbidSetDataExtensionEnabled(previewId, kind) {
    return {
        command: "setDataExtensionEnabled",
        previewId,
        kinds: { $includes: kind },
        enabled: true,
    };
}
