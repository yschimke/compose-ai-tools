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
    canonicalOverrides,
    fillLocators,
    fillSelection,
    locatorBlock,
    selectionLines,
    usableBounds,
    variantOf,
    LOCATORS_PLACEHOLDER,
    type Bounds,
} from "../src/report/locator.js";

interface FixtureCase {
    name: string;
    writer:
        | ({
              repository: string;
              system: string;
              componentId: string;
              previewId: string;
              referenceId: string;
              variant: string;
              overrides?: Record<string, string>;
              revision?: string;
              element?: string;
              bounds?: Bounds & { space: string };
          } & Record<string, unknown>)
        | null;
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

// The WHOLE block, not just the selection lines the rest of this file is about.
//
// The comparison wall's multi-row picker writes blocks the server never saw — which comparisons a
// report names is decided by ticking rows after the page was served — so this module is a third
// engine on the same contract, beside `ServeIssueReport.locatorBlock` and `parity-issues.mjs`. It
// is pinned to the same fixture as both, over every case the fixture says a writer emits, because a
// block that differs from the Kotlin writer's by one byte is a second canonical form for the same
// comparison and the whole reason the format is canonical is that there is only one.
describe("locator block writer", () => {
    for (const fixture of FIXTURE.cases.filter((entry) => entry.writer)) {
        it(`writes the fixture's \`${fixture.name}\` block byte for byte`, () => {
            const writer = fixture.writer!;
            assert.equal(
                locatorBlock({
                    repository: writer.repository,
                    system: writer.system,
                    componentId: writer.componentId,
                    previewId: writer.previewId,
                    referenceId: writer.referenceId,
                    variant: writer.variant,
                    overrides: writer.overrides,
                    revision: writer.revision,
                    element: writer.element,
                    bounds: writer.bounds,
                }),
                fixture.block,
            );
        });
    }

    // The default comparator orders by UTF-16 code unit, which puts an astral key before a BMP one
    // above U+D800 — the opposite of the canonical order the Kotlin writer produces. This case is
    // the only thing that catches a plain `.sort()` here.
    it("orders override keys by code point, not by code unit", () => {
        assert.equal(
            canonicalOverrides({ "\u{1F600}a": "2", "！b": "1" }),
            '{"！b":"1","\u{1F600}a":"2"}',
        );
    });

    it("writes no overrides as an empty object, which is what the wall's rows carry", () => {
        assert.equal(canonicalOverrides(), "{}");
        assert.equal(canonicalOverrides({}), "{}");
    });

    // Ported from `ServeIssueReport.variantFor`, and the reason it is ported rather than emitted per
    // row: the value is the preview id's own tail.
    it("derives the variant from the preview id", () => {
        assert.equal(
            variantOf("iconbutton-tonal__ideal__default__light"),
            "ideal/default/light",
        );
        assert.equal(variantOf("IconButton"), "");
    });
});

describe("filling a pickable report's locators", () => {
    // As the server writes it: the placeholder is a line of its own, newline-terminated, with no
    // blank line committed ahead of it.
    const template = `### Which page\n\n| | |\n${LOCATORS_PLACEHOLDER}\n`;

    // Nothing ticked has to reproduce the page-scoped body the server writes on its own — that is
    // what a visitor with JavaScript off files, and the two must not be different reports.
    it("removes the placeholder line entirely when nothing is picked", () => {
        assert.equal(fillLocators(template, []), "### Which page\n\n| | |\n");
    });

    it("separates the blocks from the body above them", () => {
        const one = "```fence\nfield: value\n```\n";
        assert.equal(
            fillLocators(template, [one, one]),
            `### Which page\n\n| | |\n\n${one}${one}`,
        );
    });

    it("leaves a template with no placeholder alone", () => {
        assert.equal(
            fillLocators("### Which page\n", ["x"]),
            "### Which page\n",
        );
    });
});
