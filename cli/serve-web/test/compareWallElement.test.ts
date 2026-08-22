// Behavioural contract for `<cp-compare-wall>`.
//
// The decisions are pinned next door — `compareWall.test.ts`. What only the element can answer is
// the wiring: that the scorer handle is read late enough to exist, that a lane switch cannot be
// overwritten by the previous lane's slower rows, that the published player wall replaces the
// client-rendered table wholesale rather than running behind it, and that the worst rows end up
// where the wall is read.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import "../src/components/CompareWall.js";

interface Scorer {
    calls: string[];
    /** The canvases `diffCanvases` was asked to paint, in the order the wall asked. */
    painted: HTMLCanvasElement[];
    /** The `maxSide` each normalisation was asked for — `undefined` means "the frame's own size". */
    bounds: Array<number | undefined>;
    settle(): void;
}

/**
 * `window.ComposePreviewCompare`, published LATE by default — which is what the real page does, and
 * the thing this element must not cache too early.
 */
function stubScorer(
    scores: Record<string, number>,
    options: {
        hold?: boolean;
        holdReference?: boolean;
        publish?: boolean;
        /** Dimensions the stubbed delta map is painted at, before the wall bounds it. */
        map?: { width: number; height: number };
    } = {},
): Scorer {
    const held: Array<() => void> = [];
    const state: Scorer = {
        calls: [],
        painted: [],
        bounds: [],
        settle: () => {
            for (const release of held.splice(0)) release();
        },
    };
    const api = {
        loadImage: async () => ({ naturalWidth: 10, naturalHeight: 10 }),
        scoreSvgUrls: async (png: string, svg: string) => {
            state.calls.push(svg);
            if (options.hold) await new Promise<void>((r) => held.push(r));
            const score = scores[svg];
            if (score === undefined) throw new Error("unavailable");
            return score;
        },
        scoreCanvas: async () => 50,
        scoreImageUrls: async (reference: string) => {
            state.calls.push(reference);
            return { percent: scores[reference] ?? 0, geometry: 3.5 };
        },
        // The reference lane goes through the normalise / diff / score composition, so the pair the
        // map is painted from and the pair the percentage is taken over are the same frames. The
        // stub keeps the score keyed off the reference URL, exactly as `scoreImageUrls` does.
        normaliseImageUrls: async (
            reference: string,
            candidate: string,
            maxSide?: number,
        ) => {
            state.calls.push(reference);
            state.bounds.push(maxSide);
            if (options.holdReference)
                await new Promise<void>((r) => held.push(r));
            return {
                reference: {},
                candidate: {},
                images: [{ src: reference }, { src: candidate }],
                width: 8,
                height: 8,
                geometry: 3.5,
            };
        },
        diffCanvases: (
            _reference: unknown,
            _candidate: unknown,
            into: HTMLCanvasElement,
        ) => {
            state.painted.push(into);
            into.width = options.map?.width ?? 8;
            into.height = options.map?.height ?? 8;
            return 12;
        },
        scoreImages: async (reference: { src?: string }) => ({
            percent: scores[reference.src ?? ""] ?? 0,
            geometry: 3.5,
        }),
    };
    if (options.publish !== false)
        (window as Record<string, unknown>).ComposePreviewCompare = api;
    return state;
}

/** One row, carrying the `<kind>-<variant>` artifacts the server writes. */
const rowHtml = (name: string, have: string[]) => `
  <tr class="cp-compare-row" data-label="${name}" data-hay="${name.toLowerCase()}"
      data-preview-ids="com.example.${name}Preview"
      ${have.map((h) => `data-${h}="/a/${name}-${h}"`).join(" ")}>
    <td><img class="cp-compare-png" alt=""></td>
    <td class="cp-compare-diff-cell"><canvas class="cp-compare-diff"></canvas></td>
    <td><img class="cp-compare-vector" alt=""><canvas class="cp-compare-rc" hidden></canvas></td>
    <td><span class="cp-compare-score"></span></td>
  </tr>`;

