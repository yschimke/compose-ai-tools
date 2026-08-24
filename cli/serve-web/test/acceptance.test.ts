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
import { evaluateComparison } from "../src/parity/acceptance.js";

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
    { honourRange, declareSize = true }: { honourRange: boolean; declareSize?: boolean },
) {
    const requests: RecordedRequest[] = [];
    const impl = (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const headers = new Headers(init?.headers ?? {});
        const range = headers.get("Range");
        requests.push({ url, range });

        const body = routes[url];
        if (body === undefined) return Promise.resolve(new Response("not found", { status: 404 }));
        if (typeof body === "number") return Promise.resolve(new Response("no", { status: body }));
        if (typeof body === "string") return Promise.resolve(new Response(body));

        const bytes = body as Uint8Array;
        const match = range ? /^bytes=0-(\d+)$/.exec(range) : null;
        if (honourRange && match) {
            const end = Math.min(Number(match[1]), bytes.length - 1);
            const slice = bytes.subarray(0, end + 1);
            return Promise.resolve(
                new Response(slice as unknown as BodyInit, {
                    status: 206,
                    headers: { "Content-Range": `bytes 0-${end}/${bytes.length}` },
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
            return Promise.resolve(new Response(stream as unknown as BodyInit, { status: 200 }));
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
    options: { honourRange: boolean; declareSize?: boolean },
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
    const out = new Uint8Array(signatureAndIhdr.length + plte.length + tail.length);
    out.set(signatureAndIhdr, 0);
    out.set(plte, signatureAndIhdr.length);
    out.set(tail, signatureAndIhdr.length + plte.length);
    return out;
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
        const report = await withRecordingFetch(routes, { honourRange: true }, async (requests) => {
            const result = await evaluateComparison(SOURCES, scope(scene), {});
            const artifactRequests = requests.filter((request) =>
                request.url.startsWith("/m3/parity/known-differences/"),
            );
            const ranged = artifactRequests.filter((request) => request.range !== null);
            assert.equal(ranged.length, 2, "both artifacts are asked for as a bounded prefix");
            for (const request of ranged) {
                assert.equal(request.range, "bytes=0-4095", "the prefix is the named budget");
            }
            // And each is then read whole exactly once, because both preflight cleanly here.
            const whole = artifactRequests.filter((request) => request.range === null);
            assert.equal(whole.length, 2, "a clean header earns one full read");
            return result;
        });
        // The verdict is unchanged by any of it — the prefix is a resource bound, never a verdict.
        assert.deepEqual(report.statuses, { glyph: { status: "valid" } });
    });

    it("never reads an artifact whole when its prefix already refuses it", async () => {
        // The property the whole mechanism is for. A `PLTE` declaring eight kilobytes runs off the end
        // of a four-kilobyte prefix, so the header pass refuses it — and the body, which a hostile
        // catalog would make as large as the byte cap allows, is never fetched at all. Without the
        // two rounds this artifact is downloaded in full and *then* refused, which is the resource
        // exhaustion reached through the guard meant to prevent it.
        const scene = world();
        const oversized = pngWithOversizedPlte();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }));
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(routes, { honourRange: true }, async (requests) => {
            const result = await evaluateComparison(SOURCES, scope(scene), {});
            const maskRequests = requests.filter((request) =>
                request.url.endsWith("/glyph/mask.png"),
            );
            assert.equal(maskRequests.length, 1, "the refused mask is fetched once, not twice");
            assert.equal(maskRequests[0].range, "bytes=0-4095", "and only as a prefix");
            return result;
        });
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
                const ranged = new Headers(init?.headers ?? {}).get("Range") !== null;
                if (ranged) {
                    return Promise.resolve(
                        new Response("range not satisfiable", {
                            status: 416,
                            headers: { "Content-Range": "bytes */0" },
                        }),
                    );
                }
                return Promise.resolve(new Response(new Uint8Array(0) as unknown as BodyInit));
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
            globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
                const url = String(input);
                const ranged = new Headers(init?.headers ?? {}).get("Range") !== null;
                // The prefix is served honestly; only the whole-body read is refused.
                if (url.endsWith("/glyph/mask.png") && !ranged) {
                    return Promise.resolve(new Response("no", { status }));
                }
                return base.impl(input, init);
            }) as typeof fetch;
            try {
                const report = await evaluateComparison(SOURCES, scope(scene), {});
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
        assert.ok(peak <= 8, `at most eight artifact requests in flight, saw ${peak}`);
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
        assert.ok(big.length > 4096, "the artifact has to outgrow the prefix to show the bug");
        const routes = catalogRoutes(scene, knownDifferencesJson(scene, { acceptedCandidateSha256: sha256Hex(big) }));
        routes["/m3/parity/known-differences/glyph/accepted-candidate.png"] = big;

        const report = await withRecordingFetch(routes, { honourRange: false, declareSize: false }, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
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
        const routes = catalogRoutes(scene, knownDifferencesJson(scene, { maskSha256: sha256Hex(oversized) }));
        routes["/m3/parity/known-differences/glyph/mask.png"] = oversized;

        const report = await withRecordingFetch(routes, { honourRange: false }, () =>
            evaluateComparison(SOURCES, scope(scene), {}),
        );
        assert.deepEqual(report.statuses, {
            glyph: { status: "refused", reasons: ["header-invalid"] },
        });
    });
});
