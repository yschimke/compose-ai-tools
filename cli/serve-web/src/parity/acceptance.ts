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
    canonicalRaster,
    decodePng,
    evaluateKnownDifferences,
    projectTagIndex,
    resolvePlane,
    scoreComparison,
    sha256Hex,
    type ArtifactAnswer,
    type Catalog,
    type Raster,
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
    /**
     * Whether the engine refused the **document** rather than judging its records.
     *
     * The engine says this by omitting `statuses` entirely, and it is not recoverable from the
     * failures: `duplicate-id` is deliberately attributed to the first spelling seen and so carries
     * an `id`, exactly like a per-record refusal that does have a row. A reader that told the two
     * apart by that `id` would drop the loudest document-level verdict there is and leave the band
     * showing scores above an empty list — "this catalog accepts nothing here" and "this catalog's
     * document was refused" are the same picture with opposite meanings.
     */
    documentRejected: boolean;
    /**
     * What happened to the two rasters this comparison is scored from.
     *
     * `unavailable` is the one worth carrying: with no pair the engine runs its validation-only
     * pass, which reports every in-scope acceptance as `out-of-scope` — the token that ordinarily
     * means "authored for another comparison" and that a band therefore hides. A transient 503 on
     * the render lane would then be indistinguishable from a catalog that accepts nothing here.
     * `none` is a walk that never sought a pair at all.
     */
    pair: "scored" | "unavailable" | "none";
    statuses: Record<string, AcceptanceStatus>;
    /** `index` rather than `id` on a record too broken to have one — see `sortFailures`. */
    validationFailures: Array<{ id?: string; index?: number; reason: string }>;
    /** The three scores, or null when the pair could not be decoded. */
    scores: { raw: number; accepted: number; unaccepted: number } | null;
    /** Ids whose mask reached the scoring union — status `valid`, and no other. */
    suppressing: string[];
}

function empty(state: AcceptanceReport["state"]): AcceptanceReport {
    // A function rather than a shared frozen object: the report is handed to a component that reads
    // it and could reasonably sort or filter it, and two pages sharing one array is the kind of
    // aliasing that only shows up once someone does.
    return {
        state,
        documentRejected: false,
        pair: "none",
        statuses: {},
        validationFailures: [],
        scores: null,
        suppressing: [],
    };
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
    if (
        !pair ||
        !currentGeneration(pair.referenceBytes, scope.referenceSha256)
    ) {
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
            documentRejected: result.statuses === undefined,
            pair: "unavailable",
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
            tagIndex: projectTagIndex(
                tagIndex,
                resolved.boxes.candidate,
                resolved.plane,
            ),
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
        documentRejected: result.statuses === undefined,
        pair: "scored",
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
 * Whether the reference bytes just fetched are the ones the page's metadata describes.
 *
 * The fingerprint gate is a string comparison between the record's `referenceSha256` and the
 * comparison's, and the comparison's comes from the catalog **as the page was assembled** — while
 * the reference raster is fetched separately from a stable URL that a browser cache may answer for
 * up to five minutes (`private, max-age=300`), and the render lane longer still. A catalog that
 * republishes in place therefore has a window in which fresh metadata and stale pixels meet: the
 * gate passes against a digest describing bytes nobody scored, and a mask suppresses a region of a
 * generation it was never gated against. Silent suppression is the one failure this contract exists
 * to prevent, so the two are bound together here rather than trusted to agree.
 *
 * A mismatch is `pair: "unavailable"`, not an acceptance verdict: which generation is the stale one
 * is not knowable from here, and neither side's pixels are evidence about a record. The band says
 * the comparison could not be evaluated, and a reload — past the cache window, or forced — resolves
 * it.
 *
 * A catalog that publishes **no** digest is passed straight through, so `reference-hash-missing`
 * stays the engine's verdict to reach rather than being pre-empted by a check that had nothing to
 * compare.
 */
function currentGeneration(
    referenceBytes: Uint8Array,
    published?: string | null,
): boolean {
    if (typeof published !== "string" || published === "") return true;
    return sha256Hex(referenceBytes) === published.toLowerCase();
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
        documentRejected: result.statuses === undefined,
        pair: "none",
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

/** The decoded pair, plus the reference's own bytes so its digest can be checked against. */
async function fetchPair(sources: AcceptanceSources): Promise<{
    reference: Raster;
    candidate: Raster;
    referenceBytes: Uint8Array;
} | null> {
    try {
        const [reference, candidate] = await Promise.all([
            fetchRaster(sources.referenceUrl),
            fetchRaster(sources.candidateUrl),
        ]);
        if (!reference || !candidate) return null;
        return {
            reference: reference.raster,
            candidate: candidate.raster,
            referenceBytes: reference.bytes,
        };
    } catch {
        return null;
    }
}

async function fetchRaster(
    url: string,
): Promise<{ raster: Raster; bytes: Uint8Array } | null> {
    const response = await fetch(url, { credentials: "same-origin" });
    if (!response.ok) return null;
    const bytes = new Uint8Array(await response.arrayBuffer());
    try {
        return { raster: decodePng(bytes), bytes };
    } catch {
        // A comparison side this reader cannot decode is not an acceptance verdict — it is a
        // comparison that cannot be measured, and the caller falls back to the validation-only pass.
        return null;
    }
}

/**
 * Fetch every artifact the document names, before the synchronous evaluation begins.
 *
 * The paths are discovered by parsing the document leniently — a parse that fails here changes
 * nothing, because the engine parses it again and owns the `document-unreadable` verdict. What this
 * must not do is *filter*: a record whose path is illegal is still fetched-and-refused rather than
 * skipped, so the engine sees the reader's answer instead of an absence this file invented.
 */
async function prefetch(
    documentText: string,
    artifactUrl: (path: string) => string,
): Promise<Map<string, ArtifactAnswer>> {
    const answers = new Map<string, ArtifactAnswer>();
    let parsed: unknown;
    try {
        parsed = JSON.parse(documentText);
    } catch {
        return answers;
    }
    const acceptances = (parsed as { acceptances?: unknown })?.acceptances;
    if (!Array.isArray(acceptances)) return answers;

    const paths = new Set<string>();
    for (const record of acceptances) {
        const id = (record as { id?: unknown })?.id;
        if (typeof id !== "string") continue;
        for (const key of ["mask", "acceptedCandidate"] as const) {
            const value = (record as Record<string, unknown>)[key];
            if (typeof value === "string") paths.add(`${id}/${value}`);
        }
    }

    await Promise.all(
        [...paths].map(async (path) => {
            answers.set(path, await fetchArtifact(artifactUrl(path)));
        }),
    );
    return answers;
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
 * A path the prefetch never saw — one a record spells but no acceptance declared, or one a lenient
 * parse missed — is `artifact-unreadable`, which is what a reader that could not open it would say.
 */
function reader(answers: Map<string, ArtifactAnswer>) {
    return (path: string): ArtifactAnswer | null => answers.get(path) ?? null;
}