async function mount(
    options: {
        rows?: Array<{ name: string; have: string[] }>;
        available?: string;
        search?: string;
        lanes?: boolean;
    } = {},
): Promise<void> {
    const rows = options.rows ?? [
        { name: "Button", have: ["png-light", "svg-light"] },
        { name: "Card", have: ["png-light", "svg-light"] },
    ];
    document.body.innerHTML = `
      <cp-compare-wall></cp-compare-wall>
      <div id="cp-compare" data-default-format="svg" data-default-theme="light"
           data-theme-key="cp-compare-theme" ${options.available ?? 'data-has-svg="1"'}>
        <button data-compare-format="svg">SVG</button>
        <button data-compare-format="rc">RC</button>
        <button data-compare-format="reference">Reference</button>
        <button data-compare-theme="light">Light</button>
        <button data-compare-theme="dark">Dark</button>
        <input id="cp-compare-search" value="${options.search ?? ""}">
        <span id="cp-compare-count"></span>
        <div id="cp-compare-formats"><table><tbody>${rows.map((r) => rowHtml(r.name, r.have)).join("")}</tbody></table></div>
        <p id="cp-compare-empty" hidden></p>
        ${options.lanes ? '<div id="cp-rc-lanes"></div>' : ""}
      </div>`;
    await flush();
}

const settle = async () => {
    for (let i = 0; i < 12; i++) await flush();
};
const rowsOf = () =>
    Array.from(document.querySelectorAll<HTMLElement>(".cp-compare-row"));
const scoreTextOf = (name: string) =>
    document.querySelector(`[data-label="${name}"] .cp-compare-score`)
        ?.textContent;
const count = () => document.getElementById("cp-compare-count")?.textContent;

