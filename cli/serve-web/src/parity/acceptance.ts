// The browser half of `compose-preview-known-differences/v1` — and it is an *adapter*, not an
// implementation.
//
// §4 asks for two engines that agree about what an acceptance means, and shared conformance fixtures
// to keep them honest. This file takes the stronger option where it is available: the browser runs
// the **same module** `design-artifacts` runs, so the two cannot disagree at all, and the fixtures go
// on doing their job against `design-parity`, which is a genuine second implementation in another
// repository. That was only possible once the reader stopped needing `node:zlib` and `node:crypto`
// — see `png-lite.mjs`'s header for why the alternative, decoding through an `<img>` onto a canvas,
// is not an option here: it normalises every colour type to 8-bit RGBA and so cannot see the
// mask-encoding rules the contract spends a section on.
//
// What is left for this file is everything the engine deliberately does not do:
//
// - **Fetching**, with the three reader obligations §4 names discharged on the server side and
//   reported as status codes — 403 `path-not-contained`, 413 `artifact-too-large`, 404
//   `artifact-unreadable`. Collapsing those into one failure would leave two of the three
//   unreachable, and the traversal is the one worth seeing.
// - **Prefetching**, because `readArtifact` is synchronous by design: the evaluation ladder is a
//   sequence of ordering requirements (preflight strictly before decode, gates strictly before
//   scoring) and threading a promise through it would turn every one of those into a race.
// - **Deciding what a comparison is**: the scope fields, the plane, and the canonical rasters both
//   sides are gated in.

import {
    BUDGET,
    canonicalRaster,
    decodePng,
    evaluateKnownDifferences,
    preflightPng,
    projectTagIndex,
    resolvePlane,
    scoreComparison,
    type ArtifactAnswer,
    type Catalog,
    type Raster,
    type ReadOptions,
    type TagIndex,
} from "./engine.js";

/** The identity half of the comparison, straight off the page's locator. */
export interface AcceptanceScope {
    system: string;
    component: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides: Record<string, string>;
    /** The served reference's digest. Absent is `reference-hash-missing`, refused, not invalidated. */
    referenceSha256?: string | null;
}

export interface AcceptanceSources {
    /** `…/parity/known-differences.json`. A 404 means the catalog has accepted nothing. */
    documentUrl: string;
    /** `path` is `<id>/<file>`, exactly as the document spells it. */
    artifactUrl: (path: string) => string;
    referenceUrl: string;
    candidateUrl: string;
}

export interface AcceptanceStatus {
    status: string;
    causes?: string[];
    reasons?: string[];
}

export interface AcceptanceReport {
    /**
     * Three outcomes, not two.
     *
     * `absent` is a catalog that has accepted nothing — the ordinary case, and the one the band says
     * nothing about. `unavailable` is a catalog that has, and whose document this page could not
     * fetch: an auth failure, a server error, a network drop. Folding the second into the first
     * would hide the band on exactly the pages where an acceptance exists and went unevaluated,
     * which reads to a viewer as "nothing is accepted here" — a clean bill of health for a page that
     * measured nothing. The page only carries this evaluator at all because the *server* found a
     * document, so absence at this point is already surprising.
     */
    state: "absent" | "unavailable" | "evaluated";
    statuses: Record<string, AcceptanceStatus>;
    validationFailures: Array<{ id?: string; reason: string }>;
    /** The three scores, or null when the pair could not be decoded. */
    scores: { raw: number; accepted: number; unaccepted: number } | null;
    /** Ids whose mask reached the scoring union — status `valid`, and no other. */
    suppressing: string[];
}

function empty(state: AcceptanceReport["state"]): AcceptanceReport {
    // A function rather than a shared frozen object: the report is handed to a component that reads
    // it and could reasonably sort or filter it, and two pages sharing one array is the kind of
    // aliasing that only shows up once someone does.
    return { state, statuses: {}, validationFailures: [], scores: null, suppressing: [] };
}

/**
 * Evaluate this catalog's acceptances against one comparison, and score it.
 *
 * Returns `published: false` when the catalog carries no document — which is every catalog until it
 * accepts something, and is why the route answers 404 rather than inventing an empty document for
 * the engine to judge.
 */
