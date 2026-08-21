// The rules behind the revision menu's render-run markers.
//
// The URL half matters for the same reason `historyUrls.test.ts` does: `data-render-url` is DOM
// text that ends up in an `img.src`, so the guarantee under test is that nothing is passed through
// — every accepted value is rebuilt from captured segments, and everything else yields null.

import assert from "node:assert/strict";
import {
    renderTemplateOf,
    runsViewOf,
    summaryOf,
    thumbUrlAt,
} from "../src/viewer/renderRuns.js";

const TEMPLATE = renderTemplateOf(
    "/wear-m3-catalog/render/media-playerscreen__ideal__default__192dp.png",
)!;

describe("renderTemplateOf", () => {
    it("rebuilds a site-relative render URL, query and all", () => {
        const template = renderTemplateOf("/wear/render/a.png?token=ab%20c");
        assert.deepEqual(template, {
            base: "/wear/render/a.png",
            query: "?token=ab%20c",
        });
    });

    it("refuses anything that could point the image somewhere else", () => {
        // Protocol-relative, absolute, and a `javascript:` URL: no `:` is admitted anywhere, and
        // the leading `\/(?!\/)` is what rejects `//host/…`.
        assert.equal(renderTemplateOf("//evil.example/a.png"), null);
        assert.equal(renderTemplateOf("https://evil.example/a.png"), null);
        assert.equal(renderTemplateOf("javascript:alert(1)"), null);
        // Not a render at all.
        assert.equal(renderTemplateOf("/wear/render/a.svg"), null);
        assert.equal(renderTemplateOf(null), null);
    });
});

describe("thumbUrlAt", () => {
    it("pins the render to a run head", () => {
        assert.equal(
            thumbUrlAt(TEMPLATE, "d9628859aaaa0667ff8baa42cee428a3aab57432"),
            "/wear-m3-catalog/render/media-playerscreen__ideal__default__192dp.png" +
                "?at=d9628859aaaa0667ff8baa42cee428a3aab57432",
        );
    });

    it("joins onto a query the page already carries", () => {
        const template = renderTemplateOf("/wear/render/a.png?token=abc")!;
        assert.equal(
            thumbUrlAt(template, "abc1234"),
            "/wear/render/a.png?token=abc&at=abc1234",
        );
    });

    it("refuses a pin that is not a sha", () => {
        // The shas come from our own JSON, but "the server said so" is exactly the assumption that
        // stops holding the day something else answers this route.
        assert.equal(thumbUrlAt(TEMPLATE, "main"), null);
        assert.equal(thumbUrlAt(TEMPLATE, "../../etc/passwd"), null);
        assert.equal(thumbUrlAt(TEMPLATE, undefined), null);
    });
});

describe("runsViewOf", () => {
    /** The shape that prompted the feature: twelve publishes, two distinct renders. */
    const PAYLOAD = {
        revisions: 12,
        runs: [
            { head: "a".repeat(40), sourceSha: "d9628859", commits: 2 },
            { head: "b".repeat(40), sourceSha: "eede08a2", commits: 10 },
        ],
    };

    it("marks one row per distinct render", () => {
        const view = runsViewOf(PAYLOAD, TEMPLATE)!;
        assert.deepEqual(
            [...view.markers.keys()],
            ["a".repeat(40), "b".repeat(40)],
        );
        assert.equal(
            view.summary,
            "2 distinct renders across these 12 publishes",
        );
        assert.equal(view.markers.get("b".repeat(40))?.span, "×10");
        assert.match(
            view.markers.get("b".repeat(40))!.title,
            /10 consecutive publishes carry these pixels/,
        );
    });

    it("says nothing about a run of one, where a count adds nothing", () => {
        const view = runsViewOf(
            {
                revisions: 2,
                runs: [
                    { head: "a".repeat(40), commits: 1 },
                    { head: "b".repeat(40), commits: 1 },
                ],
            },
            TEMPLATE,
        )!;
        assert.equal(view.markers.get("a".repeat(40))?.span, null);
    });

    it("reads an open run as a floor, not a count", () => {
        const view = runsViewOf(
            {
                revisions: 12,
                runs: [{ head: "a".repeat(40), commits: 12, open: true }],
            },
            TEMPLATE,
        );
        // One run is no *difference* to point at, so there is nothing to mark…
        assert.equal(view, null);
        // …but a run that IS drawn must not claim a count the window cannot support.
        const two = runsViewOf(
            {
                revisions: 12,
                runs: [
                    { head: "a".repeat(40), commits: 2 },
                    { head: "b".repeat(40), commits: 10, open: true },
                ],
            },
            TEMPLATE,
        )!;
        assert.match(
            two.markers.get("b".repeat(40))!.title,
            /At least 10 publishes/,
        );
    });

    it("skips a run whose head cannot be addressed rather than drawing a broken image", () => {
        const view = runsViewOf(
            {
                revisions: 3,
                runs: [
                    { head: "a".repeat(40), commits: 1 },
                    { head: "main", commits: 2 },
                ],
            },
            TEMPLATE,
        )!;
        assert.deepEqual([...view.markers.keys()], ["a".repeat(40)]);
    });

    it("draws nothing without a usable render URL or any runs", () => {
        assert.equal(runsViewOf(PAYLOAD, null), null);
        assert.equal(runsViewOf({ revisions: 12, runs: [] }, TEMPLATE), null);
        assert.equal(runsViewOf(null, TEMPLATE), null);
    });
});

describe("summaryOf", () => {
    it("answers 'do they all differ?' in one line", () => {
        assert.equal(
            summaryOf(2, 12),
            "2 distinct renders across these 12 publishes",
        );
        assert.equal(summaryOf(1, 12), "All 12 publishes render identically");
        assert.equal(
            summaryOf(1, 1),
            "Only one publish of this preview so far",
        );
        assert.equal(summaryOf(0, 0), "");
    });
});
