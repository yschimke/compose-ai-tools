// Per-card bundle overlay paint (#1054 follow-up). Pins the
// stamp / clear cycle for the per-bundle `<box-overlay>` layer that
// `cardBundleOverlay.ts` mounts inside `.image-container` so each
// bundle's tinted boxes appear on the focused card without
// clobbering siblings from other bundles.
//
// History-diff is the first consumer; the same helper backs future
// migrations (a11y, text-overflow). Tests target the helper directly
// rather than going through `main.ts` so the focused-card lookup
// stays mockable via `document.body.innerHTML` fixtures.

import * as assert from "assert";
import {
    GRID_PAINT_CARD_CAP,
    clearBundleBoxes,
    findCardElement,
    getVisiblePreviewIds,
    paintBundleBoxes,
    paintBundleBoxesEverywhere,
} from "../webview/preview/cardBundleOverlay";
import { sanitizeId } from "../webview/preview/cardData";
// Import for side-effect: registers `<box-overlay>` with
// `customElements.define`. Without this the createElement returns a
// plain HTMLElement and `setBoxes` is undefined.
import "../webview/preview/components/BoxOverlay";
import type {
    BoxOverlay,
    OverlayBox,
} from "../webview/preview/components/BoxOverlay";

function buildCard(previewId: string): HTMLElement {
    const card = document.createElement("div");
    card.className = "preview-card";
    card.id = "preview-" + sanitizeId(previewId);
    card.dataset.previewId = previewId;
    const container = document.createElement("div");
    container.className = "image-container";
    const img = document.createElement("img");
    // happy-dom doesn't decode bytes, so seed naturalWidth/Height
    // via the shape downstream tests reuse — `Object.defineProperty`
    // is the supported escape hatch.
    Object.defineProperty(img, "naturalWidth", { value: 200 });
    Object.defineProperty(img, "naturalHeight", { value: 100 });
    Object.defineProperty(img, "complete", { value: true });
    container.appendChild(img);
    card.appendChild(container);
    document.body.appendChild(card);
    return card;
}

function box(
    id: string,
    level: "error" | "warning" | "info" = "info",
): OverlayBox {
    return {
        id,
        bounds: { left: 0, top: 0, right: 50, bottom: 25 },
        level,
    };
}