export async function evaluateComparison(
    sources: AcceptanceSources,
    scope: AcceptanceScope,
    tagIndex: TagIndex,
): Promise<AcceptanceReport> {
    const document = await fetchDocument(sources.documentUrl);
    if (document.state === "absent") return empty("absent");
    if (document.state === "unavailable") return empty("unavailable");

    // The two rasters, decoded by the contract's own reader rather than by the browser's. Both are
    // needed before any gate can run: the plane gate samples their pixels, and the candidate gate
    // compares inside the mask at canonical resolution.
    const pair = await fetchPair(sources);
    if (!pair) {
        // Nothing here is a verdict about the *document*, so the evaluation still runs — with no
        // comparison, which is the validation-only pass. An acceptance is then `out-of-scope` rather
        // than falsely invalidated by a comparison that could not be measured.
        const artifacts = await prefetch(document.text, sources.artifactUrl);
        const result = evaluateKnownDifferences({
            documentText: document.text,
            readArtifact: reader(artifacts),
            comparison: null,
        });
        return {
            state: "evaluated",
            statuses: result.statuses ?? {},
            validationFailures: result.validationFailures,
            scores: null,
            suppressing: (result.survivingMasks ?? []).map((entry) => entry.id),
        };
    }

    const resolved = resolvePlane(pair.reference, pair.candidate);
    const artifacts = await prefetch(document.text, sources.artifactUrl);
    const result = evaluateKnownDifferences({
        documentText: document.text,
        readArtifact: reader(artifacts),
        comparison: {
            ...scope,
            referenceSha256: scope.referenceSha256 ?? null,
            plane: resolved.plane,
            canonicalReference: canonicalRaster(
                pair.reference,
                resolved.boxes.reference,
                resolved.plane,
            ),
            canonicalCandidate: canonicalRaster(
                pair.candidate,
                resolved.boxes.candidate,
                resolved.plane,
            ),
            // **Projected, not passed through.** The index publishes `boundsInRoot` in render
            // pixels and says so on the wire; an acceptance's `element.bounds` is its baseline in
            // the canonical plane, and the element gate compares the two directly. §4 names the
            // failure for skipping this: an engine that expects canonical bounds from the index
            // reports `element-moved` for an element that never moved — a false invalidation with a
            // plausible explanation attached, which nothing surfaces. The transform belongs to the
            // comparison (D1), and this is the comparison.
            tagIndex: projectTagIndex(tagIndex, resolved.boxes.candidate, resolved.plane),
        },
    });

    // I5, as one line: only the masks the gates left `valid` reach the union. `resolved`,
    // `invalidated` and `refused` suppress nothing, and the engine has already applied that rule —
    // reapplying it here from `statuses` would be a second copy of the precedence table.
    const survivingMasks = result.survivingMasks ?? [];
    const scores = scoreComparison({
        reference: pair.reference,
        candidate: pair.candidate,
        referenceBox: resolved.boxes.reference,
        candidateBox: resolved.boxes.candidate,
        plane: resolved.plane,
        masks: survivingMasks.map((entry) => entry.mask),
    });

    return {
        state: "evaluated",
        statuses: result.statuses ?? {},
        validationFailures: result.validationFailures,
        scores: {
            raw: scores.raw,
            accepted: scores.accepted,
            unaccepted: scores.unaccepted,
        },
        suppressing: survivingMasks.map((entry) => entry.id),
    };
}

/**
 * Walk the whole acceptance set against the catalog, with no comparison at all.
 *
 * Per-comparison evaluation is not the whole job, and the gap is a *shape* rather than a rule: an
 * acceptance naming a removed or renamed preview, reference, component or variant is never scoped
 * into any focused comparison, so an engine that only ever runs inside one leaves it permanently
 * absent from the browser while `design-parity` reports `orphaned-target` for the same record. That
 * is the "invisible forever" failure the rule exists to prevent, reintroduced by where the
 * evaluation is called from.
 *
 * No rasters are decoded — a validation-only pass reaches every document-level and record-level
 * refusal, which is exactly the set this walk is for.
 */
