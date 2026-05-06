import * as assert from "assert";
import { applyRelativeSizing } from "../webview/preview/relativeSizing";
import { sanitizeId } from "../webview/preview/cardData";
import type { Capture, PreviewInfo, PreviewParams } from "../types";

const baseCapture: Capture = {
    advanceTimeMillis: null,
    scroll: null,
    renderOutput: "x.png",
};

const baseParams: PreviewParams = {
    name: null,
    device: null,
    widthDp: null,
    heightDp: null,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

function preview(
    id: string,
    overrides: Partial<PreviewParams> = {},
): PreviewInfo {
    return {
        id,
        functionName: "MyPreview",
        className: "com.example.PreviewsKt",
        sourceFile: null,
        params: { ...baseParams, ...overrides },
        captures: [baseCapture],
    };
}

/** Build the matching `<div id="preview-...">` cards in document.body and
 *  return them keyed by previewId so tests can assert on each. */
function setupCards(
    previews: readonly PreviewInfo[],
): Map<string, HTMLElement> {
    document.body.innerHTML = "";
    const cards = new Map<string, HTMLElement>();
    for (const p of previews) {
        const card = document.createElement("div");
        card.id = "preview-" + sanitizeId(p.id);
        document.body.appendChild(card);
        cards.set(p.id, card);
    }
    return cards;
}

afterEach(() => {
    document.body.innerHTML = "";
});

describe("applyRelativeSizing", () => {
    it("sets --size-ratio and --aspect-ratio per card from widthDp/heightDp", () => {
        const previews = [
            preview("com.example.A", { widthDp: 100, heightDp: 200 }),
            preview("com.example.B", { widthDp: 200, heightDp: 400 }),
        ];
        const cards = setupCards(previews);
        applyRelativeSizing(previews);

        const a = cards.get("com.example.A")!;
        // 100 / max(100,200) = 0.5
        assert.strictEqual(a.style.getPropertyValue("--size-ratio"), "0.5000");
        assert.strictEqual(
            a.style.getPropertyValue("--aspect-ratio"),
            "100 / 200",
        );

        const b = cards.get("com.example.B")!;
        // 200 / 200 = 1
        assert.strictEqual(b.style.getPropertyValue("--size-ratio"), "1.0000");
        assert.strictEqual(
            b.style.getPropertyValue("--aspect-ratio"),
            "200 / 400",
        );
    });

    it("fixes --size-ratio to 4 decimal places", () => {
        const previews = [
            preview("com.example.C", { widthDp: 1, heightDp: 1 }),
            preview("com.example.D", { widthDp: 7, heightDp: 7 }),
        ];
        setupCards(previews);
        applyRelativeSizing(previews);
        const c = document.getElementById("preview-com_example_C")!;
        // 1/7 = 0.142857... → rounded to 4 dp = 0.1429
        assert.strictEqual(c.style.getPropertyValue("--size-ratio"), "0.1429");
    });

    it("removes both CSS vars when widthDp / heightDp are missing", () => {
        const previews = [
            preview("com.example.E"), // no width/height
        ];
        const cards = setupCards(previews);
        // Pre-populate to verify removal actually happens.
        cards.get("com.example.E")!.style.setProperty("--size-ratio", "0.5");
        cards
            .get("com.example.E")!
            .style.setProperty("--aspect-ratio", "100 / 200");
        applyRelativeSizing(previews);
        const e = cards.get("com.example.E")!;
        assert.strictEqual(e.style.getPropertyValue("--size-ratio"), "");
        assert.strictEqual(e.style.getPropertyValue("--aspect-ratio"), "");
    });

    it("removes vars from a card whose width is set but height isn't (and vice versa)", () => {
        // Defensive: if only one dimension survives a metadata swap, neither
        // var should be set (the CSS expects both to compute correctly).
        const previews = [
            preview("com.example.F", { widthDp: 100, heightDp: null }),
            preview("com.example.G", { widthDp: null, heightDp: 200 }),
        ];
        setupCards(previews);
        applyRelativeSizing(previews);
        const f = document.getElementById("preview-com_example_F")!;
        const g = document.getElementById("preview-com_example_G")!;
        assert.strictEqual(f.style.getPropertyValue("--size-ratio"), "");
        assert.strictEqual(g.style.getPropertyValue("--size-ratio"), "");
    });

    it("leaves the maxW computation tolerant of zero / negative widths", () => {
        // Filter is `(w) => w > 0` — zero / negative are dropped before max.
        const previews = [
            preview("com.example.H", { widthDp: 0, heightDp: 0 }),
            preview("com.example.I", { widthDp: 100, heightDp: 200 }),
        ];
        setupCards(previews);
        applyRelativeSizing(previews);
        const i = document.getElementById("preview-com_example_I")!;
        // I has widthDp=100, maxW=100, ratio = 1.0
        assert.strictEqual(i.style.getPropertyValue("--size-ratio"), "1.0000");
        const h = document.getElementById("preview-com_example_H")!;
        // H widthDp=0 → fails `if (w && h && maxW > 0)` → no vars set
        assert.strictEqual(h.style.getPropertyValue("--size-ratio"), "");
    });

    it("skips silently when a manifest entry has no matching DOM card", () => {
        // Exercising the `if (!card) continue;` guard.
        const previews = [
            preview("com.example.J", { widthDp: 100, heightDp: 100 }),
            preview("com.example.K", { widthDp: 200, heightDp: 200 }),
        ];
        // Only stub up J's card; K has no DOM presence.
        const cards = setupCards([previews[0]]);
        // Should not throw on the missing K.
        assert.doesNotThrow(() => applyRelativeSizing(previews));
        // J was processed — its --size-ratio is set against maxW = 200
        // (maxW comes from the manifest, not the DOM).
        const j = cards.get("com.example.J")!;
        assert.strictEqual(j.style.getPropertyValue("--size-ratio"), "0.5000");
    });

    it("is idempotent — calling twice produces the same final state", () => {
        const previews = [
            preview("com.example.L", { widthDp: 100, heightDp: 200 }),
        ];
        setupCards(previews);
        applyRelativeSizing(previews);
        applyRelativeSizing(previews);
        const l = document.getElementById("preview-com_example_L")!;
        assert.strictEqual(l.style.getPropertyValue("--size-ratio"), "1.0000");
        assert.strictEqual(
            l.style.getPropertyValue("--aspect-ratio"),
            "100 / 200",
        );
    });
});
