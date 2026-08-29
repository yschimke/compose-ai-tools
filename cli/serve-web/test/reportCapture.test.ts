// The parts of the report capture tool that can be reasoned about without a screen: the geometry
// that turns a pointed-at rectangle into a crop of a captured frame, the markdown a picked table
// yields, and the `sessionStorage` pile that carries a capture across the navigation to
// `/report-bug`.
//
// None of these is checkable by looking at the running feature. A wrong scale crops the wrong part
// of the picture rather than failing; an eviction rule that drops the newest capture instead of the
// oldest looks identical until the pile is full; and a table whose header row is silently eaten
// still renders as a perfectly good markdown table with one row of data missing.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import {
    clampRect,
    fitWithin,
    frameScale,
    isUsable,
    mapRect,
    rectFromPoints,
} from "../src/report/geometry.js";
import {
    cell,
    elementLabel,
    elementMarkdown,
    tableMarkdown,
} from "../src/report/markdown.js";
import {
    Capture,
    MAX_CAPTURES,
    STORE_KEY,
    addCapture,
    nextId,
    readCaptures,
    removeCapture,
    replaceCapture,
    writeCaptures,
} from "../src/report/store.js";

/** A `Storage` stand-in whose write behaviour the test chooses. */
function storage(options: { quotaAfter?: number } = {}): Storage {
    const map = new Map<string, string>();
    return {
        get length() {
            return map.size;
        },
        clear: () => map.clear(),
        key: (i: number) => Array.from(map.keys())[i] ?? null,
        getItem: (k: string) => map.get(k) ?? null,
        removeItem: (k: string) => void map.delete(k),
        setItem: (k: string, v: string) => {
            if (
                options.quotaAfter !== undefined &&
                v.length > options.quotaAfter
            )
                throw new Error("QuotaExceededError");
            map.set(k, v);
        },
    } as Storage;
}

function shot(id: string, bytes = 40): Capture {
    return {
        id,
        label: `Region ${id}`,
        dataUrl: "data:image/png;base64," + "A".repeat(bytes),
        width: 10,
        height: 10,
    };
}

describe("mapping a selection onto a captured frame", () => {
    it("derives the scale per axis from the frame that arrived", () => {
        // A 2x display: the frame is twice the viewport on both axes.
        assert.deepEqual(
            frameScale(
                { width: 2560, height: 1440 },
                { width: 1280, height: 720 },
            ),
            { x: 2, y: 2 },
        );
    });

    it("does not assume the axes scale together", () => {
        // A browser that clamped a very wide capture squeezes one axis more than the other. A
        // single scalar would shear every crop; per-axis is why this is two numbers.
        assert.deepEqual(
            frameScale(
                { width: 1280, height: 1440 },
                { width: 1280, height: 720 },
            ),
            { x: 1, y: 2 },
        );
    });

    it("falls back to 1:1 rather than dividing by a zero viewport", () => {
        const scale = frameScale(
            { width: 100, height: 100 },
            { width: 0, height: 0 },
        );
        assert.deepEqual(scale, { x: 1, y: 1 });
        assert.ok(Number.isFinite(scale.x));
    });

    it("rounds a crop outward so the pointed-at border survives", () => {
        // x 10.5 → 21 at 2x; rounding the far edge inward would shave the element's own border,
        // which is very often the thing being reported.
        const mapped = mapRect(
            { x: 10.5, y: 10.5, width: 20.5, height: 20.5 },
            { x: 2, y: 2 },
        );
        assert.deepEqual(mapped, { x: 21, y: 21, width: 41, height: 41 });
    });

    it("never maps to a zero-sized crop", () => {
        const mapped = mapRect(
            { x: 0, y: 0, width: 0, height: 0 },
            { x: 1, y: 1 },
        );
        assert.equal(mapped.width, 1);
        assert.equal(mapped.height, 1);
    });

    it("builds a rectangle from a drag in any direction", () => {
        assert.deepEqual(rectFromPoints(90, 80, 10, 20), {
            x: 10,
            y: 20,
            width: 80,
            height: 60,
        });
    });

    it("trims an element taller than the window to what was actually captured", () => {
        // The frame holds the viewport; an untrimmed rect reads outside the source canvas and
        // `drawImage` pads that with transparency — a picture of a table with a void beneath it.
        assert.deepEqual(
            clampRect(
                { x: -20, y: 400, width: 300, height: 900 },
                { width: 1000, height: 800 },
            ),
            { x: 0, y: 400, width: 280, height: 400 },
        );
    });

    it("treats a click with no drag as no selection", () => {
        assert.equal(isUsable({ x: 4, y: 4, width: 0, height: 0 }), false);
        assert.equal(isUsable({ x: 4, y: 4, width: 3, height: 2 }), false);
        assert.equal(isUsable({ x: 4, y: 4, width: 40, height: 30 }), true);
    });

    it("shrinks only what is over the limit, keeping the aspect", () => {
        assert.deepEqual(fitWithin({ width: 3200, height: 1600 }, 1600), {
            width: 1600,
            height: 800,
        });
        assert.deepEqual(fitWithin({ width: 800, height: 600 }, 1600), {
            width: 800,
            height: 600,
        });
    });
});