describe("<cp-compare-wall>", () => {
    beforeEach(() => {
        stubStorage();
        window.history.replaceState(null, "", "/compare");
    });
    afterEach(() => {
        resetDom();
        delete (window as Record<string, unknown>).ComposePreviewCompare;
        window.history.replaceState(null, "", "/");
    });

    it("scores each row and says so on the row", async () => {
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");
        assert.equal(scoreTextOf("Card"), "61.0%");
        assert.equal(count(), "2 comparisons");
    });

    it("bands the score so a wall can be triaged by colour", async () => {
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        const classOf = (name: string) =>
            document.querySelector(`[data-label="${name}"] .cp-compare-score`)
                ?.className;
        assert.ok(classOf("Button")?.includes("cp-compare-score--good"));
        assert.ok(classOf("Card")?.includes("cp-compare-score--bad"));
    });

    it("puts the WORST rows where the wall is read", async () => {
        // The page exists to find what is wrong; the rows that are wrong have to be on screen
        // without scrolling.
        stubScorer({ "/a/Button-svg-light": 93.4, "/a/Card-svg-light": 61 });
        await mount();
        await settle();
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Card", "Button"],
        );
    });

    it("leads with a row it could not score at all", async () => {
        // An unmeasurable pair outranks every measured one: it is the row nobody is looking at.
        stubScorer({ "/a/Button-svg-light": 20 });
        await mount();
        await settle();
        assert.equal(scoreTextOf("Card"), "unavailable");
        assert.deepEqual(
            rowsOf().map((r) => r.getAttribute("data-label")),
            ["Card", "Button"],
        );
    });

    it("reads the scorer LATE, so the page's script order cannot silence it", async () => {
        // The compare page emits the components bundle BEFORE `format-compare.js`. An element that
        // cached the handle when it upgraded would cache `null` and every row would read
        // "unavailable" — silently, on a page that otherwise looks like it is working.
        await mount();
        await flush();
        stubScorer({ "/a/Button-svg-light": 88, "/a/Card-svg-light": 88 });
        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "88.0%");
    });

    it("hides a row this format cannot pair, rather than scoring the wrong two pictures", async () => {
        stubScorer({ "/a/Button-svg-light": 90 });
        await mount({
            rows: [
                { name: "Button", have: ["png-light", "svg-light"] },
                { name: "Card", have: ["png-light"] },
            ],
        });
        await settle();
        assert.equal(
            document.querySelector<HTMLElement>('[data-label="Card"]')!.hidden,
            true,
        );
        assert.equal(count(), "1 comparison");
    });

    it("narrows on the search box, and says how many are left", async () => {
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "card";
        search.dispatchEvent(new Event("input"));
        await flush();
        assert.equal(count(), "1 comparison");
        assert.equal(
            document.querySelector<HTMLElement>('[data-label="Button"]')!
                .hidden,
            true,
        );
    });

    it("says so when the filter leaves nothing", async () => {
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "nothing-matches-this";
        search.dispatchEvent(new Event("input"));
        await flush();
        assert.equal(count(), "0 comparisons");
        assert.equal(
            document.getElementById("cp-compare-empty")!.hidden,
            false,
        );
    });

    it("hands the filter to the published wall and stops, rather than running behind it", async () => {
        // The lane wall owns its rows and every number it shows was computed offline. Leaving the
        // client-rendered table running would decode a document per preview for a table nobody can
        // see.
        const filtered: string[] = [];
        (window as Record<string, unknown>).cpRcLanes = {
            filter: (q: string) => filtered.push(q),
        };
        const scorer = stubScorer({ "/a/Button-svg-light": 90 });
        await mount({
            available: 'data-has-svg="1" data-has-rc="1"',
            lanes: true,
        });
        await settle();
        const before = scorer.calls.length;
        document
            .querySelector<HTMLElement>('[data-compare-format="rc"]')!
            .click();
        await settle();
        assert.equal(
            document.getElementById("cp-compare-formats")!.hidden,
            true,
        );
        assert.equal(document.getElementById("cp-rc-lanes")!.hidden, false);
        assert.equal(scorer.calls.length, before, "no rows scored behind it");
        assert.equal(filtered.length > 0, true);
        delete (window as Record<string, unknown>).cpRcLanes;
    });

    it("does not let a slow lane write its scores over a newer one's", async () => {
        // Every row decodes and scores two frames, so a switch mid-run leaves the old lane's
        // promises in flight. Without the generation counter they land on rows the visitor is now
        // looking at in a different format.
        const scorer = stubScorer(
            { "/a/Button-svg-light": 11, "/a/Card-svg-light": 11 },
            { hold: true },
        );
        await mount({ available: 'data-has-svg="1" data-has-reference="1"' });
        await flush();
        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await flush();
        scorer.settle();
        await settle();
        assert.notEqual(
            scoreTextOf("Button"),
            "11.0%",
            "the abandoned SVG run must not land",
        );
    });

    it("paints the middle diff map, and only in the reference lane", async () => {
        // The wall's reason to carry a third panel at all: the reference lane compares
        // independently-authored artwork, so "which pixels moved" is a real question there and a
        // meaningless one in the vector lanes, which export the render they are scored against.
        const scorer = stubScorer({ "/a/Button-reference-light": 80 });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        assert.deepEqual(scorer.painted, [], "the SVG lane has nothing to map");

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(scorer.painted.length, 1);
        assert.notEqual(
            scorer.painted[0],
            diff,
            "the scorer must paint its own canvas, never the row's",
        );
        assert.equal(diff.width, 8);
        assert.equal(scoreTextOf("Button"), "80.0%");
    });

    it("clears the map rather than leaving the last lane's magenta standing", async () => {
        // A stale delta map beside a freshly-scored row reads as a finding. It has to go the moment
        // the row is re-run, not once something else happens to repaint it.
        stubScorer({
            "/a/Button-reference-light": 80,
            "/a/Button-svg-light": 93.4,
        });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.width, 8);

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(diff.width, 0);
    });

    it("does not let an abandoned lane's map land on the row", async () => {
        // The rows of a lane the visitor has left are still in flight when the next lane starts a
        // second pass over the SAME elements. The score is already generation-guarded; the map has
        // to be too, or the abandoned run repaints a canvas the new run has finished with and the
        // row shows one lane's magenta beside the other's render and percentage. Blanking the canvas
        // at the top of a run cannot close this — the stale paint arrives after that.
        window.history.replaceState(null, "", "/compare?format=reference");
        const scorer = stubScorer(
            {
                "/a/Button-reference-light": 80,
                "/a/Button-svg-light": 93.4,
            },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await flush();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");

        // The reference run now finishes, into a lane nobody is looking at.
        scorer.settle();
        await settle();
        assert.equal(
            diff.width,
            0,
            "the abandoned run must not repaint the row",
        );
        assert.equal(scoreTextOf("Button"), "93.4%");
    });

    it("stops an abandoned chain before it resets the rows behind it", async () => {
        // Bumping the generation stops a stale run's RESULTS from landing; it does not stop the
        // chain, which keeps walking its remaining rows. Everything a row is reset to on the way to
        // its measurement — the vector's src, "comparing…", the blanked map — was written before the
        // only guard there was, so a stale chain arriving behind a finished one wiped rows the
        // visitor was already reading and then discarded the measurement that would have refilled
        // them. Those rows stayed blank for as long as the page was open.
        window.history.replaceState(null, "", "/compare?format=reference");
        const scorer = stubScorer(
            {
                "/a/Button-reference-light": 80,
                "/a/Card-reference-light": 60,
                "/a/Button-svg-light": 93.4,
                "/a/Card-svg-light": 88.1,
            },
            { holdReference: true },
        );
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
                {
                    name: "Card",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await flush();

        // The reference chain is parked on its FIRST row, so it has not reached Card yet.
        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.equal(scoreTextOf("Button"), "93.4%");
        assert.equal(scoreTextOf("Card"), "88.1%");

        // Released, the abandoned chain now walks on to Card — a row the visitor is reading.
        scorer.settle();
        await settle();
        assert.equal(scoreTextOf("Card"), "88.1%");
        assert.equal(
            document.querySelector<HTMLCanvasElement>(
                '[data-label="Card"] .cp-compare-diff',
            )!.width,
            0,
        );
    });

    it("bounds the map it keeps to what the column can draw", async () => {
        // A reference exported at full device resolution normalises to a frame far larger than the
        // 200px column that shows it, and the wall retains one per row rather than the detail page's
        // one. Kept at source size, a catalog of large captures is hundreds of megabytes of backing
        // store nobody can see — and a frame past the browser's canvas limit turns a row that used
        // to score into "unavailable".
        const scorer = stubScorer(
            { "/a/Button-reference-light": 80 },
            { map: { width: 1600, height: 2400 } },
        );
        window.history.replaceState(null, "", "/compare?format=reference");
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.height, 440);
        // Bounded, not squashed: a map drawn at the wrong proportion would misreport where the two
        // drawings disagree, which is the one thing this column exists to say.
        assert.equal(diff.width, Math.round((1600 / 2400) * 440));
        // And the bound is asked for UP FRONT, not applied to the finished map: normalising at the
        // frame's own size would hold three full-resolution buffers per row on the way to a picture
        // 200px wide, and a frame past the browser's canvas limit would fail there rather than
        // scoring — which is what this lane used to do before it drew anything.
        assert.deepEqual(scorer.bounds, [440]);
    });

    it("opens the Reference / Diff / Actual page from the map as well as the render", async () => {
        // Two panels of the same triptych; clicking the one that shows the problem should not be
        // the click that does nothing.
        stubScorer({ "/a/Button-reference-light": 80 });
        window.history.replaceState(null, "", "/compare?format=reference");
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: [
                        "png-light",
                        "reference-light",
                        "reference-detail-light",
                        "svg-light",
                    ],
                },
            ],
        });
        await settle();
        const diff = document.querySelector<HTMLCanvasElement>(
            '[data-label="Button"] .cp-compare-diff',
        )!;
        assert.equal(diff.title, "Open Reference / Diff / Actual");
        assert.equal(typeof diff.onclick, "function");
    });

    it("opens on the format a deep link names", async () => {
        window.history.replaceState(null, "", "/compare?format=reference");
        stubScorer({ "/a/Button-reference-light": 80 });
        await mount({
            available: 'data-has-svg="1" data-has-reference="1"',
            rows: [
                {
                    name: "Button",
                    have: ["png-light", "reference-light", "svg-light"],
                },
            ],
        });
        await settle();
        assert.equal(
            document
                .querySelector('[data-compare-format="reference"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
    });

    it("ignores a format this catalog publishes nothing for", async () => {
        // Not an empty table: that reads as "nothing matches your filter", which is a different and
        // wrong answer to a link naming a lane this catalog simply does not have.
        window.history.replaceState(null, "", "/compare?format=rc");
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        assert.equal(
            document
                .querySelector('[data-compare-format="svg"]')
                ?.getAttribute("aria-pressed"),
            "true",
        );
        assert.equal(count(), "2 comparisons");
    });

    it("gives a format pick its own history entry, and typing none", async () => {
        // The distinction the deleted source-text test was protecting. A discrete pick is a place
        // the visitor can go Back to; a keystroke is not, or Back walks the search box one letter at
        // a time. Both go through `cpUrlState`, which preserves the session keys — writing
        // `history.replaceState` here would drop them.
        const pushed: Array<Record<string, string>> = [];
        const replaced: Array<Record<string, string>> = [];
        (window as Record<string, unknown>).cpUrlState = {
            push: (v: Record<string, string>) => pushed.push(v),
            replace: (v: Record<string, string>) => replaced.push(v),
            onPop: () => () => {},
        };
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount({ available: 'data-has-svg="1" data-has-reference="1"' });
        await settle();

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        assert.deepEqual(pushed, [{ format: "reference" }]);

        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.deepEqual(pushed[1], { theme: "dark" });

        const search =
            document.querySelector<HTMLInputElement>("#cp-compare-search")!;
        search.value = "  card  ";
        search.dispatchEvent(new Event("input"));
        assert.deepEqual(replaced, [{ q: "card" }]);
        assert.equal(pushed.length, 2, "typing pushed nothing");
        delete (window as Record<string, unknown>).cpUrlState;
    });

    it("remembers the theme it was last compared in", async () => {
        const storage = stubStorage();
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();
        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.equal(storage.get("cp-compare-theme"), "dark");
    });

    it("repaints the page's chrome for the theme being compared, on a pick AND on Back", async () => {
        // Every pop path restores its theme by ASSIGNING the control's value, which fires no
        // `change` — so each one has to hand the restored choice over itself. Missing it left Back
        // from Dark to a Light entry re-rendering the preview light inside a page still pinned dark.
        const followed: string[] = [];
        let popHandler = () => {};
        (window as Record<string, unknown>).cpPageTheme = {
            follow: (t: string) => followed.push(t),
        };
        (window as Record<string, unknown>).cpUrlState = {
            push: () => {},
            replace: () => {},
            onPop: (fn: () => void) => {
                popHandler = fn;
                return () => {};
            },
        };
        stubScorer({ "/a/Button-svg-light": 90, "/a/Card-svg-light": 90 });
        await mount();
        await settle();

        document
            .querySelector<HTMLElement>('[data-compare-theme="dark"]')!
            .click();
        assert.deepEqual(followed, ["dark"], "a pick repaints the chrome");

        window.history.replaceState(null, "", "/compare?theme=light");
        popHandler();
        assert.deepEqual(
            followed,
            ["dark", "light"],
            "and so does the entry Back restores",
        );
        delete (window as Record<string, unknown>).cpPageTheme;
        delete (window as Record<string, unknown>).cpUrlState;
    });

    it("fixes the RC player's theme and fonts BEFORE it measures", async () => {
        // An ordering invariant, and every step earns its place. The artifact theme is an explicit
        // comparison input — inherit the OS's `prefers-color-scheme` and a light PNG gets scored
        // against a dark canvas. The first paint is what DISCOVERS the named font families, so
        // `fontsReady` is only meaningful after it, and the resolved glyphs only reach the canvas on
        // the repaint after that. Measure early and the lane reports the visitor's own `sans-serif`
        // against a PNG baked with Roboto: a permanent residual that reads as a layout defect.
        const order: string[] = [];
        (window as Record<string, unknown>).RC = {
            RcdPlayer: class {
                setTheme(t: string) {
                    order.push(`setTheme:${t}`);
                }
                async loadFromArrayBuffer() {
                    order.push("load");
                }
                repaint() {
                    order.push("repaint");
                }
                async fontsReady() {
                    order.push("fontsReady");
                }
            },
        };
        // Saved and restored, not deleted: mocha shares one process, and `rcFonts.test.ts` later
        // asserts that the real module registered itself on this very property.
        const priorFonts = (window as Record<string, unknown>).cpRcFonts;
        (window as Record<string, unknown>).cpRcFonts = {
            ready: async () => order.push("pageFonts"),
        };
        globalThis.fetch = (async () => ({
            ok: true,
            arrayBuffer: async () => new ArrayBuffer(8),
        })) as unknown as typeof fetch;
        globalThis.requestAnimationFrame = ((fn: () => void) => {
            setTimeout(fn, 0);
            return 0;
        }) as unknown as typeof requestAnimationFrame;

        const scorer = stubScorer({});
        await mount({
            available: 'data-has-rc="1"',
            rows: [{ name: "Button", have: ["png-light", "rc-light"] }],
        });
        await settle();
        void scorer;

        assert.deepEqual(order.slice(order.indexOf("setTheme:light")), [
            "setTheme:light",
            "load",
            "repaint",
            "fontsReady",
            "repaint",
        ]);
        delete (window as Record<string, unknown>).RC;
        (window as Record<string, unknown>).cpRcFonts = priorFonts;
    });

    it("moves the design spec to the left when the Figma lane is picked", async () => {
        // The server renders the table in the order its own default format wants (`svg` here, so
        // render first). Pressing the design-spec button changes which question the two columns
        // answer, so the columns and their headers follow — spec left, render right, the same way
        // the viewer's spec lane, its wipe seam and the focused Reference / Diff / Actual page all
        // draw the pair. Leaving the lane puts them back.
        stubScorer({ "/a/Button-reference-light": 80 });
        document.body.innerHTML = `
          <cp-compare-wall></cp-compare-wall>
          <div id="cp-compare" data-default-format="svg" data-default-theme="light"
               data-theme-key="cp-compare-theme" data-has-svg="1" data-has-reference="1"
               data-reference-label="Figma">
            <button data-compare-format="svg">SVG</button>
            <button data-compare-format="reference">Figma</button>
            <div id="cp-compare-formats"><table>
              <thead><tr><th>Preview</th>
                <th class="cp-compare-render-head">Rendered PNG</th>
                <th class="cp-compare-target-head">SVG</th>
                <th>Match</th></tr></thead>
              <tbody>
                <tr class="cp-compare-row" data-label="Button" data-hay="button"
                    data-preview-ids="com.example.ButtonPreview"
                    data-png-light="/a/Button-png-light" data-svg-light="/a/Button-svg-light"
                    data-reference-light="/a/Button-reference-light">
                  <th scope="row">Button</th>
                  <td class="cp-compare-render-cell"><img class="cp-compare-png" alt=""></td>
                  <td class="cp-compare-target-cell"><img class="cp-compare-vector" alt=""><canvas></canvas></td>
                  <td class="cp-compare-score"></td>
                </tr>
              </tbody>
            </table></div>
          </div>`;
        await flush();
        await settle();

        const headOrder = () =>
            Array.from(document.querySelectorAll("thead th"))
                .map((th) => th.className)
                .filter(Boolean);
        const cellOrder = () =>
            Array.from(
                document.querySelectorAll(
                    ".cp-compare-row .cp-compare-render-cell, .cp-compare-row .cp-compare-target-cell",
                ),
            ).map((td) => td.className);
        const headText = () =>
            document.querySelector(".cp-compare-target-head")?.textContent;

        assert.deepEqual(headOrder(), [
            "cp-compare-render-head",
            "cp-compare-target-head",
        ]);
        assert.deepEqual(cellOrder(), [
            "cp-compare-render-cell",
            "cp-compare-target-cell",
        ]);
        assert.equal(headText(), "SVG");

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        assert.deepEqual(headOrder(), [
            "cp-compare-target-head",
            "cp-compare-render-head",
        ]);
        assert.deepEqual(cellOrder(), [
            "cp-compare-target-cell",
            "cp-compare-render-cell",
        ]);
        // Named for the lane it is showing — a header still reading "SVG" over the Figma column
        // would say the pair is the other way round.
        assert.equal(headText(), "Figma");

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.deepEqual(cellOrder(), [
            "cp-compare-render-cell",
            "cp-compare-target-cell",
        ]);
        assert.equal(headText(), "SVG");
    });

    it("keeps the delta map between the pair when the columns swap", async () => {
        // The map is only a diff OF the two pictures if it sits between them. The pair swaps sides
        // at runtime, and a swap that reasoned about the pair alone would shunt whatever was parked
        // in the middle to the end of the row — leaving the wall claiming a middle column while
        // drawing a trailing one.
        stubScorer({ "/a/Button-reference-light": 80 });
        document.body.innerHTML = `
          <cp-compare-wall></cp-compare-wall>
          <div id="cp-compare" data-default-format="svg" data-default-theme="light"
               data-theme-key="cp-compare-theme" data-has-svg="1" data-has-reference="1"
               data-reference-label="Figma">
            <button data-compare-format="svg">SVG</button>
            <button data-compare-format="reference">Figma</button>
            <div id="cp-compare-formats"><table>
              <thead><tr><th>Preview</th>
                <th class="cp-compare-render-head">Rendered PNG</th>
                <th class="cp-compare-diff-head">Diff</th>
                <th class="cp-compare-target-head">SVG</th>
                <th>Match</th></tr></thead>
              <tbody>
                <tr class="cp-compare-row" data-label="Button" data-hay="button"
                    data-preview-ids="com.example.ButtonPreview"
                    data-png-light="/a/Button-png-light" data-svg-light="/a/Button-svg-light"
                    data-reference-light="/a/Button-reference-light">
                  <th scope="row">Button</th>
                  <td class="cp-compare-render-cell"><img class="cp-compare-png" alt=""></td>
                  <td class="cp-compare-diff-cell"><canvas class="cp-compare-diff"></canvas></td>
                  <td class="cp-compare-target-cell"><img class="cp-compare-vector" alt=""><canvas class="cp-compare-rc" hidden></canvas></td>
                  <td class="cp-compare-score"></td>
                </tr>
              </tbody>
            </table></div>
          </div>`;
        await flush();
        await settle();

        const pictures = (selector: string) =>
            Array.from(document.querySelectorAll(selector))
                .map((node) => node.className)
                .filter((name) => /render|diff|target/.test(name));
        const heads = () => pictures("thead th");
        const cells = () => pictures(".cp-compare-row td");

        assert.deepEqual(heads(), [
            "cp-compare-render-head",
            "cp-compare-diff-head",
            "cp-compare-target-head",
        ]);
        assert.deepEqual(cells(), [
            "cp-compare-render-cell",
            "cp-compare-diff-cell",
            "cp-compare-target-cell",
        ]);

        document
            .querySelector<HTMLElement>('[data-compare-format="reference"]')!
            .click();
        await settle();
        assert.deepEqual(heads(), [
            "cp-compare-target-head",
            "cp-compare-diff-head",
            "cp-compare-render-head",
        ]);
        assert.deepEqual(cells(), [
            "cp-compare-target-cell",
            "cp-compare-diff-cell",
            "cp-compare-render-cell",
        ]);

        document
            .querySelector<HTMLElement>('[data-compare-format="svg"]')!
            .click();
        await settle();
        assert.deepEqual(cells(), [
            "cp-compare-render-cell",
            "cp-compare-diff-cell",
            "cp-compare-target-cell",
        ]);
    });

    it("stays inert on a page that is not the compare wall", async () => {
        document.body.innerHTML = `<cp-compare-wall></cp-compare-wall><div class="cp-grid"></div>`;
        await flush();
        assert.equal(document.querySelector(".cp-compare-row"), null);
    });
});
