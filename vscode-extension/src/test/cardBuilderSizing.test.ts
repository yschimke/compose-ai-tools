// Regression test for the "tall scroll preview is long from cache, then
// rescales once generated" bug.
//
// `applyRelativeSizing` resolves each card via
// `getElementById("preview-" + sanitizeId(id))` and only then stamps the
// `--aspect-ratio` custom property the CSS uses to pin a tall
// `@ScrollingPreview(LONG)`/`GIF` image into the device-sized slot. The
// `id` used to be set in `populatePreviewCard`, which runs from the
// `<preview-card>` Lit shell's deferred `firstUpdated()` microtask — so on
// the FIRST `setPreviews` (the cache preload) the freshly-built card had no
// `id` yet, `getElementById` missed it, `--aspect-ratio` was never stamped,
// and the tall image rendered at its natural height (long). A later render
// reseeded the now-id'd card and it finally rescaled.
//
// The fix stamps `card.id` synchronously in `buildPreviewCard` alongside the
// other layout-/filter-critical attributes. These tests pin both halves:
// the synchronous `id`, and that `applyRelativeSizing` can size a
// freshly-built card before any microtask runs.

import * as assert from "assert";
import { buildPreviewCard } from "../webview/preview/cardBuilder";
import type { CardBuilderConfig } from "../webview/preview/cardBuilder";
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

// buildPreviewCard only reads `p` synchronously; the imperative population
// that consumes `config` runs from the Lit shell's deferred `firstUpdated`.
// A cast stub is enough for the synchronous path under test.
const configStub = {} as unknown as CardBuilderConfig;

afterEach(() => {
    document.body.innerHTML = "";
});

describe("buildPreviewCard synchronous id stamping", () => {
    it("stamps the card id synchronously (before firstUpdated runs)", () => {
        const p = preview("com.example.Tall", { widthDp: 412, heightDp: 800 });
        const card = buildPreviewCard(p, configStub);
        // No microtask has run — the id must already be present so the
        // synchronous applyRelativeSizing in handleSetPreviews can find it.
        assert.strictEqual(card.id, "preview-" + sanitizeId(p.id));
        assert.strictEqual(card.dataset.previewId, p.id);
    });

    it("lets applyRelativeSizing size a freshly-built, not-yet-populated card", () => {
        const p = preview("com.example.Scroll", {
            widthDp: 412,
            heightDp: 800,
        });
        const card = buildPreviewCard(p, configStub);
        document.body.appendChild(card);

        // Mirrors handleSetPreviews: renderPreviews inserts the card, then
        // applyRelativeSizing runs synchronously — before any deferred
        // populate microtask. Pre-fix this missed the card and left
        // --aspect-ratio unset, so a tall scroll image rendered long.
        applyRelativeSizing([p]);

        assert.strictEqual(
            card.style.getPropertyValue("--aspect-ratio"),
            "412 / 800",
        );
        assert.strictEqual(card.style.getPropertyValue("--width-dp"), "412");
    });
});
