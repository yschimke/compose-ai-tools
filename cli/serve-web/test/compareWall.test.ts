// What the `/compare` wall shows, which artifacts it pairs, and where each choice came from.
//
// Almost nothing here fails loudly. A wrong pairing produces a confident number about the wrong two
// pictures; a wrong resolution order opens a shared link on something other than what it says. Both
// look like a page working normally.

import assert from "node:assert/strict";
import { grade } from "../src/compare/grade.js";
import {
    rowTheme,
    supportsFormat,
    variantFor,
    type Available,
} from "../src/compare/pairing.js";
import { initialState, poppedState, themeOf } from "../src/compare/state.js";
import {
    byWorstFirst,
    countLabel,
    keepRow,
    scoreOf,
} from "../src/compare/wallRows.js";

const ALL: Available = { svg: true, rc: true, reference: true };
const SVG_ONLY: Available = { svg: true, rc: false, reference: false };

/** A row that carries exactly the listed `<kind>-<variant>` artifacts. */
const row = (have: string[]) => (kind: string, variant: string) =>
    have.includes(`${kind}-${variant}`) ? `/a/${kind}-${variant}.png` : "";

describe("grade", () => {
    it("bands on the wall's own thresholds", () => {
        assert.equal(grade(100), "good");
        assert.equal(grade(90), "good");
        assert.equal(grade(89.99), "warn");
        assert.equal(grade(75), "warn");
        assert.equal(grade(74.99), "bad");
        assert.equal(grade(0), "bad");
    });

    it("is looser than the spec lane's, deliberately", () => {
        // Three readings of one metric, and they must NOT be unified: the wall triages dozens of
        // rows, where 89% still reads as "roughly right, look later"; the spec lane judges one pair
        // the visitor chose, where 97% is visibly off. Making them agree makes one of them lie.
        assert.equal(
            grade(98),
            "good",
            "the spec lane would call this 'close'",
        );
    });
});

describe("supportsFormat", () => {
    it("takes only a format this page has artifacts for", () => {
        assert.equal(supportsFormat("svg", ALL), true);
        assert.equal(supportsFormat("rc", SVG_ONLY), false);
        assert.equal(supportsFormat("reference", SVG_ONLY), false);
    });

    it("refuses anything that is not a format at all", () => {
        // `?format=` is visitor-controlled.
        assert.equal(supportsFormat("nonsense", ALL), false);
        assert.equal(supportsFormat(null, ALL), false);
        assert.equal(supportsFormat("", ALL), false);
    });
});

describe("variantFor", () => {
    it("pairs in the theme being compared when the row has it", () => {
        assert.equal(
            variantFor(
                row(["png-dark", "svg-dark", "png-light", "svg-light"]),
                "svg",
                "dark",
            ),
            "dark",
        );
    });

    it("NEVER substitutes the opposite baked theme", () => {
        // The rule this module exists for. A dark PNG beside a light vector looks plausible and
        // scores a number that means nothing — the row then reports a fidelity problem that is
        // really a pairing mistake, which is worse than showing no row at all.
        assert.equal(
            variantFor(row(["png-light", "svg-light"]), "svg", "dark"),
            "",
        );
        assert.equal(
            variantFor(row(["png-dark", "svg-dark"]), "svg", "light"),
            "",
        );
    });

    it("falls back to a theme-neutral pairing under either theme", () => {
        // For a neutral component there is only one artifact and it is the right one.
        const neutral = row(["png-neutral", "svg-neutral"]);
        assert.equal(variantFor(neutral, "svg", "dark"), "neutral");
        assert.equal(variantFor(neutral, "svg", "light"), "neutral");
    });

    it("needs BOTH halves before it will pair anything", () => {
        // Half a pair is not a comparison.
        assert.equal(variantFor(row(["png-light"]), "svg", "light"), "");
        assert.equal(variantFor(row(["svg-light"]), "svg", "light"), "");
    });

    it("is asked per format, so a row can pair in one lane and not another", () => {
        const svgOnly = row(["png-light", "svg-light"]);
        assert.equal(variantFor(svgOnly, "svg", "light"), "light");
        assert.equal(variantFor(svgOnly, "reference", "light"), "");
    });
});

describe("rowTheme", () => {
    it("puts only a genuinely dark pairing on the dark sheet", () => {
        assert.equal(rowTheme("dark"), "dark");
        assert.equal(rowTheme("light"), "light");
        assert.equal(rowTheme("neutral"), "light");
        assert.equal(rowTheme(""), "light");
    });
});

describe("initialState", () => {
    const defaults = { format: "svg", theme: "light" };

    it("opens on the page's defaults when nothing else asks", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.deepEqual(state, { format: "svg", theme: "light", query: "" });
    });

    it("lets the URL pick a format the page can actually show", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("format=reference"),
            available: ALL,
        });
        assert.equal(state.format, "reference");
    });

    it("ignores a format this catalog has nothing for, rather than emptying the wall", () => {
        // An empty table reads as "nothing matches your filter" — a different answer, and a wrong
        // one, for a link that simply names a lane this catalog does not publish.
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("format=rc"),
            available: SVG_ONLY,
        });
        assert.equal(state.format, "svg");
    });

    it("remembers the theme this visitor last compared in", () => {
        const state = initialState({
            defaults,
            remembered: "dark",
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.theme, "dark");
    });

    it("lets an explicit ?theme= OUTRANK what was remembered", () => {
        // The remembered value is a standing preference; a `?theme=` is in the address bar because
        // someone picked it here or was handed the link. A shared link that silently reverts to the
        // reader's own preference is not the link that was sent.
        const state = initialState({
            defaults,
            remembered: "dark",
            params: new URLSearchParams("theme=light"),
            available: ALL,
        });
        assert.equal(state.theme, "light");
    });

    it("ignores a remembered value that is not a theme", () => {
        // `localStorage` is visitor-writable and survives across releases.
        const state = initialState({
            defaults,
            remembered: "chartreuse",
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.theme, "light");
    });

    it("carries the search box's text out of the URL", () => {
        const state = initialState({
            defaults,
            remembered: null,
            params: new URLSearchParams("q=button"),
            available: ALL,
        });
        assert.equal(state.query, "button");
    });
});