export async function walkCatalog(
    sources: Pick<AcceptanceSources, "documentUrl" | "artifactUrl">,
    catalog: Catalog,
): Promise<AcceptanceReport> {
    const document = await fetchDocument(sources.documentUrl);
    if (document.state === "absent") return empty("absent");
    if (document.state === "unavailable") return empty("unavailable");
    const artifacts = await prefetch(document.text, sources.artifactUrl);
    const result = evaluateKnownDifferences({
        documentText: document.text,
        readArtifact: reader(artifacts),
        comparison: null,
        catalog,
    });
    return {
        state: "evaluated",
        statuses: result.statuses ?? {},
        validationFailures: result.validationFailures,
        scores: null,
        suppressing: (result.survivingMasks ?? []).map((entry) => entry.id),
    };
}

/**
 * The document's text, or which of the two ways there isn't one.
 *
 * **A 404 is the only absence.** Anything else — 401, 500, a network drop — means the catalog has a
 * document this page could not read, and reporting that as "nothing accepted" would hide the band on
 * exactly the pages where an acceptance exists and went unevaluated. The page only carries this
 * evaluator because the *server* already found a document, so even the 404 is a surprise; it is
 * still the honest reading of one, because the document can be deleted between the page render and
 * the fetch.
 *
 * A 413 is turned into the text the engine would refuse rather than reported either way: the host
 * refuses an oversized document from its length, so nothing has allocated it, and the consumer that
 * owns `document-too-large` still needs to be able to say so.
 */
type DocumentFetch =
    | { state: "absent" }
    | { state: "unavailable" }
    | { state: "text"; text: string };

async function fetchDocument(url: string): Promise<DocumentFetch> {
    let response: Response;
    try {
        response = await fetch(url, { credentials: "same-origin" });
    } catch {
        return { state: "unavailable" };
    }
    if (response.status === 404) return { state: "absent" };
    if (response.status === 413) {
        // A string the engine measures as over the ceiling, without transferring one. The ceiling is
        // in UTF-8 bytes and this is ASCII, so its length is its byte length.
        return { state: "text", text: "x".repeat(1024 * 1024 + 1) };
    }
    if (!response.ok) return { state: "unavailable" };
    try {
        return { state: "text", text: await response.text() };
    } catch {
        return { state: "unavailable" };
    }
}

async function fetchPair(
    sources: AcceptanceSources,
): Promise<{ reference: Raster; candidate: Raster } | null> {
    try {
        const [reference, candidate] = await Promise.all([
            fetchRaster(sources.referenceUrl),
            fetchRaster(sources.candidateUrl),
        ]);
        if (!reference || !candidate) return null;
        return { reference, candidate };
    } catch {
        return null;
    }
}

async function fetchRaster(url: string): Promise<Raster | null> {
    const response = await fetch(url, { credentials: "same-origin" });
    if (!response.ok) return null;
    const bytes = new Uint8Array(await response.arrayBuffer());
    try {
        return decodePng(bytes);
    } catch {
        // A comparison side this reader cannot decode is not an acceptance verdict — it is a
        // comparison that cannot be measured, and the caller falls back to the validation-only pass.
        return null;
    }
}

/** What the two-round prefetch keeps for one path: a bounded header prefix, and the full file only
 *  once the prefix has earned it. */
interface PrefetchedArtifact {
    /** The header-pass answer: a prefix and the whole file's size, or the reader token that stands
     *  in for it. Always present — every declared path gets a header read. */
    header: ArtifactAnswer;
    /** The decode-pass answer, present only for a path whose header preflight came back clean. A
     *  full read of a path without this is `artifact-unreadable`, which is safe: the engine only
     *  full-reads a record it already cleared through preflight, so this is never missing for one it
     *  actually asks to decode. */
    full?: Uint8Array;
}

