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
import { sha256Hex } from "../../../scripts/design-artifacts/png-lite.mjs";
import { evaluateComparison, walkCatalog } from "../src/parity/acceptance.js";

/** One recorded request: the path asked for, and the `Range` header if the caller sent one. */
interface RecordedRequest {
    url: string;
    range: string | null;
}

/**
 * A `fetch` that records every request and, optionally, honours `Range` the way a real static server
 * would — `206` with a `Content-Range` naming the whole size.
 *
 * The default `serve` above deliberately ignores `Range` and answers `200` with the entire body,
 * which is the *other* case worth covering: a host that cannot range-request must still be bounded,
 * because the adapter cuts the stream itself rather than trusting the status.
 */
function recordingFetch(
    routes: Record<string, Uint8Array | string | number>,
    {
        honourRange,
        declareSize = true,
        declaredSizes = {},
    }: {
        honourRange: boolean;
        declareSize?: boolean;
        /**
         * Sizes to *claim* for named paths, without transferring them.
         *
         * A hostile catalog does not have to send 64 MiB to make a reader plan for it — it declares
         * the length and lets the reader decide. Modelling that with a header rather than with real
         * bytes is both faithful and the only way to test an aggregate ceiling without allocating
         * one.
         */
        declaredSizes?: Record<string, number>;
    },
) {
    const requests: RecordedRequest[] = [];
    const impl = (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const headers = new Headers(init?.headers ?? {});
        const range = headers.get("Range");
        requests.push({ url, range });

        const body = routes[url];
        if (body === undefined)
            return Promise.resolve(new Response("not found", { status: 404 }));
        if (typeof body === "number")
            return Promise.resolve(new Response("no", { status: body }));
        if (typeof body === "string")
            return Promise.resolve(new Response(body));

        const bytes = body as Uint8Array;
        const declared = declaredSizes[url] ?? bytes.length;
        const match = range ? /^bytes=0-(\d+)$/.exec(range) : null;
        if (honourRange && match) {
            const end = Math.min(Number(match[1]), bytes.length - 1);
            const slice = bytes.subarray(0, end + 1);
            return Promise.resolve(
                new Response(slice as unknown as BodyInit, {
                    status: 206,
                    headers: { "Content-Range": `bytes 0-${end}/${declared}` },
                }),
            );
        }
        if (!declareSize) {
            // A chunked response: the body arrives as a stream and no header names its size.
            const stream = new ReadableStream({
                start(controller) {
                    controller.enqueue(bytes);
                    controller.close();
                },
            });
            return Promise.resolve(
                new Response(stream as unknown as BodyInit, { status: 200 }),
            );
        }
        return Promise.resolve(
            new Response(bytes as unknown as BodyInit, {
                headers: { "Content-Length": String(bytes.length) },
            }),
        );
    };
    return { requests, impl };
}

function withRecordingFetch<T>(
    routes: Record<string, Uint8Array | string | number>,
    options: {
        honourRange: boolean;
        declareSize?: boolean;
        declaredSizes?: Record<string, number>;
    },
    body: (requests: RecordedRequest[]) => Promise<T>,
) {
    const original = globalThis.fetch;
    const { requests, impl } = recordingFetch(routes, options);
    globalThis.fetch = impl as typeof fetch;
    return body(requests).finally(() => {
        globalThis.fetch = original;
    });
}

/**
 * A PNG whose `PLTE` declares far more data than the header prefix can hold.
 *
 * The chunk is well-formed except for its length, which is the point: a reader that walks it reaches
 * `IDAT` and decodes, while one bounded to a prefix runs out first. It is the artifact the whole
 * prefix mechanism is measured against, so it is built by hand rather than by the encoder.
 */
