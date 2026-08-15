// Behavioural contract for `<cp-parity-scores>`.
//
// The judgements are pinned in `findings.test.ts`; this covers what only the element can answer —
// that it drains the queue, writes the per-row cells it does not own, survives a pair it cannot
// score, and draws nothing rather than a false promise when there is nothing to scan.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/ParityScores.js";

interface Measured {
    percent: number;
    geometry?: number;
}

/**
 * Mount the band as the server renders it: the tag above, the comparison table below — the ordering
 * that catches an element reading its subjects at connect time.
 */
async function mount(
    scores: Record<string, Measured | "fail">,
    options: { compare?: boolean } = {},
): Promise<HTMLElement> {
    const names = Object.keys(scores);
    if (options.compare !== false) {
        window.ComposePreviewCompare = {
            scoreImageUrls: async (reference: string) => {
                const measured = scores[reference];
                if (measured === "fail") throw new Error("no render");
                return measured;
            },
        };
    } else {
        delete window.ComposePreviewCompare;
    }
    document.body.innerHTML = `
      <cp-parity-scores></cp-parity-scores>
      <table><tbody id="rows"></tbody></table>`;
    // Rows are appended through the DOM rather than interpolated into the markup above, so a name
    // carrying a quote tests the ELEMENT's escaping instead of breaking out of the fixture's.
    const body = document.getElementById("rows") as HTMLElement;
    for (const name of names) {
        const row = document.createElement("tr");
        row.setAttribute("data-parity-comparison", "");
        row.dataset.reference = name;
        row.dataset.actual = `${name}.png`;
        row.dataset.name = name;
        row.dataset.review = `/p/${name}`;
        row.innerHTML = `<td><span class="cp-parity-score cp-muted">Checking…</span></td>`;
        body.appendChild(row);
    }
    for (let i = 0; i < 6; i++) await flush();
    return document.querySelector("cp-parity-scores") as HTMLElement;
}

const status = () =>
    document.getElementById("cp-parity-score-status")?.textContent?.trim();
const cell = (name: string) =>
    Array.from(
        document.querySelectorAll<HTMLElement>("[data-parity-comparison]"),
    )
        .find((row) => row.dataset.name === name)
        ?.querySelector<HTMLElement>(".cp-parity-score") ?? null;
const issues = () =>
    Array.from(document.querySelectorAll("#cp-parity-score-issues tr")).map(
        (row) => Array.from(row.children).map((c) => c.textContent?.trim()),
    );

describe("<cp-parity-scores>", () => {
    afterEach(() => {
        delete window.ComposePreviewCompare;
        resetDom();
    });

    it("scores every mapped pair and says so", async () => {
        await mount({ Button: { percent: 99.9 }, Card: { percent: 98 } });
        assert.equal(
            status(),
            "All 2 mapped components are at least 90% structural match.",
        );
        assert.equal(cell("Button")?.textContent, "99.9%");
        assert.equal(cell("Button")?.className, "cp-parity-score cp-ok");
    });

    it("draws no table when there is nothing to review", async () => {
        // An empty issues table under a heading says less than its absence does.
        await mount({ Button: { percent: 99.9 } });
        assert.equal(document.getElementById("cp-parity-score-results"), null);
    });

    it("lists the components worth looking at, worst first", async () => {
        await mount({
            Fine: { percent: 99 },
            Bad: { percent: 41.2 },
            Shape: { percent: 96, geometry: 5.5 },
        });
        assert.deepEqual(issues(), [
            ["Bad", "41.2%", "Compare"],
            ["Shape", "96.0% · 5.5% proportion drift", "Compare"],
        ]);
        assert.equal(
            status(),
            "2 mapped components have a structural or proportion difference.",
        );
    });

    it("keeps going past a pair it cannot score", async () => {
        // Expected on a catalog mid-publish. Dropping it would report the page clean on exactly
        // the components nobody can see.
        await mount({
            Broken: "fail",
            Bad: { percent: 55 },
            Fine: { percent: 99 },
        });
        assert.equal(cell("Broken")?.textContent, "Unavailable");
        assert.equal(cell("Fine")?.textContent, "99.0%");
        assert.equal(
            status(),
            "1 of 3 mapped comparisons could not be scored. " +
                "1 of the rest has a structural or proportion difference.",
        );
    });

    it("carries the proportion drift onto the row's own cell", async () => {
        await mount({ Shape: { percent: 96, geometry: 5.5 } });
        assert.equal(cell("Shape")?.title, "5.5% proportion difference");
        assert.equal(
            cell("Shape")?.className,
            "cp-parity-score cp-parity-missing",
        );
    });

    it("links each finding at its comparison, without hand-rolled escaping", async () => {
        // `parity.js` built this table with `innerHTML` and an `esc()` that neutralised `<`, `>`
        // and `&` but not `"`, straight into `href="…"`. A binding cannot be broken out of.
        await mount({ 'Ev"il': { percent: 10 } });
        const link = document.querySelector<HTMLAnchorElement>(
            "#cp-parity-score-issues a",
        );
        assert.equal(link?.getAttribute("href"), '/p/Ev"il');
        assert.equal(link?.textContent, "Compare");
    });

    it("draws nothing at all when no scorer is loaded", async () => {
        // `format-compare.js` only ships for a catalog with published references.
        const band = await mount(
            { Button: { percent: 99 } },
            { compare: false },
        );
        assert.equal(band.children.length, 0);
    });

    it("draws nothing at all when nothing is mapped", async () => {
        const band = await mount({});
        assert.equal(
            band.children.length,
            0,
            "no false 'Checking 0 comparisons…' promise",
        );
    });
});
