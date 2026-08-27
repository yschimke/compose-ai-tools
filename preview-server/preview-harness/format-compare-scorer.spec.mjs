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
//
// The annotation-correspondence tests that used to live here have moved to
// `cli/serve-web/test/annotateMatch.test.ts` and `annotateTypography.test.ts`. They ran in Chromium
// only because the code was inside an IIFE; it is arithmetic over boxes and needs no browser. What
// is left needs one — it samples real canvas pixels.

import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const SCORER = readFileSync(
    resolve(
        here,
        "../../cli/serve/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
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

        // Both near-empty: the SAME small mark in both, plus a faint full-bleed hairline on the
        // reference that makes its content box the whole canvas while the preview's is a few
        // percent of it. The mark used to sit 16px away in the reference, back when the score was
        // averaged over the canvas and a blank page could absorb that; the pair still read as a
        // match. It no longer can — a shifted mark on an otherwise empty page IS the whole picture
        // disagreeing — and the shift was never what this fixture is about. What it is about is the
        // crop: box the preview to its few percent and stretch that across the reference and two
        // agreeing captures report a total mismatch, which is what the fallback exists to stop.
        const emptyReference = make(400, 320, (c, w, h) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, w, h);
            c.strokeStyle = "#eee";
            c.strokeRect(0.5, 0.5, w - 1, h - 1);
            c.fillStyle = "#111";
            c.fillRect(20, 22, 90, 16);
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

        // A missing repeated mark used to match its neighbour inside the unrestricted 11×11
        // luminance search and score as unchanged. A one-pixel edge shift remains tolerated.
        const repeatedReference = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(30, 10, 2, 40);
            c.fillRect(36, 10, 2, 40);
        });
        const missingRepeatedMark = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(30, 10, 2, 40);
        });
        const shiftedEdges = make(100, 60, (c) => {
            c.fillStyle = "#fff";
            c.fillRect(0, 0, 100, 60);
            c.fillStyle = "#000";
            c.fillRect(31, 10, 2, 40);
            c.fillRect(37, 10, 2, 40);
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
            missingRepeated: await score(repeatedReference, missingRepeatedMark),
            shiftedEdges: await score(repeatedReference, shiftedEdges),
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
        // mismatch for two captures that plainly agree — the fallback to whole-canvas is the only
        // reason this pair can score at all.
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

    test("edge tolerance preserves position and still tolerates raster shifts", async ({ page }) => {
        const { missingRepeated, shiftedEdges } = await scorePairs(page);
        expect(missingRepeated.percent).toBeLessThan(99.5);
        expect(shiftedEdges.percent).toBeGreaterThan(missingRepeated.percent);
        expect(shiftedEdges.percent).toBeGreaterThan(95);
    });
});

// The SVG lane's registration, which nothing else in this harness covers: every fixture SVG sits at
// `translate(0, 0)`, so a bug in reading that offset is invisible to the page snapshots. One did
// ship — the pattern accepted integers only, so a fractional placement did not match at all and the
// offset silently became the origin. Figma writes fractional positions routinely.
test.describe("format-compare · SVG lane registration", () => {
    /** The same card as a PNG at the origin, and as an SVG placed at (tx, ty) on its board. */
    async function scorePlacement(page, tx, ty) {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        return page.evaluate(
            async ([x, y]) => {
                const canvas = document.createElement("canvas");
                canvas.width = 200;
                canvas.height = 120;
                const ctx = canvas.getContext("2d");
                ctx.fillStyle = "#fff";
                ctx.fillRect(0, 0, 200, 120);
                ctx.fillStyle = "#2c5f4f";
                ctx.fillRect(20, 20, 160, 80);
                ctx.fillStyle = "#fff";
                ctx.fillRect(40, 40, 80, 16);
                const png = canvas.toDataURL("image/png");

                const svg =
                    '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120">' +
                    '<rect width="200" height="120" fill="#fff"/>' +
                    `<g transform="translate(${x}, ${y})">` +
                    '<rect x="20" y="20" width="160" height="80" fill="#2c5f4f"/>' +
                    '<rect x="40" y="40" width="80" height="16" fill="#fff"/>' +
                    "</g></svg>";

                const realFetch = window.fetch;
                window.fetch = async () => ({ ok: true, text: async () => svg });
                try {
                    return await window.ComposePreviewCompare.scoreSvgUrls(png, "/svg");
                } finally {
                    window.fetch = realFetch;
                }
            },
            [tx, ty],
        );
    }

    test("a fractionally-placed export registers as well as an integer one", async ({
        page,
    }) => {
        // The two must land within a point of each other. Before the fix the fractional case scored
        // roughly twenty points worse — not because the component differed, but because the whole
        // drawing was offset by the translate the scorer failed to read. That reads as "this
        // component is wrong", which is the most misleading answer this surface can give.
        const integer = await scorePlacement(page, 60, 30);
        const fractional = await scorePlacement(page, 60.5, 30.25);
        expect(fractional).toBeGreaterThan(integer - 1);
    });
});