function pngWithOversizedPlte(): Uint8Array {
    const base = png(raster(4, 4, WHITE));
    const signatureAndIhdr = base.subarray(0, 8 + 25);
    const declared = 8000;
    const plte = new Uint8Array(12 + declared);
    new DataView(plte.buffer).setUint32(0, declared);
    plte.set([0x50, 0x4c, 0x54, 0x45], 4);
    const tail = base.subarray(8 + 25);
    const out = new Uint8Array(
        signatureAndIhdr.length + plte.length + tail.length,
    );
    out.set(signatureAndIhdr, 0);
    out.set(plte, signatureAndIhdr.length);
    out.set(tail, signatureAndIhdr.length + plte.length);
    return out;
}
/**
 * The fixture document repeated across [ids], so an aggregate ceiling has something to aggregate.
 *
 * Every record names the same shape of artifact; only the ids differ. The ceiling is only reachable
 * with several records because each *individual* artifact must stay under `maxArtifactBytes` — one
 * refused for busting the per-file cap is refused before its size is counted, and contributes
 * nothing to the total. Which is the whole reason the aggregate ceiling exists: the exhaustion is
 * built from files that are each perfectly legal.
 */
function repeatedRecords(
    scene: ReturnType<typeof world>,
    ids: string[],
): string {
    const parsed = JSON.parse(knownDifferencesJson(scene)) as {
        acceptances: Record<string, unknown>[];
    };
    const template = parsed.acceptances[0];
    parsed.acceptances = ids.map((id) => ({ ...template, id }));
    return JSON.stringify(parsed);
}

/**
 * The fixture document plus a record the engine refuses **before reading anything**.
 *
 * `bad id` is a perfectly ordinary string — so it survives the identity scan, and the document is
 * evaluated — but it is not a portable path segment, so `isSafeId` refuses the record and its two
 * artifacts are never read. The adapter still fetches their headers, because it plans leniently and
 * lets the engine own the verdict; what it must not do is count them toward the ceiling.
 */
