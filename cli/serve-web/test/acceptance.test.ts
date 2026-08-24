// The browser adapter, end to end over a synthetic catalog.
//
// What this checks is the **plumbing**, not the contract: the contract's semantics are pinned by
// `scripts/design-artifacts/fixtures/known-differences/`, which this adapter runs the very same
// implementation against. What no fixture there can reach is everything between a page and that
// implementation — a document fetched over HTTP, artifacts prefetched so a synchronous
// `readArtifact` can answer, a plane resolved from the two panels' own rasters rather than handed
// over, and a reader's status codes turned back into the three tokens §4 gives a reader.
//
// The last of those is the one worth a test of its own. `path-not-contained` and
// `artifact-too-large` are verdicts the *server* establishes and the engine only relays, so an
// adapter that collapsed them into "could not read" would leave two of the three unreachable from
// the browser — and the traversal is the one worth seeing.

import assert from "node:assert/strict";
import {
    MARK,
    SOURCES,
    WHITE,
    catalogRoutes,
    fillRect,
    knownDifferencesJson,
    png,
    raster,
    scope,
    withFetch,
    world,
} from "./support/knownDifferences.js";
import { evaluateComparison } from "../src/parity/acceptance.js";

describe("evaluateComparison", () => {
    it("says nothing at all when the catalog publishes no document", async () => {
        const report = await withFetch({}, () =>
            evaluateComparison(SOURCES, scope(world()), {}),
        );
        assert.equal(report.state, "absent");
        assert.deepEqual(report.statuses, {});
        assert.equal(report.scores, null);
    });

    it("tells a document it could not fetch apart from one that is not there", async () => {
        // Folding a 401 or a 500 into "absent" hides the band on exactly the pages where an
        // acceptance exists and went unevaluated — which reads to a viewer as a clean bill of
        // health for a comparison nobody measured.
        const scene = world();
        for (const status of [401, 500, 503] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            routes[SOURCES.documentUrl] = status;
            const report = await withFetch(routes, () =>
                evaluateComparison(SOURCES, scope(scene), {}),
            );
            assert.equal(report.state, "unavailable", `HTTP ${status}`);
        }
    });

    it("accepts the recorded difference and reports three separate numbers", async () => {
        const scene = world();
        const report = await withFetch(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(report.state, "evaluated");
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        assert.deepEqual(report.suppressing, ["glyph"]);
        assert.ok(report.scores, "a decodable pair must be scored");
        // The raw finding survives acceptance — the whole reason this is not an ignore rectangle.
        assert.ok(report.scores!.raw < 100, "the pair really does differ");
        // Nothing outside the mask differs, so what is left is a perfect match.
        assert.equal(report.scores!.unaccepted, 100);
        // And the accepted region is measured on its own, not as a difference of the other two.
        assert.ok(report.scores!.accepted < 100);
    });

    it("relays the reader's own tokens rather than collapsing them", async () => {
        const scene = world();
        for (const [status, reason] of [
            [403, "path-not-contained"],
            [413, "artifact-too-large"],
            [404, "artifact-unreadable"],
        ] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            routes["/m3/parity/known-differences/glyph/mask.png"] = status;
            const report = await withFetch(routes, () =>
                evaluateComparison(SOURCES, scope(scene), {}),
            );
            assert.deepEqual(
                report.validationFailures,
                [{ id: "glyph", reason }],
                `HTTP ${status}`,
            );
            assert.deepEqual(
                report.suppressing,
                [],
                "a refused record suppresses nothing",
            );
        }
    });

    it("still reaches the document's own verdict when the pair cannot be decoded", async () => {
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.candidateUrl] = 404;
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        // No comparison means no gate has fired, so the acceptance is out of scope rather than
        // invalidated — a comparison that could not be measured is not evidence against a record.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.equal(report.scores, null);
        assert.deepEqual(report.suppressing, []);
        // And it says WHY there are no scores. Without this the band cannot tell a comparison it
        // could not fetch from one the catalog has nothing to say about: both are a set of
        // `out-of-scope` rows and a null score, and only one of them is a clean bill of health.
        assert.equal(report.pair, "unavailable");
    });

    it("refuses to score reference bytes the page's own digest does not describe", async () => {
        // The digest comes from the catalog as the page was assembled; the raster comes from a
        // stable URL a browser cache may answer for five minutes. A catalog that republishes in
        // place therefore has a window where fresh metadata meets stale pixels — and the
        // fingerprint gate, being a string comparison against that metadata, passes. A mask would
        // then suppress a region of a generation nobody gated it against, which is precisely the
        // silent suppression the contract exists to prevent.
        const scene = world();
        const stale = fillRect(raster(32, 24, WHITE), MARK, [10, 90, 190, 255]);
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.referenceUrl] = png(stale);
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(report.pair, "unavailable");
        assert.equal(report.scores, null);
        // Not an acceptance verdict either way: which generation is the stale one is not knowable
        // from here, so neither side's pixels are evidence about the record.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.deepEqual(report.suppressing, []);
    });

    it("scores a catalog that publishes no digest rather than pre-empting the engine", async () => {
        // `reference-hash-missing` is the engine's verdict to reach. A generation check that fired
        // on a null digest would turn every such catalog into an unevaluated page, replacing a
        // record-level refusal with a comparison-level one.
        const scene = world();
        const report = await withFetch(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            () =>
                evaluateComparison(
                    SOURCES,
                    scope(scene, { referenceSha256: null }),
                    {},
                ),
        );
        assert.equal(report.pair, "scored");
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["reference-hash-missing"] },
        });
        assert.deepEqual(
            report.suppressing,
            [],
            "a refused record suppresses nothing",
        );
    });

    it("marks a wholesale document rejection as such, not as an empty verdict", async () => {
        // `duplicate-id` is attributed to the first spelling seen, so it carries an `id` exactly
        // like a per-record refusal — while `statuses` is absent, because no record was judged. A
        // reader that told the two apart by that `id` would show scores over an empty list and
        // explain nothing.
        const scene = world();
        const doc = JSON.parse(knownDifferencesJson(scene)) as {
            acceptances: unknown[];
        };
        doc.acceptances.push({ ...(doc.acceptances[0] as object) });
        const report = await withFetch(
            catalogRoutes(scene, JSON.stringify(doc)),
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(report.documentRejected, true);
        assert.deepEqual(report.statuses, {});
        assert.deepEqual(report.validationFailures, [
            { id: "glyph", reason: "duplicate-id" },
        ]);
        assert.deepEqual(report.suppressing, []);
    });

    it("projects the tag index into the canonical plane before gating on it", async () => {
        // The index publishes render pixels; `element.bounds` is canonical. Handing the raw index to
        // the engine compares two coordinate systems, and §4 names the result: an element that never
        // moved is reported as moved. Here the plane's origin is non-zero, so the two differ — and
        // the acceptance only stays `valid` if the projection happened.
        const scene = world();
        const plane = scene.plane;
        assert.ok(
            plane.box.x > 0 || plane.box.y > 0,
            "the fixture must exercise a cropped plane",
        );
        const doc = knownDifferencesJson(scene, {
            element: {
                kind: "tag",
                tag: "glyph",
                // The mark's box in CANONICAL coordinates — what an author records.
                bounds: scene.local,
                tolerance: 0.1,
            },
        });
        // …and the index reports the same node in RENDER pixels, which is the mark's box in the
        // full raster.
        const renderBounds = MARK;
        const report = await withFetch(catalogRoutes(scene, doc), () =>
            evaluateComparison(SOURCES, scope(scene), {
                glyph: { count: 1, bounds: renderBounds },
            }),
        );
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        assert.deepEqual(report.suppressing, ["glyph"]);
    });

    it("refuses an acceptance authored for another system", async () => {
        const scene = world();
        const doc = knownDifferencesJson(scene, { system: "wear-m3" });
        const report = await withFetch(catalogRoutes(scene, doc), () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        // Served preview and reference ids are unique only *within* a system, so scope matching uses
        // every recorded field. Dropping `system` would let this mask suppress pixels in a catalog
        // nobody accepted anything for.
        assert.deepEqual(report.statuses, {
            glyph: { status: "out-of-scope" },
        });
        assert.deepEqual(report.suppressing, []);
    });

    it("reports a document past the ceiling as too large, not as absent", async () => {
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.documentUrl] = 413;
        const report = await withFetch(routes, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.equal(
            report.state,
            "evaluated",
            "a refused document is not an absent one",
        );
        assert.deepEqual(report.validationFailures, [
            { reason: "document-too-large" },
        ]);
    });
});
