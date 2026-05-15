// Generator for `a11y-findings.json`. Run with:
//   node preview-harness/fixtures/a11y-findings.gen.mjs > preview-harness/fixtures/a11y-findings.json
//
// Drives focus mode + the Accessibility bundle so the rendered card
// shows the `<box-overlay>` layer painted by `refreshA11yBundle`, plus
// the data tab with the hierarchy/findings table. Uses a 360×600
// stylised-mobile mock PNG so finding bounds line up with visible UI
// shapes (header, two buttons, footer) — useful when discussing the
// design treatment for ATF violations.

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

// 360×600 stylised mobile mock. Coordinates below match these regions.
const W = 360;
const H = 600;
const HEADER_H = 80;
const FOOTER_TOP = 520;
const BTN_Y = 240;
const BTN_H = 56;
const BTN1 = { left: 40, top: BTN_Y, right: 160, bottom: BTN_Y + BTN_H };
const BTN2 = { left: 200, top: BTN_Y, right: 320, bottom: BTN_Y + BTN_H };
const HEADER = { left: 0, top: 0, right: W, bottom: HEADER_H };
const FOOTER = { left: 0, top: FOOTER_TOP, right: W, bottom: H };
const ROOT = { left: 0, top: 0, right: W, bottom: H };

function inRect(x, y, r) {
    return x >= r.left && x < r.right && y >= r.top && y < r.bottom;
}

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
            // Visually-small primary button (too small for touch target).
            r = 14;
            g = 99;
            b = 156;
        } else if (inRect(x, y, BTN2)) {
            // Icon-only button (missing contentDescription).
            r = 220;
            g = 220;
            b = 220;
        } else if (inRect(x, y, FOOTER)) {
            // Footer text in low-contrast grey on light bg.
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

function bounds(r) {
    return `${r.left},${r.top},${r.right},${r.bottom}`;
}

const FOCUS_ID = "com.example.OnboardingKt.WelcomeScreenPreview";

const nodes = [
    {
        label: "Welcome screen",
        role: "container",
        states: [],
        // Top-level container — its own focus target so screen
        // readers can land on it.
        merged: true,
        boundsInScreen: bounds(ROOT),
    },
    {
        label: "Welcome to Compose AI Tools",
        role: "headline",
        states: ["focusable"],
        merged: true,
        boundsInScreen: bounds(HEADER),
    },
    {
        label: "Get started",
        role: "button",
        states: ["clickable", "focusable"],
        merged: true,
        boundsInScreen: bounds(BTN1),
    },
    {
        // Inner Text inside the "Get started" button — the button
        // owns the focus stop, so the child reads as merged:false
        // and renders indented in the bundle tab table.
        label: "Get started",
        role: "text",
        states: [],
        merged: false,
        boundsInScreen: bounds(BTN1),
    },
    {
        label: "",
        role: "button",
        states: ["clickable", "focusable"],
        merged: true,
        boundsInScreen: bounds(BTN2),
    },
    {
        label: "By continuing you agree to our terms",
        role: "text",
        states: [],
        merged: true,
        boundsInScreen: bounds(FOOTER),
    },
];

const findings = [
    {
        level: "ERROR",
        type: "SpeakableTextPresent",
        message: "Image-only button has no contentDescription.",
        viewDescription: "ImageButton",
        boundsInScreen: bounds(BTN2),
    },
    {
        level: "WARNING",
        type: "TouchTargetSize",
        message:
            "Touch target is 120×56dp. Material guidance recommends at least 48×48dp on each side; this clears height but the width / spacing leaves the tap area cramped on small screens.",
        viewDescription: "Button",
        boundsInScreen: bounds(BTN1),
    },
    {
        level: "WARNING",
        type: "TextContrast",
        message:
            "Footer text uses #C8C8C8 on #F5F6FA — contrast ratio 1.4:1, below the WCAG AA 4.5:1 threshold for body copy.",
        viewDescription: "TextView",
        boundsInScreen: bounds(FOOTER),
    },
];

const focused = {
    id: FOCUS_ID,
    functionName: "WelcomeScreenPreview",
    className: "com.example.OnboardingKt",
    sourceFile: "Onboarding.kt",
    params: {
        name: null,
        device: null,
        widthDp: W,
        heightDp: H,
        fontScale: 1.0,
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
            renderOutput: `renders/${FOCUS_ID}.png`,
            label: "",
        },
    ],
    a11yFindings: findings,
    a11yNodes: nodes,
};

const sibling = {
    id: "com.example.OnboardingKt.SettingsScreenPreview",
    functionName: "SettingsScreenPreview",
    className: "com.example.OnboardingKt",
    sourceFile: "Onboarding.kt",
    params: {
        name: null,
        device: null,
        widthDp: W,
        heightDp: H,
        fontScale: 1.0,
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
            renderOutput: `renders/${"com.example.OnboardingKt.SettingsScreenPreview"}.png`,
            label: "",
        },
    ],
};

const setPreviews = {
    command: "setPreviews",
    moduleDir: "/workspace/sample-android",
    heavyStaleIds: [],
    previews: [focused, sibling],
};

const fixture = {
    name: "a11y-findings",
    description:
        "Focused card with three Accessibility Test Framework findings (one ERROR, two WARNINGs) overlaid on a stylised mobile mock. Drives focus mode and activates the Accessibility bundle so the chip bar, data tab, and on-image box-overlay all paint.",
    dataset: {
        // `earlyFeatures` is the gate for the chip bar + data tabs.
        earlyFeatures: "true",
        autoEnableCheap: "false",
        collapseVariants: "false",
        minimalMode: "false",
        autoInject: "true",
    },
    messages: [
        setPreviews,
        {
            command: "updateImage",
            previewId: focused.id,
            captureIndex: 0,
            imageData: encodePng(W, H, pixels),
        },
        // The sibling stays as a skeleton — we want focus mode to take
        // over the layout so its image never needs to land.
    ],
    actions: [
        // Focus the WelcomeScreen card via its .card-focus-btn (the
        // explicit handle from `cardBuilder`). Clicking the image
        // would toggle interactive mode instead, which isn't what we
        // want here.
        {
            click: `.preview-card[data-preview-id="${focused.id}"] .card-focus-btn`,
        },
        // Activate the Accessibility bundle — chip is keyed by
        // `data-bundle="a11y"` per BundleChipBar.renderChip.
        {
            click: `bundle-chip-bar button[data-bundle="a11y"]`,
        },
        // Click the row for the ERROR finding (unlabelled image
        // button) so the snapshot exercises the row-click → detail
        // panel path. `data-legend-id` matches the bundle row's
        // overlay id; with the inner "Get started" text node now
        // inserted at index 3, the unlabelled-button row sits at
        // `a11y-4`.
        {
            click: `[data-bundle="a11y"] tr[data-legend-id="a11y-4"]`,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