/**
 * Fetch what the document names, in two rounds, before the synchronous evaluation begins.
 *
 * The reference reader in `known-differences.mjs` bounds its memory not by fetching little but by
 * reading one record at a time and **retaining nothing** — the header preflight reads a few dozen
 * bytes, and the whole file is read again, and dropped again, only inside the decode of a record the
 * preflight already cleared. A browser reader is synchronous, so it cannot fetch mid-ladder and must
 * have every answer in hand before the engine starts; the naive way to satisfy that — fetch every
 * artifact in full up front — reintroduces exactly the four gigabytes of simultaneously-held bytes
 * the reference design spends a paragraph avoiding, and does it *before* a single preflight has had
 * the chance to refuse a record.
 *
 * So this mirrors the reference reader's two phases instead of collapsing them:
 *
 * 1. A bounded **prefix** of every declared path — `maxPreflightBytes`, streamed and cut off rather
 *    than allocated whole, so a hostile eight-megabyte artifact costs four kilobytes here. That is
 *    the read the header preflight runs on, and it is enough for it: a conforming header resolves
 *    within {@link MAX_CONFORMING_HEADER_BYTES}, and one that does not is `header-invalid` — which is
 *    the same verdict on the same bytes the reference reader reaches, because the engine caps its own
 *    view to the same constant regardless of what a reader hands over.
 * 2. The **full body** of only the paths whose prefix preflights cleanly and sits within the byte
 *    cap. That set is a superset of the records the engine will actually decode — it drops the
 *    mask-encoding and pixel-budget filters, which only ever *remove* records — so the engine never
 *    asks to decode a path this round skipped, while a flood of malformed, oversized, animated, or
 *    non-PNG artifacts is refused on its prefix alone and never fetched in full.
 *
 * What this must not do is *filter the header round*: a record whose path is illegal is still
 * fetched-and-refused rather than skipped, so the engine sees the reader's answer instead of an
 * absence this file invented. The paths are discovered by parsing the document leniently — a parse
 * that fails here changes nothing, because the engine parses it again and owns `document-unreadable`.
 */
async function prefetch(
    documentText: string,
    artifactUrl: (path: string) => string,
): Promise<Map<string, PrefetchedArtifact>> {
    const artifacts = new Map<string, PrefetchedArtifact>();
    let parsed: unknown;
    try {
        parsed = JSON.parse(documentText);
    } catch {
        return artifacts;
    }
    const acceptances = (parsed as { acceptances?: unknown })?.acceptances;
    if (!Array.isArray(acceptances)) return artifacts;

    const paths = new Set<string>();
    for (const record of acceptances) {
        const id = (record as { id?: unknown })?.id;
        if (typeof id !== "string") continue;
        for (const key of ["mask", "acceptedCandidate"] as const) {
            const value = (record as Record<string, unknown>)[key];
            if (typeof value === "string") paths.add(`${id}/${value}`);
        }
    }

    // Round one: a bounded prefix of everything.
    await Promise.all(
        [...paths].map(async (path) => {
            artifacts.set(path, { header: await fetchPrefix(artifactUrl(path), BUDGET.maxPreflightBytes) });
        }),
    );

    // Round two: the full body of only the prefixes that earned it.
    await Promise.all(
        [...artifacts].map(async ([path, entry]) => {
            if (!headerEarnsFullRead(entry.header)) return;
            const full = await fetchArtifact(artifactUrl(path));
            if (full instanceof Uint8Array) entry.full = full;
            // A body that turned unreadable between the two rounds is left without `full`; the engine
            // re-reads and reaches `artifact-unreadable` on the decode pass, the same as the
            // reference reader when a tree moves under a long evaluation.
        }),
    );

    return artifacts;
}

/** True when a prefix's header is clean enough that the engine might decode the record — the
 *  superset test that keeps round two from ever under-fetching a body the engine will ask for. */
function headerEarnsFullRead(header: ArtifactAnswer): boolean {
    if (!("bytes" in header)) return false;
    if (header.byteLength > BUDGET.maxArtifactBytes) return false;
    const preflight = preflightPng(header.bytes, { byteLength: header.byteLength });
    return !("error" in preflight) && !preflight.animated;
}

/**
 * Fetch at most `limit` bytes of `url`, and the whole file's size, without allocating the rest.
 *
 * A `Range` request is the ask, but a server that ignores it and answers `200` must not defeat the
 * bound — so the body is streamed and cancelled once `limit` bytes are in hand rather than trusting
 * the status. `byteLength` comes from `Content-Range`'s total when the server honoured the range, and
 * from `Content-Length` otherwise; a file the server will not size is read to its (bounded) end and
 * measured by what arrived.
 */
