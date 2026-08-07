// Behavioural tests for the `/compare` page's design-reference scorer.
//
// The rest of this harness screenshots pages. This spec instead drives
// `window.ComposePreviewCompare.scoreImageUrls` directly over synthetic images, because the
// property under test is numeric and the interesting cases are ones no committed fixture
// currently contains.
//
// What it pins down: a design reference and a rendered preview are framed differently by
// construction. The preview carries whatever its `@Preview` scaffold added — `showBackground`'s
// opaque sheet, a `padding()` inset, a fixed-height container the content does not fill — and the
// reference is usually cropped to the artboard. The scorer normalises both to their content box so
// the number answers "does this component look like its design?" rather than "were these two
// exported at the same size?".
//
// The images are drawn in-page and passed as data URIs: no fixture files, and a data URI does not
// taint the canvas, so the scorer can sample pixels exactly as it does for a served artifact.

import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const SCORER = readFileSync(
    resolve(
        here,
        "../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
    ),
    "utf8",
);

/** Load the real asset into a page with an origin, then score the four synthetic pairs. */
async function scorePairs(page) {
    await page.goto("/preview-harness/index.html");
    await page.addScriptTag({ content: SCORER });
    return page.evaluate(async () => {
        function make(width, height, draw) {
            const canvas = document.createElement("canvas");
            canvas.width = width;
            canvas.height = height;
            draw(canvas.getContext("2d"), width, height);
            return canvas.toDataURL("image/png");
        }
        // A stand-in component: a rounded card with a title bar and two body rows.
        function card(ctx, x, y, w, h) {
            ctx.fillStyle = "#e8f1ee";
            ctx.beginPath();
            ctx.roundRect(x, y, w, h, h * 0.12);
            ctx.fill();
            ctx.fillStyle = "#123";
            ctx.fillRect(x + w * 0.08, y + h * 0.15, w * 0.5, h * 0.14);
            ctx.fillStyle = "#567";
            ctx.fillRect(x + w * 0.08, y + h * 0.45, w * 0.7, h * 0.09);
            ctx.fillRect(x + w * 0.08, y + h * 0.65, w * 0.6, h * 0.09);
        }

        // Reference bleeds to the artboard; the preview pads the same component onto an opaque
        // sheet and lands on a different canvas size entirely.
        const bleedReference = make(400, 240, (c, w, h) => card(c, 0, 0, w, h));
        const scaffoldedPreview = make(480, 300, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            card(c, 40, 30, w - 80, h - 60);
        });

        // Both near-empty: one small mark each, plus a faint full-bleed hairline on the reference
        // that makes its content box the whole canvas while the preview's is a few percent of it.
        const emptyReference = make(400, 320, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            c.strokeStyle = "#eee";
            c.strokeRect(0.5, 0.5, w - 1, h - 1);
            c.fillStyle = "#111";
            c.fillRect(4, 6, 90, 16);
        });
        const emptyPreview = make(400, 320, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            c.fillStyle = "#111";
            c.fillRect(20, 22, 90, 16);
        });

        // Different content at comparable framing — the control for "normalisation inflates
        // everything".
        const unrelatedPreview = make(400, 240, (c, w, h) => {
            c.fillStyle = "#3b1d1d";
            c.fillRect(0, 0, w, h);
            c.fillStyle = "#ffd";
            c.beginPath();
            c.arc(w / 2, h / 2, h * 0.35, 0, Math.PI * 2);
            c.fill();
        });

        // An OPAQUE reference cropped tight to a card, so the card's own fill reaches (0, 0) — the
        // usual shape of a design-tool export. Its corner is artwork, not a backdrop. Denser cards
        // used to score worse, because boxing "everything that isn't the corner colour" reduces to
        // the text bounding box and then stretches that against the whole card in the preview.
        const opaqueBledCard = make(400, 240, (c, w, h) => {
            c.fillStyle = "#e8f1ee";
            c.fillRect(0, 0, w, h);
            card(c, 0, 0, w, h);
        });

        const score = window.ComposePreviewCompare.scoreImageUrls;
        return {
            scaffolded: await score(bleedReference, scaffoldedPreview),
            identical: await score(bleedReference, bleedReference),
            nearEmpty: await score(emptyReference, emptyPreview),
            unrelated: await score(bleedReference, unrelatedPreview),
            opaqueBled: await score(opaqueBledCard, scaffoldedPreview),
        };
    });
}

test.describe("format-compare · design reference scorer", () => {
    test("a scaffolded preview still matches its bled reference", async ({ page }) => {
        const { scaffolded, identical } = await scorePairs(page);
        // Differing canvas sizes used to abort the comparison outright ("image dimensions differ"),
        // and equal-sized-but-differently-framed pairs scored like unrelated art.
        expect(scaffolded.percent).toBeGreaterThan(90);
        expect(scaffolded.geometry).toBeLessThan(2);
        expect(identical.percent).toBeCloseTo(100, 5);
    });

    test("a near-empty pair falls back to whole-canvas instead of magnifying one mark", async ({
        page,
    }) => {
        const { nearEmpty } = await scorePairs(page);
        // Cropping here would stretch a 90x16 mark across the whole comparison and report a total
        // mismatch for two captures that plainly agree.
        expect(nearEmpty.percent).toBeGreaterThan(90);
        // The framing difference is real and still reported, just kept out of the match score.
        expect(nearEmpty.geometry).toBeGreaterThan(50);
    });

    test("an opaque reference whose artwork reaches the corner is not stripped", async ({
        page,
    }) => {
        const { opaqueBled } = await scorePairs(page);
        // "A uniform border around an interior region" is the same picture whether the border is a
        // scaffold sheet or a card bleeding to the artboard edge, so an opaque corner is only taken
        // as a backdrop when it is a sheet `showBackground` actually paints. Treating this card's
        // own fill as the backdrop boxed only its text and scored the pair at 8%.
        expect(opaqueBled.percent).toBeGreaterThan(85);
    });

    test("unrelated content still reads as a mismatch", async ({ page }) => {
        const { unrelated } = await scorePairs(page);
        expect(unrelated.percent).toBeLessThan(75);
    });
});