describe("what a picked element is worth as text", () => {
    beforeEach(resetDom);

    it("renders a table with its own header row", () => {
        document.body.innerHTML = `
          <table><thead><tr><th>Lane</th><th>State</th></tr></thead>
          <tbody><tr><td>live</td><td>up</td></tr>
          <tr><td>baked</td><td>idle</td></tr></tbody></table>`;
        assert.equal(
            tableMarkdown(document.querySelector("table") as HTMLTableElement),
            [
                "| Lane | State |",
                "| --- | --- |",
                "| live | up |",
                "| baked | idle |",
            ].join("\n"),
        );
    });

    it("keeps the first row of a header-less fact table instead of promoting it", () => {
        // This server's own diagnostics tables are row-header two-column shapes with no column
        // heading. Promoting the first row would silently eat a fact out of a bug report.
        document.body.innerHTML = `
          <table class="cp-report-facts"><tbody>
            <tr><th scope="row">Mode</th><td>public</td></tr>
            <tr><th scope="row">Uptime</th><td>2h 58m</td></tr>
          </tbody></table>`;
        assert.equal(
            tableMarkdown(document.querySelector("table") as HTMLTableElement),
            [
                "|  |  |",
                "| --- | --- |",
                "| Mode | public |",
                "| Uptime | 2h 58m |",
            ].join("\n"),
        );
    });

    it("carries the whole table when a single cell was picked", () => {
        // A number without the row and column that name it is not evidence.
        document.body.innerHTML = `
          <table><tbody><tr><th scope="row">Trust</th><td id="t">branch</td></tr></tbody></table>`;
        const markdown = elementMarkdown(
            document.getElementById("t") as Element,
        );
        assert.match(markdown, /\| Trust \| branch \|/);
    });

    it("fences preformatted text and neutralises a fence inside it", () => {
        document.body.innerHTML = "<pre>render failed\n```\nstack</pre>";
        const markdown = elementMarkdown(
            document.querySelector("pre") as Element,
        );
        assert.ok(markdown.startsWith("```\n"));
        assert.ok(markdown.endsWith("\n```"));
        assert.ok(!markdown.slice(4, -4).includes("```"));
    });

    it("says nothing about an element whose value is its pixels", () => {
        document.body.innerHTML = `<img id="r" src="/render/Button.png">`;
        assert.equal(
            elementMarkdown(document.getElementById("r") as Element),
            "",
        );
    });

    it("escapes what would shear a row or close a code span", () => {
        assert.equal(cell("a|b`c\\d"), "a\\|b\\`c\\\\d");
        assert.equal(cell("two\nlines"), "two lines");
    });

    it("labels an element by the first thing that identifies it", () => {
        document.body.innerHTML = `
          <table id="cp-status"></table><ul class="cp-shot-list one"></ul><b></b>`;
        assert.equal(
            elementLabel(document.querySelector("table")!),
            "table#cp-status",
        );
        assert.equal(
            elementLabel(document.querySelector("ul")!),
            "ul.cp-shot-list",
        );
        assert.equal(elementLabel(document.querySelector("b")!), "b");
    });
});

describe("the pile that survives the navigation to /report-bug", () => {
    it("keeps the newest when more than the cap are added", () => {
        const store = storage();
        for (const id of ["a", "b", "c", "d"]) addCapture(store, shot(id));
        assert.deepEqual(
            readCaptures(store).map((c) => c.id),
            ["b", "c", "d"],
        );
        assert.equal(readCaptures(store).length, MAX_CAPTURES);
    });

    it("drops the oldest, not the newest, when the browser refuses the write", () => {
        // A quota rejection is not a reason to lose the capture somebody just deliberately took.
        const store = storage({ quotaAfter: 400 });
        addCapture(store, shot("a", 200));
        addCapture(store, shot("b", 200));
        assert.deepEqual(
            readCaptures(store).map((c) => c.id),
            ["b"],
        );
    });

    it("reports what actually landed when nothing could be stored", () => {
        const store = storage({ quotaAfter: 1 });
        assert.deepEqual(addCapture(store, shot("a")), []);
        assert.deepEqual(readCaptures(store), []);
    });

    it("survives a store that is missing, unreadable, or holding nonsense", () => {
        assert.deepEqual(readCaptures(null), []);
        const store = storage();
        store.setItem(STORE_KEY, "not json");
        assert.deepEqual(readCaptures(store), []);
        store.setItem(STORE_KEY, JSON.stringify({ id: "a" }));
        assert.deepEqual(readCaptures(store), []);
    });

    it("refuses an entry whose picture is not a PNG data URL", () => {
        // That string becomes an `<img src>` on the report page, so the prefix is the guard.
        const store = storage();
        store.setItem(
            STORE_KEY,
            JSON.stringify([
                { ...shot("ok") },
                { ...shot("bad"), dataUrl: "https://elsewhere.example/x.png" },
                { ...shot("worse"), dataUrl: "javascript:alert(1)" },
            ]),
        );
        assert.deepEqual(
            readCaptures(store).map((c) => c.id),
            ["ok"],
        );
    });

    it("removes one by id and leaves the rest", () => {
        const store = storage();
        addCapture(store, shot("a"));
        addCapture(store, shot("b"));
        removeCapture(store, "a");
        assert.deepEqual(
            readCaptures(store).map((c) => c.id),
            ["b"],
        );
    });

    it("replaces edited pixels in place and clears no neighbouring capture", () => {
        const store = storage();
        addCapture(store, shot("a"));
        addCapture(store, shot("b"));
        replaceCapture(store, { ...shot("a"), label: "Marked up" });
        assert.deepEqual(
            readCaptures(store).map((capture) => [capture.id, capture.label]),
            [
                ["a", "Marked up"],
                ["b", "Region b"],
            ],
        );
    });

    it("mints an id no capture in the pile already has", () => {
        assert.equal(nextId([]), "shot-1");
        assert.equal(nextId([shot("shot-1"), shot("shot-3")]), "shot-4");
    });

    it("writes nothing at all when there is no store", () => {
        assert.deepEqual(writeCaptures(null, [shot("a")]), []);
    });
});
