// The browser's half of the `compose-parity-locator/v1` writer, against the SHARED fixture.
//
// Three engines now touch this block: `ServeIssueReport` writes it, `parity-issues.mjs` parses it,
// and this module fills in the one part neither of them can know — what the reporter selected, which
// happens after the page is served. All three are pinned to one file, so none of them can move the
// contract alone. That matters more here than anywhere else in the format: the fields are OPTIONAL
// and both parsers ignore unknown keys, so a writer that emitted them slightly wrong would produce
// reports that index cleanly with the selection silently dropped — no rejection to notice, no error
// anywhere.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
    canonicalBounds,
    canonicalElement,
    fillSelection,
    selectionLines,
    usableBounds,
    type Bounds,
} from "../src/report/locator.js";

interface FixtureCase {
    name: string;
    writer: {
        element?: string;
        bounds?: Bounds & { space: string };
    } | null;
    block: string;
}

const FIXTURE = JSON.parse(
    readFileSync(
        fileURLToPath(
            new URL(
                "../../../scripts/design-artifacts/fixtures/parity-locators.json",
                import.meta.url,
            ),
        ),
        "utf8",
    ),
) as { cases: FixtureCase[] };

const named = (name: string): FixtureCase => {
    const found = FIXTURE.cases.find((entry) => entry.name === name);
    assert.ok(found, `fixture case ${name} is missing`);
    return found;
};

/** The `element:` / `bounds:` lines the fixture's block actually carries, in order. */
function selectionLinesOf(block: string): string {
    return block
        .split("\n")
        .filter(
            (line) =>
                line.startsWith("element: ") || line.startsWith("bounds: "),
        )
        .map((line) => `${line}\n`)
        .join("");
}

describe("locator selection writer", () => {
    it("writes the fixture's element and bounds lines byte for byte", () => {
        const fixture = named("element-and-bounds");
        assert.deepEqual(
            selectionLines({
                element: fixture.writer!.element,
                bounds: fixture.writer!.bounds,
            }),
            selectionLinesOf(fixture.block),
        );
    });

    it("escapes a tag carrying a newline instead of opening a second field", () => {
        // The injection the encoding exists to stop: unquoted, `row\nrevision: injected` reads back
        // as an element plus a revision nobody wrote.
        const fixture = named("element-with-a-newline");
        assert.equal(
            selectionLines({ element: fixture.writer!.element }),
            selectionLinesOf(fixture.block),
        );
        assert.equal(
            canonicalElement("row\nrevision: injected"),
            '"row\\nrevision: injected"',
        );
    });

    it("writes a signed origin, because a tagged node can sit above the render root", () => {
        const fixture = named("bounds-above-the-render-root");
        assert.equal(
            selectionLines({
                element: fixture.writer!.element,
                bounds: fixture.writer!.bounds,
            }),
            selectionLinesOf(fixture.block),
        );
    });

    it("never emits the key order the producer refuses", () => {
        // Code point order: height < space < width < x < y. `bounds-not-canonical` in the fixture is
        // the same rectangle in insertion order, and the producer rejects it — so a writer that
        // drifted onto that order would take the whole issue out of the index.
        const written = canonicalBounds({
            x: 18,
            y: 18,
            width: 24,
            height: 24,
        });
        assert.equal(
            written,
            '{"height":24,"space":"render-pixels","width":24,"x":18,"y":18}',
        );
        assert.notEqual(
            written,
            named("bounds-not-canonical")
                .block.split("\n")
                .find((line) => line.startsWith("bounds: "))!
                .slice("bounds: ".length),
        );
    });

    it("names render pixels and nothing else", () => {
        // `v1` accepts one plane. There is deliberately no way to ask this writer for another: a
        // display-plane rectangle is the failure that makes an unmoved element report as moved, and
        // both parsers refuse it rather than storing the guess.
        assert.match(
            canonicalBounds({ x: 0, y: 0, width: 1, height: 1 }),
            /"space":"render-pixels"/,
        );
    });
});

