// What the reference page says about a pair, and what it hands the report form.
//
// The security-relevant one is `reportRenderUrl`: a report is written to be pasted somewhere else,
// and a URL still carrying the session token grants whoever reads it the access the reporter had.

import assert from "node:assert/strict";
import {
    changedPercentOf,
    fillReport,
    rawScores,
    reportRenderUrl,
    resultLine,
} from "../src/annotate/report.js";
import type { ComparisonResult } from "../src/compare/detail.js";

const result = (over: Partial<ComparisonResult> = {}): ComparisonResult => ({
    score: 98.4,
    changed: 1200,
    pixels: 40000,
    geometry: 0.4,
    ...over,
});

describe("changedPercentOf", () => {
    it("guards the frame that never decoded", () => {
        assert.equal(changedPercentOf(result({ pixels: 0 })), 0);
        assert.equal(changedPercentOf(result()), 3);
    });
});

describe("resultLine", () => {
    it("states both numbers, because they answer different questions", () => {
        // Structural match is "how alike are these"; changed pixels is "how much of the frame
        // moved". 99% with 8% of pixels differing is a uniform shift; the reverse is one element in
        // the wrong place.
        assert.equal(
            resultLine(result()),
            "98.4% structural match · 3.00% pixels changed",
        );
    });

    it("adds the proportion difference only once it is more than rasteriser noise", () => {
        assert.ok(
            !resultLine(result({ geometry: 1.9 })).includes("proportion"),
        );
        assert.equal(
            resultLine(result({ geometry: 2 })),
            "98.4% structural match · 3.00% pixels changed · 2.0% proportion difference",
        );
    });
});

describe("rawScores", () => {
    it("says the same thing as one sentence, under the same threshold", () => {
        assert.equal(
            rawScores(result()),
            "98.4% structural match; 3.00% pixels changed",
        );
        assert.equal(
            rawScores(result({ geometry: 7.24 })),
            "98.4% structural match; 3.00% pixels changed; 7.2% proportion difference",
        );
    });
});

describe("reportRenderUrl", () => {
    it("strips the session token and keeps everything else", () => {
        // The overrides are what make the URL reproduce the frame being reported, so they stay; the
        // token is what makes the report a credential, so it does not.
        const url = reportRenderUrl(
            "/m3/render/plain.Button.png?token=secret&theme=dark&at=abc",
            "https://preview.example/catalog/compare/plain.Button",
        );
        assert.ok(!url.includes("secret"), url);
        assert.ok(url.includes("theme=dark"), url);
        assert.ok(url.includes("at=abc"), url);
        assert.ok(url.startsWith("https://preview.example/"), url);
    });
});

describe("fillReport", () => {
    // The real row, as `ServeIssueReport.body` writes it. Spelled out here rather than approximated,
    // because the score placeholder is filled by EXACT row identity — a template that only
    // resembles the server's would pass a test and fill nothing in production.
    const ROW = "| Raw comparison | `{{rawScores}}` |";
    const template = [
        "| Preview | `plain.Button` |",
        ROW,
        "",
        "![render]({{render}})",
        "Steps: {{unknown}}",
    ].join("\n");

    it("substitutes the two placeholders and nothing else", () => {
        assert.equal(
            fillReport(
                template,
                "https://preview.example/r.png",
                "98.4% structural match",
            ),
            [
                "| Preview | `plain.Button` |",
                "| Raw comparison | `98.4% structural match` |",
                "",
                "![render](https://preview.example/r.png)",
                "Steps: {{unknown}}",
            ].join("\n"),
        );
    });

    it("drops the score row when the browser could not measure", () => {
        // What the server itself writes when it has no measurements: no row at all, rather than a
        // placeholder or a word where a number belongs.
        assert.equal(
            fillReport(template, "https://preview.example/r.png", null),
            [
                "| Preview | `plain.Button` |",
                "",
                "![render](https://preview.example/r.png)",
                "Steps: {{unknown}}",
            ].join("\n"),
        );
    });

    it("substitutes the render LINK, never a bare occurrence in catalog text", () => {
        // `ServeIssueReport.body` only ever emits the placeholder as a markdown destination. A bare
        // occurrence therefore came from catalog-authored text, and rewriting it would leave the
        // real link reading `{{render}}` — a broken image in every filed report. This got sharper
        // when the body started being composed before any score existed: a bad substitution now
        // replaces a perfectly good server-written body instead of never running.
        const hostile = [
            "| Preview | `weird{{render}}` |",
            "![shot]({{render}})",
        ].join("\n");
        assert.equal(
            fillReport(hostile, "https://preview.example/r.png", null),
            [
                "| Preview | `weird{{render}}` |",
                "![shot](https://preview.example/r.png)",
            ].join("\n"),
        );
    });

    it("drops only the dedicated row, never another line carrying the same text", () => {
        // Preview ids and variants are catalog-authored — third-party data. Filtering every line
        // that CONTAINS the placeholder would take the `| Preview |` row and the locator's required
        // `preview:` field with it, producing a report that cannot be indexed at all, on the exact
        // path meant to make a failed comparison still reportable.
        const hostile = [
            "| Preview | `weird{{rawScores}}` |",
            ROW,
            "preview: weird{{rawScores}}",
        ].join("\n");
        assert.equal(
            fillReport(hostile, "https://preview.example/r.png", null),
            [
                "| Preview | `weird{{rawScores}}` |",
                "preview: weird{{rawScores}}",
            ].join("\n"),
        );
    });

    it("leaves a body with no score row alone", () => {
        const noRow = "| Preview | `plain.Button` |\n![render]({{render}})";
        assert.equal(
            fillReport(noRow, "https://preview.example/r.png", null),
            "| Preview | `plain.Button` |\n![render](https://preview.example/r.png)",
        );
    });
});