async function fetchPrefix(url: string, limit: number): Promise<ArtifactAnswer> {
    let response: Response;
    try {
        response = await fetch(url, {
            credentials: "same-origin",
            headers: { Range: `bytes=0-${limit - 1}` },
        });
    } catch {
        return { error: "artifact-unreadable" };
    }
    if (response.status === 403) return { error: "path-not-contained" };
    if (response.status === 413) return { error: "artifact-too-large" };
    if (!response.ok && response.status !== 206) return { error: "artifact-unreadable" };

    const total = totalBytesFromHeaders(response);
    const bytes = await readAtMost(response, limit);
    if (!bytes) return { error: "artifact-unreadable" };
    // Absent a server-declared size, the artifact is at least what we read; the header preflight only
    // needs `byteLength` to enforce the byte cap, and understating it there is caught by the reader's
    // own guard, which refuses a `byteLength` shorter than the bytes handed over.
    return { bytes, byteLength: total ?? bytes.length };
}

/** The whole-file size a range response advertises, or `null` when the server declared none. */
function totalBytesFromHeaders(response: Response): number | null {
    const contentRange = response.headers.get("Content-Range");
    if (contentRange) {
        const total = /\/(\d+)\s*$/.exec(contentRange);
        if (total) return Number(total[1]);
    }
    if (response.status !== 206) {
        const length = response.headers.get("Content-Length");
        if (length !== null && /^\d+$/.test(length)) return Number(length);
    }
    return null;
}

/** Read a response body until `limit` bytes, then cancel the stream so the rest is never allocated. */
async function readAtMost(response: Response, limit: number): Promise<Uint8Array | null> {
    if (!response.body) {
        // No stream to bound — take the buffer and cut it, the one path where the full body is briefly
        // held. A fetch implementation without a readable body is the fallback, not the norm.
        try {
            const buffer = new Uint8Array(await response.arrayBuffer());
            return buffer.subarray(0, limit);
        } catch {
            return null;
        }
    }
    const chunks: Uint8Array[] = [];
    let held = 0;
    const streamReader = response.body.getReader();
    try {
        while (held < limit) {
            const { done, value } = await streamReader.read();
            if (done) break;
            chunks.push(value);
            held += value.length;
        }
    } catch {
        return null;
    } finally {
        await streamReader.cancel().catch(() => {});
    }
    const out = new Uint8Array(Math.min(held, limit));
    let offset = 0;
    for (const chunk of chunks) {
        if (offset >= out.length) break;
        const slice = chunk.subarray(0, out.length - offset);
        out.set(slice, offset);
        offset += slice.length;
    }
    return out;
}

async function fetchArtifact(url: string): Promise<ArtifactAnswer> {
    let response: Response;
    try {
        response = await fetch(url, { credentials: "same-origin" });
    } catch {
        return { error: "artifact-unreadable" };
    }
    // The three the host distinguishes, kept distinct. Each is a different verdict for the record,
    // and the engine honours only these two tokens from a reader — anything else it treats as
    // unreadable rather than trusting into the result.
    if (response.status === 403) return { error: "path-not-contained" };
    if (response.status === 413) return { error: "artifact-too-large" };
    if (!response.ok) return { error: "artifact-unreadable" };
    return new Uint8Array(await response.arrayBuffer());
}

/**
 * The synchronous reader the engine calls, over what `prefetch` already has.
 *
 * The header pass passes `{ prefix }` and gets the bounded prefix answer; the decode pass passes no
 * options and gets the full body. A path the prefetch never saw — one a record spells but no
 * acceptance declared, or one a lenient parse missed — is `artifact-unreadable`, which is what a
 * reader that could not open it would say. So is a full read of a path whose header never earned a
 * body: the engine only reaches that for a record its own preflight cleared, so it never happens for
 * one the engine actually decodes, and answering `artifact-unreadable` for the rest is the truthful
 * "nothing was fetched" rather than a truncated prefix masquerading as the whole file.
 */
function reader(artifacts: Map<string, PrefetchedArtifact>) {
    return (path: string, options?: ReadOptions): ArtifactAnswer | null => {
        const entry = artifacts.get(path);
        if (!entry) return null;
        if (options?.prefix !== undefined) return entry.header;
        return entry.full ?? { error: "artifact-unreadable" };
    };
}
