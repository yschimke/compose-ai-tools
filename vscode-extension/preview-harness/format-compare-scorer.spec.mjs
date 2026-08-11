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
const RECORDED_MENU = JSON.parse(
    readFileSync(
        resolve(here, "fixtures/menu-dropdown-annotations.recorded.json"),
        "utf8",
    ),
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

    test("edge tolerance preserves position and still tolerates raster shifts", async ({ page }) => {
        const { missingRepeated, shiftedEdges } = await scorePairs(page);
        expect(missingRepeated.percent).toBeLessThan(99.5);
        expect(shiftedEdges.percent).toBeGreaterThan(missingRepeated.percent);
        expect(shiftedEdges.percent).toBeGreaterThan(95);
    });
});

test.describe("format-compare · annotation correspondence", () => {
    test("reduces the recorded menu-dropdown report from 94 vs 7 to seven shared elements", async ({
        page,
    }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const result = await page.evaluate((payload) => {
            const matched = window.ComposePreviewCompare.matchAnnotationItems(
                payload.reference,
                payload.actual,
            );
            return {
                referenceBefore: payload.reference.length,
                actualBefore: payload.actual.length,
                reference: matched.reference,
                actual: matched.actual,
            };
        }, RECORDED_MENU);

        expect(RECORDED_MENU.source).toContain("menu-dropdown__ideal__default__light");
        expect(result.referenceBefore).toBe(94);
        expect(result.actualBefore).toBe(7);
        expect(result.reference).toHaveLength(7);
        expect(result.actual).toHaveLength(7);
        expect(result.reference.map((item) => item.comparisonOrdinal)).toEqual([
            1, 2, 3, 4, 5, 6, 7,
        ]);
        expect(result.actual.map((item) => item.comparisonOrdinal)).toEqual([
            1, 2, 3, 4, 5, 6, 7,
        ]);
        expect(result.reference.slice(1).map((item) => item.role)).toEqual([
            "Menu-item 01 - First",
            "Menu-item 02",
            "Menu-item 03",
            "Menu-item 04",
            "Menu-item 05",
            // The render's last visible row aligns with Figma's last item. Figma's item 06 is
            // lower/off-viewport in this recorded layout despite appearing earlier in traversal.
            "Menu-item 12 - Last",
        ]);
    });

    test("pairs the shallow render tree with the same positioned Figma elements", async ({
        page,
    }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const matched = await page.evaluate(() => {
            const b = (x, y, width, height) => ({ x, y, width, height });
            const layout = (role, bounds, label = "spacing") => ({
                kind: "layout",
                bounds,
                label,
                ...(role ? { role } : {}),
            });
            const reference = [layout(undefined, b(0, 0, 603, 847), "r 16px")];
            // A Figma menu item has several nested layout nodes. Only the outer item corresponds
            // to the single shallow row exposed by Compose semantics.
            for (let i = 0; i < 6; i++) {
                const y = 6 + i * 139;
                reference.push(layout(`Menu-item ${i + 1}`, b(0, y, 603, 139)));
                reference.push(layout("Content", b(12, y + 6, 580, 128)));
                reference.push(layout("State Layer", b(12, y + 6, 580, 128)));
                reference.push(
                    layout("Leading element", b(46, y + 40, 58, 58)),
                );
                reference.push(layout("Icon", b(46, y + 40, 58, 58)));
            }
            const actual = [layout(undefined, b(29, 18, 546, 767), "r 16dp")];
            for (let i = 0; i < 6; i++) {
                actual.push(
                    layout(
                        undefined,
                        b(29, 23 + i * 126, 546, 126),
                        "pad 0dp/12dp",
                    ),
                );
            }
            return window.ComposePreviewCompare.matchAnnotationItems(
                reference,
                actual,
            );
        });

        expect(matched.reference).toHaveLength(7);
        expect(matched.actual).toHaveLength(7);
        expect(matched.reference.slice(1).map((item) => item.role)).toEqual([
            "Menu-item 1",
            "Menu-item 2",
            "Menu-item 3",
            "Menu-item 4",
            "Menu-item 5",
            "Menu-item 6",
        ]);
        expect(matched.reference.map((item) => item.comparisonOrdinal)).toEqual(
            [1, 2, 3, 4, 5, 6, 7],
        );
        expect(matched.actual.map((item) => item.comparisonOrdinal)).toEqual([
            1, 2, 3, 4, 5, 6, 7,
        ]);
    });

    test("drops render-only elements after the Figma inventory is exhausted", async ({
        page,
    }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const counts = await page.evaluate(() => {
            const item = (y) => ({
                kind: "layout",
                bounds: { x: 0, y, width: 100, height: 20 },
                label: "row",
            });
            const matched = window.ComposePreviewCompare.matchAnnotationItems(
                [item(0), item(20)],
                [item(0), item(20), item(40), item(60)],
            );
            return {
                reference: matched.reference.length,
                actual: matched.actual.length,
            };
        });
        expect(counts).toEqual({ reference: 2, actual: 2 });
    });

    test("retains extra typography usages so local token overrides remain visible", async ({
        page,
    }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const counts = await page.evaluate(() => {
            const item = (y, weight) => ({
                kind: "typography",
                bounds: { x: 0, y, width: 100, height: 20 },
                label: `bodyLarge wght ${weight}`,
                detail: { token: "bodyLarge", fontWeight: weight },
            });
            const matched = window.ComposePreviewCompare.matchAnnotationItems(
                [item(0, 400), item(20, 400)],
                [item(0, 400), item(20, 400), item(40, 700)],
            );
            return {
                reference: matched.reference.length,
                actual: matched.actual.length,
                weights: matched.actual.map((entry) => entry.detail.fontWeight),
            };
        });
        expect(counts).toEqual({
            reference: 2,
            actual: 3,
            weights: [400, 400, 700],
        });
    });

    test("chooses the globally minimal annotation pairing", async ({ page }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const roles = await page.evaluate(() => {
            const item = (x, role) => ({
                kind: "layout",
                bounds: { x, y: 0, width: 2, height: 2 },
                role,
                label: "spacing",
            });
            const matched = window.ComposePreviewCompare.matchAnnotationItems(
                [item(9, "reference-10"), item(19, "reference-20")],
                [item(15, "candidate-16"), item(18, "candidate-19")],
            );
            return matched.actual.map((entry) => entry.role);
        });
        expect(roles).toEqual(["candidate-16", "candidate-19"]);
    });

    test("normalizes suffix units when labels break geometric ties", async ({ page }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const labels = await page.evaluate(() => {
            const item = (label) => ({
                kind: "layout",
                bounds: { x: 0, y: 0, width: 20, height: 20 },
                label,
            });
            const matched = window.ComposePreviewCompare.matchAnnotationItems(
                [item("r 16px"), item("r 24px")],
                [item("r 24dp"), item("r 16dp")],
            );
            return matched.actual.map((entry) => entry.label);
        });
        expect(labels).toEqual(["r 16dp", "r 24dp"]);
    });

    test("retains reference-only typography usages", async ({ page }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const result = await page.evaluate(() => {
            const item = (y, token) => ({
                kind: "typography",
                bounds: { x: 0, y, width: 100, height: 20 },
                label: token,
                detail: { token },
            });
            return window.ComposePreviewCompare.matchAnnotationItems(
                [item(0, "bodyLarge"), item(20, "labelSmall")],
                [item(0, "bodyLarge")],
            );
        });
        expect(result.reference).toHaveLength(2);
        expect(result.actual).toHaveLength(1);
    });

    test("groups and pairs typography by resolved metric identity", async ({ page }) => {
        await page.goto("/preview-harness/index.html");
        await page.addScriptTag({ content: SCORER });
        const result = await page.evaluate(() => {
            const item = (label, detail) => ({
                kind: "typography",
                bounds: { x: 0, y: 0, width: 100, height: 60 },
                label,
                detail,
            });
            const groups = window.ComposePreviewCompare.groupTypography([
                item("body", { token: "body", fontSize: "14.0sp", lineHeight: "20.0sp" }),
                item("title", { token: "title", fontSize: "16.0sp", lineHeight: "24.0sp" }),
                item("file", { fontFamily: "/fonts/Roboto-Medium.ttf" }),
                item("compact", { token: "body", fontVariationSettings: '"wdth" 75' }),
                item("wide", { token: "body", fontVariationSettings: '"wdth" 100' }),
                item("label-only-a", {}),
                item("label-only-b", {}),
            ]);
            const reference = window.ComposePreviewCompare.groupTypography([
                item("regular", { token: "body", fontWeight: 400 }),
            ]);
            const actual = window.ComposePreviewCompare.groupTypography([
                item("bold", { token: "body", fontWeight: 700 }),
                item("regular", { token: "body", fontWeight: 400 }),
            ]);
            const paired = window.ComposePreviewCompare.pairTypography(reference, actual);
            return {
                keys: groups.map((group) => group.key),
                families: groups.map((group) => group.spec.family),
                sizes: groups.map((group) => window.ComposePreviewCompare.typographyValue(group.spec, "size")),
                matchedWeight: paired[0].actual.spec.weight,
            };
        });
        expect(result.keys).toHaveLength(7);
        expect(result.families).toContain("Roboto");
        expect(result.sizes).toContain("14sp");
        expect(result.matchedWeight).toBe(400);
    });
});