describe("usableBounds", () => {
    it("accepts a negative origin", () => {
        // Both tag-index producers emit signed coordinates, and refusing them here would mean a
        // selection could not copy the bounds the index handed it. Clipping belongs to the
        // comparison's plane transform.
        assert.equal(
            usableBounds({ x: -4, y: -2, width: 24, height: 24 }),
            true,
        );
    });

    it("refuses an empty or sub-pixel rectangle", () => {
        assert.equal(usableBounds({ x: 0, y: 0, width: 0, height: 5 }), false);
        assert.equal(usableBounds({ x: 0, y: 0, width: 5, height: 0 }), false);
        assert.equal(
            usableBounds({ x: 0, y: 0, width: 5.5, height: 5 }),
            false,
        );
        assert.equal(usableBounds(undefined), false);
    });
});

describe("selectionLines", () => {
    it("writes nothing at all for no selection", () => {
        // Load-bearing: the substitution has to reproduce the block a server with nothing selected
        // writes on its own, or every unselected report differs from the format's own baseline.
        assert.equal(selectionLines({}), "");
    });

    it("keeps a tag verbatim, edge whitespace and all", () => {
        // `"item"` and `" item "` are different identities to a tag index. Normalising here points
        // the acceptance at the wrong one — or at none.
        assert.equal(
            selectionLines({ element: " item " }),
            'element: " item "\n',
        );
    });

    it("drops an empty tag rather than writing a field both parsers refuse", () => {
        assert.equal(selectionLines({ element: "" }), "");
    });

    it("drops an unusable rectangle but keeps the element beside it", () => {
        // A report naming its element and no region is a real report. One carrying a rectangle the
        // producer refuses is not a report at all — it takes the whole issue out of the index.
        assert.equal(
            selectionLines({
                element: "glyph",
                bounds: { x: 0, y: 0, width: 0, height: 0 },
            }),
            'element: "glyph"\n',
        );
    });

    it("writes a region with no element", () => {
        assert.equal(
            selectionLines({ bounds: { x: 1, y: 2, width: 3, height: 4 } }),
            'bounds: {"height":4,"space":"render-pixels","width":3,"x":1,"y":2}\n',
        );
    });
});

describe("fillSelection", () => {
    const template = [
        "```compose-parity-locator/v1",
        "repository: yschimke/m3-catalog",
        "overrides: {}",
        "{{selection}}",
        "revision: yschimke/m3-catalog@design-artifacts/m3-catalog",
        "```",
        "",
    ].join("\n");

    it("consumes the placeholder line whole when nothing is selected", () => {
        // With its newline: a stray blank line inside the fence is a field the producer's line
        // parser reads short.
        assert.equal(
            fillSelection(template, {}),
            template.replace("{{selection}}\n", ""),
        );
        assert.ok(!fillSelection(template, {}).includes("{{selection}}"));
    });

    it("rewrites the placeholder LINE, not an earlier value that ends in it", () => {
        // Catalog-authored ids are third-party data. A first-occurrence substring replace would
        // rewrite this preview id and file the real placeholder verbatim, producing a locator the
        // parity index cannot consume — with nothing anywhere to notice.
        const hostile = [
            "```compose-parity-locator/v1",
            "preview: weird{{selection}}",
            "overrides: {}",
            "{{selection}}",
            "```",
            "",
        ].join("\n");
        const filled = fillSelection(hostile, { element: "glyph" });
        assert.ok(
            filled.includes("preview: weird{{selection}}"),
            "the preview id must survive untouched",
        );
        assert.ok(!filled.includes("\n{{selection}}\n"), filled);
        assert.ok(filled.includes('element: "glyph"'), filled);
    });

    it("puts the selection between the overrides and the revision", () => {
        const filled = fillSelection(template, {
            element: "glyph",
            bounds: { x: 18, y: 18, width: 24, height: 24 },
        });
        assert.equal(
            filled,
            [
                "```compose-parity-locator/v1",
                "repository: yschimke/m3-catalog",
                "overrides: {}",
                'element: "glyph"',
                'bounds: {"height":24,"space":"render-pixels","width":24,"x":18,"y":18}',
                "revision: yschimke/m3-catalog@design-artifacts/m3-catalog",
                "```",
                "",
            ].join("\n"),
        );
    });
});