function withUnreadableRecord(scene: ReturnType<typeof world>): string {
    const parsed = JSON.parse(knownDifferencesJson(scene)) as {
        acceptances: Record<string, unknown>[];
    };
    parsed.acceptances.push({ ...parsed.acceptances[0], id: "bad id" });
    return JSON.stringify(parsed);
}

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
            () =>
                evaluateComparison(SOURCES, scope(scene), {}, [
                    {
                        repository: "YSCHIMKE/M3-CATALOG",
                        number: 40,
                        state: "closed",
                    },
                ]),
        );
        assert.equal(report.state, "evaluated");
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        assert.deepEqual(
            { ...report.lifecycles },
            {
                glyph: {
                    issue: "yschimke/m3-catalog#40",
                    lifecycle: "closed",
                    stale: true,
                },
            },
        );
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

    it("reads a bounded prefix of every artifact before reading any of them whole", async () => {
        // The reference reader bounds its memory by reading a header, then reading the whole file
        // again only inside the decode of a record the preflight already cleared. A browser reader is
        // synchronous and must have every answer in hand first, so the naive adapter fetched all of
        // them in full up front — reintroducing the four gigabytes of simultaneously-held bytes the
        // reference design exists to avoid, *before* a single preflight could refuse anything.
        //
        // This pins the two rounds: every declared path is asked for with a bounded `Range` first.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        const report = await withRecordingFetch(
            routes,
            { honourRange: true },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const artifactRequests = requests.filter((request) =>
                    request.url.startsWith("/m3/parity/known-differences/"),
                );
                const ranged = artifactRequests.filter(
                    (request) => request.range !== null,
                );
                assert.equal(
                    ranged.length,
                    2,
                    "both artifacts are asked for as a bounded prefix",
                );
                for (const request of ranged) {
                    assert.equal(
                        request.range,
                        "bytes=0-4095",
                        "the prefix is the named budget",
                    );
                }
                // And each is then read whole exactly once, because both preflight cleanly here.
                const whole = artifactRequests.filter(
                    (request) => request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "a clean header earns one full read",
                );
                return result;
            },
        );
        // The verdict is unchanged by any of it — the prefix is a resource bound, never a verdict.
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
    });

    it("never fetches a body for a document past the aggregate ceiling", async () => {
        // The gap `readsNoArtifacts` cannot close. That one refuses a document from its *text*; this
        // one is refused from the reader's *sizes* — `document-too-large` against
        // `maxTotalArtifactBytes`, a verdict the engine reaches without decoding anything. Round one
        // has already answered every size, so the total is knowable before round two, and without
        // this gate the adapter retains full bodies right up until the engine says the document was
        // never readable. The ceiling bounds the legal case; this is the illegal one, and the
        // illegal one is what an attacker picks.
        //
        // The sizes are *declared*, not sent: a hostile catalog does not upload 64 MiB to make a
        // reader plan for it.
        const scene = world();
        // Five records x two artifacts x 7 MiB = 70 MiB, past the 64 MiB ceiling — and every file
        // individually under the 8 MiB per-artifact cap, so none is refused before it is counted.
        const ids = ["glyph", "glyph2", "glyph3", "glyph4", "glyph5"];
        const routes = catalogRoutes(scene, repeatedRecords(scene, ids));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
            routes[
                `/m3/parity/known-differences/${id}/accepted-candidate.png`
            ] = scene.accepted;
        }
        const each = 7 * 1024 * 1024;
        const declaredSizes = Object.fromEntries(
            ids.flatMap((id) =>
                ["mask.png", "accepted-candidate.png"].map((file) => [
                    `/m3/parity/known-differences/${id}/${file}`,
                    each,
                ]),
            ),
        );

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const artifactRequests = requests.filter((request) =>
                    request.url.startsWith("/m3/parity/known-differences/"),
                );
                assert.equal(
                    artifactRequests.length,
                    10,
                    "every declared path is still sized",
                );
                for (const request of artifactRequests) {
                    assert.equal(
                        request.range,
                        "bytes=0-4095",
                        `a body was fetched for a refused document: ${request.url}`,
                    );
                }
                return result;
            },
        );
        // And the verdict is the engine's own, unchanged by the adapter having skipped the round.
        assert.equal(report.documentRejected, true);
    });

    it("still fetches when the records the engine reads are under the ceiling", async () => {
        // The test that separates this gate from the naive one. Summing every path the *document
        // names* is an upper bound on the engine's total: `id-not-safe`, a schema failure,
        // `orphaned-target` and `path-not-contained` all refuse a record before its first read, so
        // their artifacts never count toward `maxTotalArtifactBytes`.
        //
        // Here one legal record is small and one `id-not-safe` record declares 80 MiB. The naive sum
        // is over the ceiling; the engine's is not. Gating on the naive sum would skip round two, and
        // the legal record — which the engine does ask to decode — would come back
        // `artifact-unreadable`: a verdict changed by a planner, on a document the engine evaluated.
        const scene = world();
        const doc = withUnreadableRecord(scene);
        const routes = catalogRoutes(scene, doc);
        routes["/m3/parity/known-differences/bad id/mask.png"] = scene.mask;
        routes["/m3/parity/known-differences/bad id/accepted-candidate.png"] =
            scene.accepted;
        const huge = 40 * 1024 * 1024; // 2 x 40 MiB, all of it on the record nobody reads.
        const declaredSizes = {
            "/m3/parity/known-differences/bad id/mask.png": huge,
            "/m3/parity/known-differences/bad id/accepted-candidate.png": huge,
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                // The safety property, asserted where it bites: every path the engine actually reads
                // was fetched whole.
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the evaluated record's bodies were never fetched",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses.glyph, { status: "valid" });
        assert.equal(report.documentRejected, false);
    });

    it("charges a record twice when it names one file for both artifacts", async () => {
        // A record may legitimately use the same path for `mask` and `acceptedCandidate` — the
        // engine says so explicitly, and reads it twice and charges it twice. The fetch map holds it
        // once, so a sum over map entries under-charges every such record and the ceiling arrives
        // late: the browser retains full bodies for a document that is about to be rejected.
        //
        // Five records x one aliased file x 7 MiB charged twice = 70 MiB, past the 64 MiB ceiling —
        // where counting unique paths sees 35 MiB and fetches everything.
        //
        // 7 MiB, not 9: an artifact past the 8 MiB per-artifact cap is refused per-record and never
        // full-read anyway, so a larger figure makes this test pass for a reason that has nothing to
        // do with the aliasing. The property is only observable in the band where each file is legal
        // and the aggregate is not.
        const scene = world();
        const ids = ["glyph", "glyph2", "glyph3", "glyph4", "glyph5"];
        const parsed = JSON.parse(knownDifferencesJson(scene)) as {
            acceptances: Record<string, unknown>[];
        };
        const template = parsed.acceptances[0];
        parsed.acceptances = ids.map((id) => ({
            ...template,
            id,
            // One file, named twice. Its digest has to answer for both fields.
            mask: "mask.png",
            acceptedCandidate: "mask.png",
            acceptedCandidateSha256: template.maskSha256,
        }));
        const routes = catalogRoutes(scene, JSON.stringify(parsed));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
        }
        const declaredSizes = Object.fromEntries(
            ids.map((id) => [
                `/m3/parity/known-differences/${id}/mask.png`,
                7 * 1024 * 1024,
            ]),
        );

        await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                await evaluateComparison(SOURCES, scope(scene), {});
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    0,
                    `an aliased artifact was under-charged and its body fetched: ${whole.map((r) => r.url).join(", ")}`,
                );
            },
        );
    });

    it("charges nothing for a record refused by the per-artifact cap", async () => {
        // `preflightRecord` reads a record's two prefixes and then returns *before* assigning
        // `artifactBytes` when either is past `maxArtifactBytes` — so the engine charges such a
        // record zero toward the aggregate ceiling, while its declared size is the largest number in
        // the document. A planner that counted it would over-estimate by gigabytes, skip round two,
        // and turn a perfectly legal sibling into `artifact-unreadable`.
        //
        // Here one record declares 200 MiB per artifact (refused per-record, charged zero) beside one
        // ordinary record the engine does read.
        const scene = world();
        const routes = catalogRoutes(
            scene,
            repeatedRecords(scene, ["glyph", "huge"]),
        );
        routes["/m3/parity/known-differences/huge/mask.png"] = scene.mask;
        routes["/m3/parity/known-differences/huge/accepted-candidate.png"] =
            scene.accepted;
        const declaredSizes = {
            "/m3/parity/known-differences/huge/mask.png": 200 * 1024 * 1024,
            "/m3/parity/known-differences/huge/accepted-candidate.png":
                200 * 1024 * 1024,
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the legal record's bodies were never fetched",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses.glyph, { status: "valid" });
    });

    it("does not count records the catalog orphans toward the ceiling", async () => {
        // The catalog-aware call site, and the reason `prefetch` needs the catalog the evaluation
        // gets. `orphaned-target` is a *pre-read* refusal: the engine charges an orphaned record
        // nothing toward the aggregate ceiling. A planner without the catalog cannot see that, counts
        // every orphan, and over-estimates — which is the direction that skips round two for a
        // document the engine evaluates and turns its readable records into `artifact-unreadable`.
        //
        // Four orphans at 7 MiB x 2 = 56 MiB the engine never charges, beside one resolvable record
        // at 14 MiB it does. Catalog-blind the sum is 70 MiB and the gate fires; catalog-aware it is
        // 14 MiB and the readable record is fetched.
        const scene = world();
        const ids = ["glyph", "orphan1", "orphan2", "orphan3", "orphan4"];
        const routes = catalogRoutes(scene, repeatedRecords(scene, ids));
        for (const id of ids) {
            routes[`/m3/parity/known-differences/${id}/mask.png`] = scene.mask;
            routes[
                `/m3/parity/known-differences/${id}/accepted-candidate.png`
            ] = scene.accepted;
        }
        const each = 7 * 1024 * 1024;
        const declaredSizes = Object.fromEntries(
            ids.flatMap((id) =>
                ["mask.png", "accepted-candidate.png"].map((file) => [
                    `/m3/parity/known-differences/${id}/${file}`,
                    each,
                ]),
            ),
        );
        // Every record names the same preview, so the catalog resolves them all or none — which is
        // no use here. The orphans are made orphans by giving the catalog a preview that matches
        // only the first record's `referenceId`... except they share that too. So instead the
        // catalog resolves the shared preview, and the orphans are re-pointed at one it lacks.
        const parsed = JSON.parse(repeatedRecords(scene, ids)) as {
            acceptances: Record<string, unknown>[];
        };
        for (const record of parsed.acceptances) {
            if (record.id !== "glyph") record.previewId = "no-such-preview";
        }
        routes["/m3/parity/known-differences.json"] = JSON.stringify(parsed);
        const template = JSON.parse(knownDifferencesJson(scene))
            .acceptances[0] as Record<string, string>;
        const catalog = {
            previews: [
                {
                    system: template.system,
                    id: template.previewId,
                    component: template.component,
                    variant: template.variant,
                    referenceIds: [template.referenceId],
                },
            ],
        };

        const report = await withRecordingFetch(
            routes,
            { honourRange: true, declaredSizes },
            async (requests) => {
                const result = await walkCatalog(SOURCES, catalog);
                const whole = requests.filter(
                    (request) =>
                        request.url.startsWith(
                            "/m3/parity/known-differences/glyph/",
                        ) && request.range === null,
                );
                assert.equal(
                    whole.length,
                    2,
                    "the resolvable record's bodies were never fetched — the orphans were counted",
                );
                return result;
            },
        );
        assert.equal(report.documentRejected, false);
        assert.equal(report.statuses.orphan1?.status, "refused");
    });

    it("never reads an artifact whole when its prefix already refuses it", async () => {
        // The property the whole mechanism is for. A `PLTE` declaring eight kilobytes runs off the end
        // of a four-kilobyte prefix, so the header pass refuses it — and the body, which a hostile
        // catalog would make as large as the byte cap allows, is never fetched at all. Without the
        // two rounds this artifact is downloaded in full and *then* refused, which is the resource
        // exhaustion reached through the guard meant to prevent it.
        const scene = world();
        const oversized = pngWithOversizedPlte();
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }),
        );
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(
            routes,
            { honourRange: true },
            async (requests) => {
                const result = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                const maskRequests = requests.filter((request) =>
                    request.url.endsWith("/glyph/mask.png"),
                );
                assert.equal(
                    maskRequests.length,
                    1,
                    "the refused mask is fetched once, not twice",
                );
                assert.equal(
                    maskRequests[0].range,
                    "bytes=0-4095",
                    "and only as a prefix",
                );
                return result;
            },
        );
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["header-invalid"] },
        });
    });

    it("reads an empty artifact as a short header, not as an unopenable file", async () => {
        // A zero-byte artifact makes `bytes=0-4095` unsatisfiable, and a range-honouring server answers
        // `416` with `Content-Range: bytes */0`. Treating that as a failed fetch reports
        // `artifact-unreadable`, while the filesystem reader opens the empty file happily and the
        // engine refuses its too-short header as `header-invalid` — two engines, one committed file,
        // different verdicts.
        //
        // `416` is only reachable here because the range starts at zero, which no non-empty resource
        // can fail to satisfy. So it is not an error to relay: it is the server saying the artifact is
        // empty, which is a fact the preflight is entitled to judge for itself.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        const base = recordingFetch(routes, { honourRange: true });
        const original = globalThis.fetch;
        globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input);
            if (url.endsWith("/glyph/mask.png")) {
                const ranged =
                    new Headers(init?.headers ?? {}).get("Range") !== null;
                if (ranged) {
                    return Promise.resolve(
                        new Response("range not satisfiable", {
                            status: 416,
                            headers: { "Content-Range": "bytes */0" },
                        }),
                    );
                }
                return Promise.resolve(
                    new Response(new Uint8Array(0) as unknown as BodyInit),
                );
            }
            return base.impl(input, init);
        }) as typeof fetch;
        try {
            const report = await evaluateComparison(SOURCES, scope(scene), {});
            assert.deepEqual(report.statuses, {
                glyph: { status: "refused", reasons: ["header-invalid"] },
            });
        } finally {
            globalThis.fetch = original;
        }
    });

    it("keeps the full read's own refusal token when an artifact changes between the rounds", async () => {
        // The header round can succeed and the body round still be refused — the tree moves, or a file
        // is swapped for an oversized one. `path-not-contained` and `artifact-too-large` are verdicts
        // only the server establishes, and dropping them here degrades the record to
        // `artifact-unreadable`, where the reference reader (which stats the file on its own second
        // read) reports the specific token. Same divergence class as the prefix itself: one contract,
        // two engines, different answers.
        const scene = world();
        for (const [status, token] of [
            [413, "artifact-too-large"],
            [403, "path-not-contained"],
        ] as const) {
            const routes = catalogRoutes(scene, knownDifferencesJson(scene));
            const base = recordingFetch(routes, { honourRange: true });
            const original = globalThis.fetch;
            globalThis.fetch = ((
                input: RequestInfo | URL,
                init?: RequestInit,
            ) => {
                const url = String(input);
                const ranged =
                    new Headers(init?.headers ?? {}).get("Range") !== null;
                // The prefix is served honestly; only the whole-body read is refused.
                if (url.endsWith("/glyph/mask.png") && !ranged) {
                    return Promise.resolve(new Response("no", { status }));
                }
                return base.impl(input, init);
            }) as typeof fetch;
            try {
                const report = await evaluateComparison(
                    SOURCES,
                    scope(scene),
                    {},
                );
                assert.deepEqual(
                    report.statuses,
                    { glyph: { status: "refused", reasons: [token] } },
                    `a ${status} on the full read must keep its own token`,
                );
            } finally {
                globalThis.fetch = original;
            }
        }
    });

    it("keeps its requests inside a fixed concurrency, however many records there are", async () => {
        // `Promise.all` over a 256-record catalog opens 512 requests at once, and the repository's own
        // route holds a whole artifact in memory for each — four gigabytes on the *server* to return
        // four kilobytes apiece. A pool bounds that peak on both sides. It is a rate and not a budget:
        // every request that would have been made is still made, and every answer is unchanged, which
        // is why no verdict here moves.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        let inFlight = 0;
        let peak = 0;
        const original = globalThis.fetch;
        const base = recordingFetch(routes, { honourRange: true });
        globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
            inFlight += 1;
            peak = Math.max(peak, inFlight);
            return base.impl(input, init).finally(() => {
                inFlight -= 1;
            });
        }) as typeof fetch;
        try {
            const report = await evaluateComparison(SOURCES, scope(scene), {});
            assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
        } finally {
            globalThis.fetch = original;
        }
        assert.ok(
            peak <= 8,
            `at most eight artifact requests in flight, saw ${peak}`,
        );
    });

    it("keeps an artifact's true size when the response never declares one", async () => {
        // A chunked `200` carries no `Content-Length`, and a server that ignores `Range` sends one for
        // every artifact. The prefix is then all this adapter has seen, and recording *its* length as
        // the artifact's makes the header pass disagree with the decode pass about the same
        // unchanged file — which the engine reads as an artifact that changed underneath the
        // evaluation and refuses as `artifact-unreadable`.
        //
        // Invisible to every other test here because their artifacts are smaller than the prefix, so
        // the truncated length and the real one coincide. This one is deliberately past 4096 bytes.
        const scene = world();
        // Deterministic noise, because a flat raster deflates to well under the prefix and the whole
        // point of this case is an artifact that outgrows it.
        const noisy = raster(64, 64, WHITE);
        for (let i = 0; i < noisy.pixels.length; i += 4) {
            noisy.pixels[i] = (i * 37) % 251;
            noisy.pixels[i + 1] = (i * 89) % 241;
            noisy.pixels[i + 2] = (i * 151) % 239;
        }
        const big = png(noisy);
        assert.ok(
            big.length > 4096,
            "the artifact has to outgrow the prefix to show the bug",
        );
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, {
                acceptedCandidateSha256: sha256Hex(big),
            }),
        );
        routes["/m3/parity/known-differences/glyph/accepted-candidate.png"] =
            big;

        const report = await withRecordingFetch(
            routes,
            { honourRange: false, declareSize: false },
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        // Whatever the record's verdict is on its merits, it must not be "the file changed".
        const reasons = report.statuses.glyph?.reasons ?? [];
        assert.ok(
            !reasons.includes("artifact-unreadable"),
            `an unchanged artifact must not read as changed, got ${JSON.stringify(report.statuses.glyph)}`,
        );
    });

    it("reaches the same verdict from a server that ignores Range entirely", async () => {
        // `Range` is a request, and a static host may answer `200` with the whole body regardless.
        // That must cost bytes, never a different answer: the adapter cuts the stream itself, and the
        // engine caps its own header view to the same constant whatever a reader hands over. So the
        // oversized `PLTE` is `header-invalid` here too, rather than walking through to a decode.
        const scene = world();
        const oversized = pngWithOversizedPlte();
        const routes = catalogRoutes(
            scene,
            knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }),
        );
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(
            routes,
            { honourRange: false },
            () => evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["header-invalid"] },
        });
    });
});