describe("cardBundleOverlay", () => {
    beforeEach(() => {
        document.body.innerHTML = "";
    });
    afterEach(() => {
        document.body.innerHTML = "";
    });

    it("findCardElement resolves the card by its sanitized id", () => {
        const card = buildCard("com.example.PreviewKt.Sample");
        assert.strictEqual(
            findCardElement("com.example.PreviewKt.Sample"),
            card,
        );
        assert.strictEqual(findCardElement("missing"), null);
    });

    it("paintBundleBoxes mounts a `<box-overlay>` keyed on the bundle id", () => {
        const card = buildCard("p:1");
        paintBundleBoxes(card, "history", [box("region-0"), box("region-1")]);
        const overlays = card.querySelectorAll<BoxOverlay>(
            "box-overlay[data-bundle='history']",
        );
        assert.strictEqual(overlays.length, 1, "exactly one history layer");
        assert.ok(
            overlays[0].classList.contains("bundle-card-overlay"),
            "shared CSS class so the layer sits at z-index above the image",
        );
    });

    it("re-paints the same layer in place rather than stacking new mounts", () => {
        const card = buildCard("p:1");
        paintBundleBoxes(card, "history", [box("a")]);
        paintBundleBoxes(card, "history", [box("b"), box("c")]);
        const overlays = card.querySelectorAll(
            "box-overlay[data-bundle='history']",
        );
        assert.strictEqual(overlays.length, 1);
    });

    it("paints two distinct layers when two different bundles paint", () => {
        const card = buildCard("p:1");
        paintBundleBoxes(card, "history", [box("region-0")]);
        paintBundleBoxes(card, "a11y", [box("a11y-0")]);
        assert.strictEqual(
            card.querySelectorAll("box-overlay[data-bundle='history']").length,
            1,
        );
        assert.strictEqual(
            card.querySelectorAll("box-overlay[data-bundle='a11y']").length,
            1,
        );
    });

    it("clearBundleBoxes(card, id) removes only that bundle's layer", () => {
        const card = buildCard("p:1");
        paintBundleBoxes(card, "history", [box("region-0")]);
        paintBundleBoxes(card, "a11y", [box("a11y-0")]);
        clearBundleBoxes(card, "history");
        assert.strictEqual(
            card.querySelectorAll("box-overlay[data-bundle='history']").length,
            0,
        );
        assert.strictEqual(
            card.querySelectorAll("box-overlay[data-bundle='a11y']").length,
            1,
            "other bundles' layers must survive the targeted teardown",
        );
    });

    it("clearBundleBoxes(null, id) removes the bundle's layer from every card", () => {
        const cardA = buildCard("p:a");
        const cardB = buildCard("p:b");
        paintBundleBoxes(cardA, "history", [box("ra")]);
        paintBundleBoxes(cardB, "history", [box("rb")]);
        clearBundleBoxes(null, "history");
        assert.strictEqual(
            document.querySelectorAll("box-overlay[data-bundle='history']")
                .length,
            0,
        );
    });

    it("paintBundleBoxes with empty boxes clears the layer in place (re-add later still works)", () => {
        const card = buildCard("p:1");
        paintBundleBoxes(card, "history", [box("a")]);
        paintBundleBoxes(card, "history", []);
        // The empty-set path keeps the wrapper mounted (so a follow-up
        // refresh reuses it), but `<box-overlay>.setBoxes([])` makes
        // it produce zero `.overlay-box` children.
        const layer = card.querySelector("box-overlay[data-bundle='history']");
        assert.ok(layer, "empty paint should not unmount the layer");
        // Subsequent non-empty refresh repopulates the same node.
        paintBundleBoxes(card, "history", [box("b"), box("c")]);
        assert.strictEqual(
            card.querySelectorAll("box-overlay[data-bundle='history']").length,
            1,
            "still one layer after the empty-then-full cycle",
        );
    });

    it("no-ops when the card has no `.image-container`", () => {
        const card = document.createElement("div");
        card.className = "preview-card";
        document.body.appendChild(card);
        assert.doesNotThrow(() =>
            paintBundleBoxes(card, "history", [box("a")]),
        );
        assert.strictEqual(card.querySelectorAll("box-overlay").length, 0);
    });

    describe("deferred-load paint (img not yet decoded)", () => {
        // Build a card whose `<img>` is *not* yet complete so
        // `paintBundleBoxes` takes the deferred-load branch. Returns
        // the helpers needed to flip the natural dims in and fire
        // `load` once the test is ready.
        function buildPendingCard(previewId: string): {
            card: HTMLElement;
            img: HTMLImageElement;
            settle: (width: number, height: number) => void;
        } {
            const card = document.createElement("div");
            card.className = "preview-card";
            card.id = "preview-" + sanitizeId(previewId);
            card.dataset.previewId = previewId;
            const container = document.createElement("div");
            container.className = "image-container";
            const img = document.createElement("img");
            Object.defineProperty(img, "naturalWidth", {
                configurable: true,
                value: 0,
            });
            Object.defineProperty(img, "naturalHeight", {
                configurable: true,
                value: 0,
            });
            Object.defineProperty(img, "complete", {
                configurable: true,
                value: false,
            });
            container.appendChild(img);
            card.appendChild(container);
            document.body.appendChild(card);
            return {
                card,
                img,
                settle(width: number, height: number) {
                    Object.defineProperty(img, "naturalWidth", {
                        configurable: true,
                        value: width,
                    });
                    Object.defineProperty(img, "naturalHeight", {
                        configurable: true,
                        value: height,
                    });
                    Object.defineProperty(img, "complete", {
                        configurable: true,
                        value: true,
                    });
                    img.dispatchEvent(new Event("load"));
                },
            };
        }

        it("two pending bundles on the same <img> both receive setNaturalSize on load (#1104 regression)", () => {
            const { card, img, settle } = buildPendingCard("p:1");
            paintBundleBoxes(card, "history", [box("h-0")]);
            paintBundleBoxes(card, "a11y", [box("a-0")]);
            // The pending map should hold both bundles, JSON-encoded
            // in a single dataset field.
            const pending = JSON.parse(
                img.dataset.bundleOverlayPaint ?? "{}",
            ) as Record<string, true>;
            assert.deepStrictEqual(pending, { history: true, a11y: true });
            // Re-painting either bundle before load fires must not
            // stack a second listener — semantics inherited from the
            // pre-refactor per-bundle guard.
            paintBundleBoxes(card, "history", [box("h-0"), box("h-1")]);
            paintBundleBoxes(card, "a11y", [box("a-0"), box("a-1")]);
            settle(400, 200);
            // Both layers must have learnt the natural size — that's
            // what the `<box-overlay>` needs to render boxes at all.
            const historyLayer = card.querySelector<BoxOverlay>(
                "box-overlay[data-bundle='history']",
            );
            const a11yLayer = card.querySelector<BoxOverlay>(
                "box-overlay[data-bundle='a11y']",
            );
            assert.ok(historyLayer);
            assert.ok(a11yLayer);
            // Force a synchronous render so the natural-size state
            // becomes observable via the rendered DOM.
            (
                historyLayer as unknown as { performUpdate(): void }
            ).performUpdate();
            (a11yLayer as unknown as { performUpdate(): void }).performUpdate();
            assert.ok(
                historyLayer.querySelector(".box-overlay"),
                "history layer renders boxes once natural size lands",
            );
            assert.ok(
                a11yLayer.querySelector(".box-overlay"),
                "a11y layer renders boxes once natural size lands",
            );
            // The pending map should be empty once both loads have
            // settled — verified by the dataset attribute being
            // removed entirely.
            assert.strictEqual(img.dataset.bundleOverlayPaint, undefined);
        });

        it("treats a malformed dataset value as an empty map (defensive parse)", () => {
            const { card, img, settle } = buildPendingCard("p:1");
            // Seed an invalid JSON value the way a stray devtools poke
            // or external script could. The next paint must recover
            // rather than throw.
            img.dataset.bundleOverlayPaint = "{not json";
            assert.doesNotThrow(() =>
                paintBundleBoxes(card, "history", [box("h-0")]),
            );
            // After the defensive overwrite, the map should be the
            // single-entry shape we wrote — proving the parse failure
            // didn't poison the state.
            assert.deepStrictEqual(
                JSON.parse(img.dataset.bundleOverlayPaint ?? "{}"),
                { history: true },
            );
            settle(400, 200);
            assert.strictEqual(img.dataset.bundleOverlayPaint, undefined);
        });
    });

    describe("getVisiblePreviewIds", () => {
        it("returns the preview ids of every mounted card in DOM order", () => {
            buildCard("p:a");
            buildCard("p:b");
            buildCard("p:c");
            assert.deepStrictEqual(getVisiblePreviewIds(), [
                "p:a",
                "p:b",
                "p:c",
            ]);
        });

        it("skips cards with the [hidden] attribute", () => {
            buildCard("p:a");
            const bCard = buildCard("p:b");
            bCard.setAttribute("hidden", "");
            buildCard("p:c");
            assert.deepStrictEqual(getVisiblePreviewIds(), ["p:a", "p:c"]);
        });

        it("skips cards with inline display:none", () => {
            buildCard("p:a");
            const bCard = buildCard("p:b");
            bCard.style.display = "none";
            buildCard("p:c");
            assert.deepStrictEqual(getVisiblePreviewIds(), ["p:a", "p:c"]);
        });

        it("returns an empty array when no cards are mounted", () => {
            assert.deepStrictEqual(getVisiblePreviewIds(), []);
        });
    });

    describe("paintBundleBoxesEverywhere", () => {
        it("paints each visible card with its own per-card boxes", () => {
            const cardA = buildCard("p:a");
            const cardB = buildCard("p:b");
            const perCard = new Map<string, ReadonlyArray<OverlayBox>>([
                ["p:a", [box("a-0")]],
                ["p:b", [box("b-0"), box("b-1")]],
            ]);
            paintBundleBoxesEverywhere("history", perCard);
            const layerA = cardA.querySelector<BoxOverlay>(
                "box-overlay[data-bundle='history']",
            );
            const layerB = cardB.querySelector<BoxOverlay>(
                "box-overlay[data-bundle='history']",
            );
            assert.ok(layerA, "card A should have a history layer mounted");
            assert.ok(layerB, "card B should have a history layer mounted");
        });

        it("clears in place on cards absent from the map", () => {
            const cardA = buildCard("p:a");
            const cardB = buildCard("p:b");
            // Seed both cards with prior boxes so we can prove cardB
            // gets the empty-set call when the new map omits it.
            paintBundleBoxes(cardA, "history", [box("a-old")]);
            paintBundleBoxes(cardB, "history", [box("b-old")]);
            const perCard = new Map<string, ReadonlyArray<OverlayBox>>([
                ["p:a", [box("a-new")]],
            ]);
            paintBundleBoxesEverywhere("history", perCard);
            // Both layers should still exist (empty-set keeps the
            // wrapper mounted), and we should not have stacked any
            // new wrappers on either card.
            assert.strictEqual(
                cardA.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                1,
            );
            assert.strictEqual(
                cardB.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                1,
            );
        });

        it("skips hidden cards entirely", () => {
            const cardA = buildCard("p:a");
            const cardB = buildCard("p:b");
            cardB.setAttribute("hidden", "");
            const perCard = new Map<string, ReadonlyArray<OverlayBox>>([
                ["p:a", [box("a-0")]],
                ["p:b", [box("b-0")]],
            ]);
            paintBundleBoxesEverywhere("history", perCard);
            assert.strictEqual(
                cardA.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                1,
                "visible card paints",
            );
            assert.strictEqual(
                cardB.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                0,
                "hidden card is skipped — no layer mounted",
            );
        });

        it("aborts with a console.warn when the visible-card count exceeds the cap", () => {
            const cap = GRID_PAINT_CARD_CAP;
            for (let i = 0; i < cap + 1; i++) buildCard("p:" + i);
            const originalWarn = console.warn;
            let warnCount = 0;
            let warnMessage = "";
            console.warn = (...args: unknown[]) => {
                warnCount++;
                warnMessage = String(args[0] ?? "");
            };
            try {
                const perCard = new Map<string, ReadonlyArray<OverlayBox>>();
                for (let i = 0; i < cap + 1; i++) {
                    perCard.set("p:" + i, [box("b-" + i)]);
                }
                paintBundleBoxesEverywhere("history", perCard);
            } finally {
                console.warn = originalWarn;
            }
            assert.strictEqual(warnCount, 1, "warn fired exactly once");
            assert.match(
                warnMessage,
                /paintBundleBoxesEverywhere/,
                "warn names the helper for grep-ability",
            );
            assert.strictEqual(
                document.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                0,
                "no layers stamped — over-cap path is a hard skip",
            );
        });

        it("paints right up to the cap (boundary)", () => {
            const cap = GRID_PAINT_CARD_CAP;
            for (let i = 0; i < cap; i++) buildCard("p:" + i);
            const perCard = new Map<string, ReadonlyArray<OverlayBox>>();
            for (let i = 0; i < cap; i++) {
                perCard.set("p:" + i, [box("b-" + i)]);
            }
            paintBundleBoxesEverywhere("history", perCard);
            assert.strictEqual(
                document.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                cap,
                "all cap cards paint",
            );
        });

        it("clearBundleBoxes(null, id) after grid paint wipes every layer", () => {
            buildCard("p:a");
            buildCard("p:b");
            const perCard = new Map<string, ReadonlyArray<OverlayBox>>([
                ["p:a", [box("a-0")]],
                ["p:b", [box("b-0")]],
            ]);
            paintBundleBoxesEverywhere("history", perCard);
            assert.strictEqual(
                document.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                2,
            );
            clearBundleBoxes(null, "history");
            assert.strictEqual(
                document.querySelectorAll("box-overlay[data-bundle='history']")
                    .length,
                0,
            );
        });
    });
});