describe("poppedState", () => {
    const initial = {
        format: "reference" as const,
        theme: "dark" as const,
        query: "",
    };

    it("restores what a history entry names", () => {
        const state = poppedState({
            initial,
            params: new URLSearchParams("format=svg&theme=light&q=card"),
            available: ALL,
        });
        assert.deepEqual(state, {
            format: "svg",
            theme: "light",
            query: "card",
        });
    });

    it("falls back to what THIS LOAD resolved to, not the page's bare default", () => {
        // The entry with no parameters is the one from before the visitor picked anything. Falling
        // back to the default would make Back from a shared `?theme=dark` link land somewhere the
        // visitor has never been.
        const state = poppedState({
            initial,
            params: new URLSearchParams(),
            available: ALL,
        });
        assert.equal(state.format, "reference");
        assert.equal(state.theme, "dark");
    });

    it("clears the search box for an entry that names no query", () => {
        // Not "leaves it alone": the box is part of the state the entry describes, and a stale
        // filter after Back hides rows the entry says are showing.
        const state = poppedState({
            initial,
            params: new URLSearchParams("format=svg"),
            available: ALL,
        });
        assert.equal(state.query, "");
    });
});

describe("themeOf", () => {
    it("takes the two themes and nothing else", () => {
        assert.equal(themeOf("light"), "light");
        assert.equal(themeOf("dark"), "dark");
        assert.equal(themeOf("Dark"), null);
        assert.equal(themeOf(null), null);
    });
});

describe("keepRow", () => {
    const facts = {
        hay: "filled button · buttons",
        previewIds: "com.example.FilledButtonPreview",
        hasFormat: true,
    };

    it("shows a row this format can pair", () => {
        assert.equal(keepRow(facts, "", ""), true);
    });

    it("hides a row this format has nothing for", () => {
        assert.equal(keepRow({ ...facts, hasFormat: false }, "", ""), false);
    });

    it("matches the search box case-insensitively, on trimmed text", () => {
        assert.equal(keepRow(facts, "BUTTON", ""), true);
        assert.equal(keepRow(facts, "  button  ", ""), true);
        assert.equal(keepRow(facts, "slider", ""), false);
    });

    it("COMPOSES the ?preview= narrow with the search box", () => {
        // The viewer links into this wall for one component. Someone who arrives that way and then
        // types is narrowing within that preview — not starting a fresh search across the catalog,
        // which is what overriding either narrow with the other would give them.
        assert.equal(keepRow(facts, "button", "FilledButton"), true);
        assert.equal(keepRow(facts, "slider", "FilledButton"), false);
        assert.equal(keepRow(facts, "button", "Slider"), false);
    });
});

describe("countLabel", () => {
    it("counts, and says it in the singular exactly once", () => {
        assert.equal(countLabel(0), "0 comparisons");
        assert.equal(countLabel(1), "1 comparison");
        assert.equal(countLabel(2), "2 comparisons");
    });
});

describe("scoreOf / byWorstFirst", () => {
    it("sorts the worst rows to the top, where the wall is read", () => {
        const sorted = [90, 12.5, 100, 74].sort(byWorstFirst);
        assert.deepEqual(sorted, [12.5, 74, 90, 100]);
    });

    it("leads with a row that could not be scored at all", () => {
        // An unmeasured pair outranks any measured one: it is the row nobody is looking at.
        assert.equal(scoreOf(null), -1);
        assert.equal(scoreOf(""), -1);
        assert.equal(scoreOf("-1"), -1);
        assert.deepEqual(
            [80, scoreOf(null), 20].sort(byWorstFirst),
            [-1, 20, 80],
        );
    });

    it("reads a real score back off the attribute", () => {
        assert.equal(scoreOf("93.4"), 93.4);
    });
});

describe("initialState with an unusable default", () => {
    it("opens on a format the catalog HAS, not a hardcoded svg", () => {
        // A Remote-Compose-only catalog. Falling back to "svg" opens the wall on a lane with nothing
        // in it, and an empty table reads as "nothing matches your filter" — the wrong answer to
        // "this catalog does not publish that format".
        const state = initialState({
            defaults: { format: "svg", theme: "light" },
            remembered: null,
            params: new URLSearchParams(),
            available: { svg: false, rc: true, reference: false },
        });
        assert.equal(state.format, "rc");
    });

    it("prefers the declared order when several are available", () => {
        const state = initialState({
            defaults: { format: "nonsense", theme: "light" },
            remembered: null,
            params: new URLSearchParams(),
            available: { svg: false, rc: true, reference: true },
        });
        assert.equal(state.format, "rc");
    });
});
