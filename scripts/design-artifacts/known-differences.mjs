/**
 * `compose-preview-known-differences/v1` — the committed known-difference contract, as executable
 * rules.
 *
 * Defined in this repo because `serve` is a consumer and the other wire contracts
 * (`compose-preview-references/v1`, `compose-preview-annotations/v1`,
 * `compose-preview-activity/v1`) already live here; `design-parity` and
 * `@design-parity/catalog-export` are the second consumer and the publisher.
 * [`COMPONENT_PARITY_WORKFLOW.md` §4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#the-normative-contract)
 * is the specification — this file is the reference implementation of it, and the
 * `fixtures/known-differences/` suite is what keeps the two, plus the two engines batch 05 writes,
 * from drifting apart.
 *
 * **What this module decides, and what it deliberately does not.** It decides every *verdict*: the
 * validation refusals, the five gates, the resolution test, the status precedence, and the exact
 * ordering of `validationFailures` (`statuses` is a *map* — it promises one entry per acceptance,
 * not an order). It does **not** compute `raw` / `accepted` / `unaccepted`: those are the separated
 * plane path in [`known-difference-score.mjs`](./known-difference-score.mjs), and the split is the
 * contract's own (I1 — every gate resolves before any score is computed). The seam between the two
 * is `survivingMasks`, the union of the `valid` acceptances' masks: it cannot be formed until the
 * gates have run, and it is the only thing the scorer needs from here.
 *
 * The canonical-plane rasters arrive **already resampled**, as inputs. That is the same seam: the
 * portable resampler is specified (see {@link resampleArea}) and pinned by its own fixture group,
 * but the gate cases pin gate semantics rather than re-deriving the resample in every one of them —
 * so a resampler divergence fails as a resampler divergence, which is the whole point of pinning
 * intermediate stages at all.
 */

import { MAX_CONFORMING_HEADER_BYTES, decodePng, preflightPng, sha256Hex } from "./png-lite.mjs";

/** The schema token a document must carry, exactly. */
export const KNOWN_DIFFERENCES_SCHEMA = "compose-preview-known-differences/v1";

/**
 * The budget, versioned with the schema.
 *
 * The six ceilings are *inclusive* — a document at exactly 256 acceptances, exactly 128 megapixels,
 * exactly 8192 px on a side, exactly 8 MiB per artifact or exactly 640 MiB of peak live raster is
 * legal, and one unit past refuses. A `>=` check would reject both and leave two engines free to
 * disagree about the case in between.
 *
 * `maxRasterBytes` is the one ceiling that is about the *reader* rather than the document, and it
 * exists because the others quietly implied it: a document inside every one of them could still
 * oblige an engine to hold well over a gigabyte, which is a resource decision the caps were making
 * without anyone taking it. It is measured by {@link peakRasterBytes} from the declared headers,
 * before a single raster is allocated.
 *
 * `maxPreflightBytes` is the odd one out and is not a ceiling on anything a document may declare: it
 * is how much of an artifact the reader must serve so the header preflight can reach a verdict. It
 * still has to be *named* for the same reason the others do — a prefix each engine sizes for itself
 * is a prefix two engines disagree about, and they would disagree precisely on the files that put
 * the most in front of their image data. {@link MAX_CONFORMING_HEADER_BYTES} is the proof that 4 KiB
 * is enough: a conforming artifact resolves the preflight within 1089 bytes, so the constant is
 * chosen with room to spare rather than fitted to the fixtures.
 */
export const BUDGET = {
  maxDocumentBytes: 1024 * 1024,
  maxAcceptances: 256,
  maxPixels: 128_000_000,
  maxAxis: 8192,
  maxArtifactBytes: 8 * 1024 * 1024,
  maxPreflightBytes: 4096,
  maxRasterBytes: 640 * 1024 * 1024,
};

/**
 * Bytes per pixel of a decoded raster, and the buffers one decode holds at its peak.
 *
 * Every decode normalises to 8-bit RGBA whatever the file's colour type is, so a raster is exactly
 * four bytes a pixel and the accounting needs no colour-type term. `DECODE_WORKING_MULTIPLE` is the
 * transient side: at its peak a decode holds the inflated scanline bytes and the raster it is
 * filling — for RGBA the output aliases the scanlines, for every other colour type the scanlines are
 * *smaller* than the raster — so two raster-sized buffers is the shape, and three is a bound that
 * covers the per-row filter byte (`h × (4w + 1)` is `4wh + h`) and every colour type at once without
 * a case analysis. Deliberately a bound rather than a measurement: an accounting an implementation
 * can under-shoot by being clever is one two engines disagree about.
 */
export const RASTER_BYTES_PER_PIXEL = 4;
export const DECODE_WORKING_MULTIPLE = 3;

/**
 * The peak live raster bytes a document obliges an engine to hold, from its **declared** headers.
 *
 *     peak = 4 × Σ(w·h over every artifact)  +  4 × 3 × max(w·h over every artifact)
 *
 * The first term is what is *retained*: the gates need both of a record's rasters and the union
 * needs the masks of the survivors, so every artifact's raster is live at once by the time a verdict
 * exists. The second is the *transient* working set of the one decode in flight; decodes are
 * sequential and each releases before the next, so charging the largest single artifact once is
 * exact rather than conservative. Resampling a record onto its canonical plane needs no term of its
 * own: the plane is the mask's own dimensions (`dimension-mismatch` is the verdict when it is not),
 * so a plane raster is bounded by a raster already counted.
 *
 * **Why this is not a restatement of `maxPixels`.** The two caps bind on different documents, and
 * both are reachable. 512 artifacts of 250,000 pixels total exactly 128 megapixels and peak at
 * ~515 MB — refused by `maxPixels`, nowhere near the memory ceiling. One record holding an
 * 8000 × 8000 mask and an 8000 × 8000 accepted candidate is *also* exactly 128 megapixels, inside
 * every axis and byte cap, and peaks at ~1.28 GB — legal under every cap `v1` had before this one,
 * which is the hole: the pixel cap chose a memory floor nobody had agreed to. 640 MiB is the figure
 * that keeps the first document legal and refuses the second.
 */
export function peakRasterBytes(totalPixels, largestArtifactPixels) {
  return (
    RASTER_BYTES_PER_PIXEL * totalPixels +
    RASTER_BYTES_PER_PIXEL * DECODE_WORKING_MULTIPLE * largestArtifactPixels
  );
}

// Not a comment's promise — the one relationship between those two constants, checked where it
// cannot rot. Shrinking the prefix below what a conforming header can occupy would turn legal
// palette artifacts into `header-invalid` at preflight, and the fixtures that would catch it are the
// ones a future edit is least likely to run.
if (BUDGET.maxPreflightBytes < MAX_CONFORMING_HEADER_BYTES) {
  throw new Error("maxPreflightBytes cannot be smaller than a conforming PNG header region");
}

/** `candidateTolerance` is an 8-bit channel distance and an integer; `element.tolerance` is real. */
export const CANDIDATE_TOLERANCE_RANGE = [0, 8];
export const ELEMENT_TOLERANCE_RANGE = [0, 0.25];

/** The exact spellings JavaScript treats as integer keys — no leading zeros, no `+`, no fraction. */
const CANONICAL_INTEGER = /^-?(0|[1-9][0-9]*)$/;

/** Ids that are fine as path segments and catastrophic as map keys. */
const RESERVED_IDS = new Set(["__proto__", "constructor", "prototype"]);

/**
 * Segment names Windows cannot open, whatever extension follows them.
 *
 * `artifacts/CON.png` commits fine, evaluates fine on POSIX, and cannot be created under that name on
 * Windows at all — so the offline engine reads a file the serving host reports as
 * `artifact-unreadable`, which is the divergence the "contained **and** portable" rule exists to
 * close. Reserved names apply with any extension, so the check is on the segment up to its first dot,
 * case-insensitively. A trailing dot or space is the same class: Windows silently strips it, so two
 * distinct committed names collapse onto one file there.
 */
const RESERVED_SEGMENTS = new Set([
  "con",
  "prn",
  "aux",
  "nul",
  ...Array.from({ length: 9 }, (_, i) => `com${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `lpt${i + 1}`),
]);

/**
 * The longest a single segment may be, in bytes.
 *
 * ext4, APFS, NTFS and every other filesystem a checkout of this repository plausibly lands on cap a
 * *component* at 255 — not the path, the component — so a 256-character id is a record a URL-backed
 * consumer fetches and evaluates happily while a normal `git checkout` cannot even create the
 * directory it names, and the offline engine reports `artifact-unreadable` for bytes the serving
 * host validated. That is the same host-versus-checkout divergence the reserved-name and
 * trailing-dot rules exist to close, so it gets the same treatment rather than a new token. The
 * character class is ASCII-only, checked first, so counting characters here counts bytes.
 *
 * Per segment and not per path on purpose: `PATH_MAX` is a property of the *reader's* working
 * directory, not of the document, so a total-length rule would make the same committed bytes legal
 * in one checkout and refused in another — which is the divergence, not a fix for it.
 */
const MAX_SEGMENT_LENGTH = 255;

function isPortableSegment(segment) {
  if (!SAFE_SEGMENT.test(segment)) return false;
  if (segment.length > MAX_SEGMENT_LENGTH) return false;
  if (segment === "." || segment === "..") return false;
  if (segment.endsWith(".") || segment.endsWith(" ")) return false;
  return !RESERVED_SEGMENTS.has(segment.split(".")[0].toLowerCase());
}

/** The one character class an `id` or an artifact path segment may use. */
const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;

const HEX64 = /^[0-9a-f]{64}$/;

/** RFC 3339 date-time, which is what the schema's `format: "date-time"` means. */
// The `T` and `Z` are case-insensitive — RFC 3339 says so in as many words — so an uppercase-only
// pattern refuses a legal timestamp, which is a wrong verdict rather than a missing check.
const RFC3339 = /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:[Zz]|([+-])(\d{2}):(\d{2}))$/;

/**
 * Shape **and** meaning. `2026-99-99T99:99:99Z` matches the punctuation and the digit counts and is
 * not a date, so a validator asserting the schema's `date-time` format refuses what a pattern check
 * accepts — the same gap the pattern was added to close, one level down. The calendar check is a
 * round-trip through `Date`, which is where leap years and month lengths already live.
 */
function isRfc3339(value) {
  const match = RFC3339.exec(value);
  if (!match) return false;
  const [, year, month, day, hour, minute, second, offsetSign, offsetHour, offsetMinute] = match;
  if (Number(month) < 1 || Number(month) > 12) return false;
  if (Number(day) < 1 || Number(day) > 31) return false;
  if (Number(hour) > 23 || Number(minute) > 59 || Number(second) > 60) return false;
  if (offsetHour !== undefined && (Number(offsetHour) > 23 || Number(offsetMinute) > 59)) return false;
  // **Second 60 only where a leap second can be inserted.** RFC 3339 admits `60` for exactly one
  // instant — `23:59:60` **UTC** — so `2026-01-01T12:00:60Z` matches the grammar and is not a
  // date-time, and a strict consumer refuses the record this evaluator would go on to gate.
  //
  // An earlier round declined this, and the decline was aimed at a different proposal: refusing
  // instants where no leap second was *actually* inserted needs the IERS table, which grows by
  // announcement and cannot live in a committed contract. This rule needs no table. It asks only
  // whether the instant is one where a leap second *could* be inserted, which is a property of the
  // clock, and it accepts every real leap second past and future — including one announced after
  // this code was written. That distinction is the whole of the disagreement, so the finding stands
  // and the earlier reasoning does not apply to it.
  //
  // **And the instant is a date, not just a time of day.** A leap second is inserted at the end of a
  // UTC *month*, so `2026-01-01T23:59:60Z` reads `23:59` and is still not a leap-second instant. An
  // earlier revision compared only the minute-of-day, modulo a day — which discards the very rollover
  // that makes the offset cases work, and admits `:60` on 334 days of the year. Month-end is as
  // static as the time of day is: no table, nothing to keep current.
  const utc = new Date(`${year}-${month}-${day}T00:00:00Z`);
  const dateIsReal =
    utc.getUTCFullYear() === Number(year) &&
    utc.getUTCMonth() + 1 === Number(month) &&
    utc.getUTCDate() === Number(day);
  if (!dateIsReal) return false;
  if (Number(second) === 60) {
    const offsetMinutes =
      offsetHour === undefined
        ? 0
        : (offsetSign === "-" ? -1 : 1) * (Number(offsetHour) * 60 + Number(offsetMinute));
    // The offset is applied to the whole date-time, so a local spelling that belongs to the previous
    // or next UTC day lands on the right day before the day is examined.
    const instant = utc.getTime() + (Number(hour) * 60 + Number(minute) - offsetMinutes) * 60_000;
    const moment = new Date(instant);
    if (moment.getUTCHours() !== 23 || moment.getUTCMinutes() !== 59) return false;
    // The minute this `:60` completes ends at 00:00. If that is the first of a month, the instant
    // sits on a month end.
    if (new Date(instant + 60_000).getUTCDate() !== 1) return false;
  }
  return true;
}

/**
 * The authoritative ordering for `reasons` and for `validationFailures`.
 *
 * Document-wide tokens lead, then identity, then structure, then artifacts — so a combined failure
 * serialises the same way in both engines and a reader sees the widest problem first.
 */
export const REASON_ORDER = [
  "document-unreadable",
  "document-too-large",
  "duplicate-id",
  "id-missing",
  "id-not-safe",
  "schema-invalid",
  "orphaned-target",
  "path-not-contained",
  "artifact-too-large",
  "header-invalid",
  "decode-failed",
  "dimension-mismatch",
  "mask-encoding-invalid",
  "animated-png",
  "mask-empty",
  "artifact-unreadable",
  "mask-hash-mismatch",
  "accepted-candidate-hash-mismatch",
  "reference-hash-missing",
  "tolerance-out-of-range",
  "acceptance-is-noop",
];

/** `causes` order, as the gate table lists them. `candidate-changed` never shares a list. */
export const CAUSE_ORDER = [
  "reference-changed",
  "plane-changed",
  "candidate-changed",
  "element-ambiguous",
  "element-moved",
];

const reasonRank = new Map(REASON_ORDER.map((token, index) => [token, index]));
const causeRank = new Map(CAUSE_ORDER.map((token, index) => [token, index]));

// ---------------------------------------------------------------------------------------------
// The portable pixel path (batch 00's D5, answers 1 and 5)
// ---------------------------------------------------------------------------------------------

// The named resampler and the outward-rounding rule are the contract's, but they are arithmetic
// over rasters and nothing else, so they live in their own module — `format-compare.js` takes the
// kernel without taking the ladder. Re-exported here because this is where the contract's readers
// have always found them.
export { resampleArea } from "./known-difference-resample.mjs";

function clamp8(value) {
  return Math.max(0, Math.min(255, Math.floor(value + 0.5)));
}

/**
 * D5 answer 5, in one function: a real-valued box becomes the **enclosing** integer box.
 *
 * Outward rather than nearest, and the same rule at every transform — a mask or a selection that
 * rounds inward is smaller than the region the author looked at, which is the direction that
 * silently stops covering pixels. Applied after the transform's arithmetic, never during it.
 */
export function enclosingBox({ x, y, width, height }) {
  const x0 = Math.floor(x);
  const y0 = Math.floor(y);
  const x1 = Math.ceil(x + width);
  const y1 = Math.ceil(y + height);
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

/**
 * D5 answer 6, the match metric shared by the candidate gate and the resolution test: the
 * **maximum absolute per-channel difference over R, G, B and A**, applied **per pixel**.
 *
 * Per-pixel rather than aggregate because an aggregate needs a second constant — how many
 * over-threshold pixels are too many — and a second constant is a second thing two engines pick
 * differently. All four channels because that is what the existing delta map already charges for
 * (`DIFF_CHANNEL_TOLERANCE` compares the same four), and an alpha-only change is a visible change.
 * The comparison is `>`, so a pixel exactly at the tolerance passes — the same inclusive convention
 * the tolerance ranges and the budget caps use.
 *
 * The mask is strictly binary, so "at the mask edge" is not a case: a canonical pixel is masked or
 * it is not, and only masked pixels are compared.
 */
export function pixelsAgree(a, aOffset, b, bOffset, tolerance) {
  return (
    Math.abs(a[aOffset] - b[bOffset]) <= tolerance &&
    Math.abs(a[aOffset + 1] - b[bOffset + 1]) <= tolerance &&
    Math.abs(a[aOffset + 2] - b[bOffset + 2]) <= tolerance &&
    Math.abs(a[aOffset + 3] - b[bOffset + 3]) <= tolerance
  );
}

// ---------------------------------------------------------------------------------------------
// Identity, paths and hashes
// ---------------------------------------------------------------------------------------------

/** An `id` is a single safe path segment, is neither dot name, and is safe as a map key. */
export function isSafeId(id) {
  if (typeof id !== "string" || !isPortableSegment(id)) return false;
  if (RESERVED_IDS.has(id)) return false;
  // **An integer-like id is a map-key hazard of the same family as `__proto__`.** JavaScript orders
  // canonical array-index keys ahead of every other key and numerically among themselves, so a
  // document listing `"10"` before `"2"` serialises them the other way round while an ordered-map
  // consumer keeps the input order. `statuses` is a *map* and this contract promises it no ordering,
  // so nothing is wrong today — but the `id` is doing double duty as an identifier and a key, and a
  // key whose behaviour depends on the host language's property semantics is not one this schema
  // should mint. Only canonical integers are affected; `2024-fix` is not.
  //
  // Spelled as a pattern rather than as `String(Number(id)) !== id`, which also refused `NaN`,
  // `Infinity` and `-Infinity`: those round-trip through `Number` unchanged and are neither
  // array-index properties nor reserved keys, so refusing them was the check disagreeing with the
  // rule it implements. A leading-zero spelling like `007` is not canonical either, and is fine.
  return !CANONICAL_INTEGER.test(id);
}

/**
 * Artifact paths are contained **and** portable: segments of `[A-Za-z0-9._-]` joined by `/`.
 *
 * Containment alone is not enough. `a\b.png` is checked as two segments by a validator that
 * rewrites `\` to `/` and opened as one filename on POSIX; `#` and `?` become URL syntax the moment
 * the serving host fetches the artifact rather than reading it off disk. A committed artifact path
 * has no need of the rest of Unicode, so the grammar removes the encoding question instead of
 * answering it.
 */
export function isSafeArtifactPath(path) {
  if (typeof path !== "string" || path.length === 0) return false;
  if (path.startsWith("/")) return false;
  return path.split("/").every(isPortableSegment);
}

/**
 * Compare a hash this schema owns against one a catalog served.
 *
 * Only the *served* side may be spelled loosely: `ServeDesignReferenceStore` lowercases a reference
 * hash to validate it and then serves the original spelling, so raw string inequality reports
 * `reference-changed` — "the design moved" — for a reference that never changed. The recorded side
 * is held to 64 lowercase hex characters by {@link recordedHashValid} first and separately, because
 * collapsing the two rules lets one engine lowercase an uppercase *recorded* hash and accept it
 * while another rejects it.
 */
export function hashesMatch(recorded, served) {
  if (typeof recorded !== "string" || typeof served !== "string") return false;
  return recorded.toLowerCase() === served.toLowerCase();
}

/** Every hash this schema owns is exactly 64 lowercase hex characters, or the record is invalid. */
export function recordedHashValid(value) {
  return typeof value === "string" && HEX64.test(value);
}

/**
 * Issue identity is the canonical `owner/repo/number`, never the URL string.
 *
 * Acceptances are hand-authored, so one issue arrives spelled several ways — a trailing slash, an
 * `#issuecomment` fragment, `www.`, a mixed-case owner. Aggregating on the raw string splits those
 * into separate groups, and a group that looks fully resolved then closes an issue a sibling
 * acceptance is still holding open. Returns `null` for a URL that does not parse, which is
 * `schema-invalid` rather than its own group of one.
 */
export function parseIssue(url) {
  if (typeof url !== "string") return null;
  // Not trimmed. `new URL` tolerates surrounding whitespace and the schema's `format: "uri"` does
  // not, so trimming here would accept bytes a schema-first consumer refuses — the divergence this
  // module already closes for unknown properties, reintroduced by a convenience.
  if (url !== url.trim()) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
  // `new URL` lowercases the host for us, which is one of the spellings a regex over the raw string
  // gets wrong. The other is percent-encoding: `%79schimke` and `yschimke` are the same owner and
  // would otherwise key two groups, letting one subset look independently resolved — the precise
  // failure aggregating on canonical identity exists to prevent.
  const host = parsed.hostname.replace(/^www\./, "");
  if (host !== "github.com") return null;
  let segments;
  try {
    segments = parsed.pathname.split("/").filter(Boolean).map(decodeURIComponent);
  } catch {
    return null;
  }
  if (segments.length !== 4 || segments[2] !== "issues") return null;
  const [owner, repo, , rawNumber] = segments;
  if (!/^[A-Za-z0-9._-]+$/.test(owner) || !/^[A-Za-z0-9._-]+$/.test(repo)) return null;
  if (!/^\d+$/.test(rawNumber)) return null;
  const number = Number(rawNumber);
  if (!Number.isSafeInteger(number) || number <= 0) return null;
  return { owner: owner.toLowerCase(), repo: repo.toLowerCase(), number };
}

/** `owner/repo#number`, the key issue-level aggregation groups on. */
export function issueKey(issue) {
  return `${issue.owner}/${issue.repo}#${issue.number}`;
}

/**
 * Join comparison verdicts to the issue index without changing either wire contract.
 *
 * `statuses` is the comparison axis. `issueRows` is the independently-published lifecycle axis,
 * and absence from it is deliberately `unknown`: only a row that positively says `closed` is
 * evidence of closure. Duplicate rows are ordinary (one issue can carry several locator blocks),
 * but contradictory states are not evidence either way and therefore also collapse to `unknown`.
 *
 * Both sides are canonicalised to `owner/repo#number`. Acceptance URLs are hand-authored and may
 * use `www.`, mixed case, a trailing slash or a fragment; joining their raw strings to the index's
 * rebuilt URLs silently loses exactly the closed row stale detection needs.
 */
export function acceptanceLifecycles(documentRecords, statuses, issueRows = []) {
  const indexed = new Map();
  for (const row of issueRows ?? []) {
    const issue = indexedIssue(row);
    const state = row?.state === "open" || row?.state === "closed" ? row.state : null;
    if (!issue || !state) continue;
    const key = issueKey(issue);
    const previous = indexed.get(key);
    if (previous === undefined) indexed.set(key, state);
    else if (previous !== state) indexed.set(key, null);
  }

  const joined = Object.create(null);
  for (const record of documentRecords ?? []) {
    if (typeof record?.id !== "string" || statuses?.[record.id] === undefined) continue;
    const issue = parseIssue(record.issue);
    const key = issue ? issueKey(issue) : null;
    const lifecycle = key === null ? "unknown" : indexed.get(key) ?? "unknown";
    const status = statuses[record.id].status;
    joined[record.id] = {
      issue: key,
      lifecycle,
      stale: lifecycle === "closed" && status !== "resolved",
    };
  }
  return joined;
}

function indexedIssue(row) {
  if (typeof row?.repository === "string" && Number.isSafeInteger(row?.number) && row.number > 0) {
    const parts = row.repository.split("/");
    if (
      parts.length === 2 &&
      parts.every((part) => /^[A-Za-z0-9._-]+$/.test(part))
    ) {
      return { owner: parts[0].toLowerCase(), repo: parts[1].toLowerCase(), number: row.number };
    }
  }
  return parseIssue(row?.url);
}

// ---------------------------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------------------------

/**
 * Evaluate a known-differences document against one comparison.
 *
 * @param documentText raw JSON text — parsed here, so `document-unreadable` is reachable.
 * @param readArtifact `(path, options) => Uint8Array | { bytes, byteLength } | null | { error }`,
 *   where `path` is relative to the fixed `.design-parity/known-differences/` root (`<id>/<mask>`).
 *   `null` means the fetch or open failed, which is `artifact-unreadable`; a file that opens and
 *   holds too few bytes for an `IHDR` is `header-invalid`, because the line is where the failure
 *   occurs rather than how little data there turned out to be.
 *
 *   **`options` is `{ prefix: N }` on the header pass and absent on the decode pass**, and the
 *   difference is the whole reason the preflight can be called bounded. Asked for a prefix, the
 *   reader serves **at most** the first `N` bytes as `{ bytes, byteLength }`, where `byteLength` is
 *   the size of the whole file — which it knows from a `stat` or a `Content-Length` before any bytes
 *   exist. Serving the entire artifact and letting this module read only the front of it satisfies
 *   the letter of "walk chunk headers" while allocating the 8 MiB the budget exists to avoid, twice
 *   per record, for every record that reaches the preflight. A reader may serve fewer than `N` bytes
 *   only because the file is shorter; it may never serve more.
 *
 *   `byteLength` rather than `bytes.length` is what the byte cap and the second-read comparison are
 *   both measured against, so both keep asking a question about the artifact instead of a question
 *   about how much of it was read. A plain `Uint8Array` is still accepted — for the decode pass,
 *   where the whole file is the answer — and its length stands in for both.
 *
 *   **The reader carries three further obligations this module cannot discharge for it**, and
 *   returns `{ error }` to report the first two without materialising the file:
 *
 *   - `{ error: "artifact-too-large" }` — the reader must refuse to allocate more than
 *     {@link BUDGET}`.maxArtifactBytes`. Handing back a whole oversized file so this module can
 *     measure its `.length` exhausts the process through the very guard meant to prevent that, and
 *     only the reader is positioned to know the size before the bytes exist. Every other budget is
 *     enforced from a bounded header read for exactly this reason; this one had been left to a
 *     check that arrives too late.
 *   - `{ error: "path-not-contained" }` — the grammar here is *lexical*, so it cannot see a symlink
 *     inside an acceptance directory. Whether the resolved path stays inside **this acceptance's own
 *     `<id>/` directory** — not merely somewhere under `known-differences/` — is a fact about the
 *     filesystem or the URL space the reader is serving from, and it must establish it before
 *     opening anything. The narrower bound is the load-bearing one: a link from one acceptance's
 *     directory into another's stays under the root while letting a record read bytes it does not
 *     own, and the hash it is then checked against is the *other* record's.
 *   - **Resolution is exact-case, including the `<id>` directory.** A document spelling `MASK.png`
 *     for a committed `mask.png` opens and evaluates on a case-insensitive Windows or macOS
 *     filesystem and is `artifact-unreadable` on a Linux checkout or a case-sensitive URL space — the
 *     same host-versus-checkout divergence the portable grammar and the case-folded id scan close
 *     from the other side, and one no lexical rule can see, because it is a fact about which bytes
 *     the reader actually opened. A reader whose filesystem resolves the request case-insensitively
 *     must compare the resolved name against the requested one and report the mismatch as a failed
 *     open (`null`) rather than serving the file it happened to find.
 * @param comparison the comparison being evaluated, or `null` for a validation-only pass.
 * @param catalog `{ previews: [{ system, id, component, variant, referenceIds }] }` for the
 *   orphaned-target walk, or `null` to skip it.
 * @returns `{ statuses, survivingMasks, validationFailures }`. `statuses` is **absent** for a
 *   document-level rejection — "no acceptance was evaluated" and "every acceptance was valid" must
 *   not serialise the same way — and `survivingMasks` is absent with it. `survivingMasks` carries
 *   the decoded canonical-plane mask of every acceptance whose status is `valid`, in input order:
 *   it is the union
 *   [`known-difference-score.mjs`](./known-difference-score.mjs) suppresses, and forming it is the
 *   one thing that has to happen after the gates and before the score (I5).
 */
/**
 * The document-level failures decided by the records' **identity alone** — no artifact is read to
 * reach any of them.
 *
 * Extracted so {@link readsNoArtifacts} and the evaluation itself cannot drift: a consumer that
 * plans its reads from a second copy of these rules would fetch for a document the engine rejects,
 * or skip for one it accepts, and only the second is a verdict change — which is exactly why the two
 * must be one function rather than two that agree today.
 */
function identityFailures(records) {
  const failures = [];

  // Identity first: a record with no usable key cannot be reported any other way, and a duplicated
  // key cannot be represented in a map at all. Both reject the document.
  const unkeyable = records
    .map((record, index) => ({ record, index }))
    .filter(({ record }) => typeof record?.id !== "string" || record.id.trim() === "");
  for (const { index } of unkeyable) failures.push({ index, reason: "id-missing" });

  // **Collisions are detected case-folded, and reported under the first spelling seen.** `foo` and
  // `FOO` are distinct map keys and the *same directory* on Windows and on a default macOS
  // filesystem — so a document carrying both evaluates cleanly on Linux and, checked out anywhere
  // else, has two records reading one another's artifacts. It cannot even be checked out intact.
  // The `id` is doing double duty as an identifier and a path, and the path half is the one that
  // decides whether two records are really two.
  const firstSeen = new Map();
  const counts = new Map();
  records.forEach((record, index) => {
    if (typeof record?.id !== "string" || record.id.trim() === "") return;
    const key = record.id.toLowerCase();
    if (!firstSeen.has(key)) firstSeen.set(key, { index, id: record.id });
    counts.set(key, (counts.get(key) ?? 0) + 1);
  });
  const duplicates = [...counts.entries()]
    .filter(([, count]) => count > 1)
    .map(([key]) => firstSeen.get(key))
    .sort((a, b) => a.index - b.index);
  for (const { id } of duplicates) failures.push({ id, reason: "duplicate-id" });

  if (records.length > BUDGET.maxAcceptances) failures.push({ reason: "document-too-large" });

  return failures;
}

/**
 * Whether this document is rejected **before a single artifact is read**.
 *
 * For a consumer that must fetch ahead — a browser, where `readArtifact` is synchronous by design
 * and the bytes have to be in hand before the ladder starts — this is the difference between
 * prefetching a document's artifacts and prefetching nothing at all. A rejected document produces no
 * `statuses` and reads nothing, so every byte fetched for one is a byte held for no verdict: up to
 * 256 × 2 × 8 MiB of legal, individually-capped artifacts, which is the exhaustion the caps exist to
 * prevent, reached through the guard itself.
 *
 * It answers only what the *text* decides — the size ceiling, a parse failure, a repeated member,
 * an unkeyable or duplicated id, the record count. Everything past that needs headers, and a
 * consumer planning reads from headers it has not fetched is planning from nothing.
 *
 * Deliberately not "will the engine read *this* artifact": that is a per-record question whose
 * answer is `preflightClean`, and a caller approximating it from headers alone would over-count as
 * often as under-count. This is the half that is exactly decidable, and it is the half that matters
 * for a document nobody should be fetching for.
 */
export function readsNoArtifacts(documentText) {
  const parsed = parseDocument(documentText);
  if (parsed.failure) return true;
  return identityFailures(parsed.document.acceptances).length > 0;
}

export function evaluateKnownDifferences({ documentText, readArtifact, comparison = null, catalog = null }) {
  const parsed = parseDocument(documentText);
  if (parsed.failure) return { validationFailures: [parsed.failure] };
  const records = parsed.document.acceptances;

  const documentFailures = identityFailures(records);

  // **Any document-level failure ends it here, before a single artifact is fetched.** An earlier
  // revision stopped the loop below only for `document-too-large`, so a document rejected for a
  // duplicate or unkeyable id still read all 512 of its artifacts — up to four gigabytes of bounded
  // network or filesystem reads — and then discarded every one of them, because a document-level
  // rejection carries no `statuses`. Nothing below this point can change that verdict.
  if (documentFailures.length > 0) return { validationFailures: sortFailures(documentFailures, records) };

  // **Preflight only, one record at a time, retaining nothing.** The pixel budget is a document
  // verdict reached from per-record header reads, so the two cannot be separated — but nothing below
  // the header is kept. Holding each record's two artifacts until the budget had been checked would
  // put 256 × 2 × 8 MiB — four gigabytes of legal, individually-capped bytes — in memory *before* the
  // aggregate cap could fire, which is the resource exhaustion the caps exist to prevent, reached
  // through the guard itself. The bytes are re-read in {@link decodeRecord}; a preflight is a bounded
  // read of a few dozen bytes per artifact, and reading twice is far cheaper than retaining once.
  //
  // **Compare as you go and stop reading.** Once the document is over budget nothing further about it
  // is knowable — `statuses` is absent for a document-level rejection — so continuing to fetch the
  // remaining artifacts buys nothing and costs everything the cap was defending.
  const evaluations = [];
  let pixels = 0;
  let largestArtifactPixels = 0;
  for (const [index, record] of records.entries()) {
    if (documentFailures.some((failure) => failure.reason === "document-too-large")) break;
    const evaluation = preflightRecord(record, index, readArtifact, catalog);
    evaluations.push(evaluation);
    if (!evaluation.preflightClean) continue;
    for (const header of evaluation.headers) {
      const area = header.width * header.height;
      // **The memory ceiling is checked as you go, like every other aggregate here.** Both terms of
      // {@link peakRasterBytes} only ever grow as headers are added, so a running check refuses at
      // the first header that puts the document over and stops reading — the same short-circuit the
      // pixel cap gets, and for the same reason: past the ceiling nothing further about the document
      // is knowable, and continuing to fetch costs exactly what the cap is defending.
      const nextPixels = pixels + area;
      const nextLargest = largestArtifactPixels > area ? largestArtifactPixels : area;
      if (
        header.width > BUDGET.maxAxis ||
        header.height > BUDGET.maxAxis ||
        area > BUDGET.maxPixels ||
        nextPixels > BUDGET.maxPixels ||
        peakRasterBytes(nextPixels, nextLargest) > BUDGET.maxRasterBytes
      ) {
        pushOnce(documentFailures, { reason: "document-too-large" });
        break;
      }
      pixels = nextPixels;
      largestArtifactPixels = nextLargest;
    }
  }

  if (documentFailures.length > 0) return { validationFailures: sortFailures(documentFailures, records) };

  // Only now: re-read the bytes, hash them, decode, and everything that needs pixels.
  for (const evaluation of evaluations) if (evaluation.preflightClean) decodeRecord(evaluation, readArtifact);

  const statuses = new Map();
  const validationFailures = [];

  evaluations.forEach((evaluation, index) => {
    const record = records[index];
    const reasons = [...evaluation.reasons];

    if (reasons.length === 0 && comparison) {
      reasons.push(...comparisonRefusals(record, evaluation, comparison));
    }

    if (reasons.length > 0) {
      statuses.set(record.id, { status: "refused", reasons: sortReasons(reasons) });
      for (const reason of sortReasons(reasons)) validationFailures.push({ id: record.id, reason, index });
      return;
    }

    if (!comparison || !scopeMatches(record, comparison)) {
      statuses.set(record.id, { status: "out-of-scope" });
      return;
    }

    statuses.set(record.id, runGates(record, evaluation, comparison));
  });

  // **The surviving union, formed only now** (I5). "Survivor" means status `valid`, not "reached the
  // end of the gates": `resolved`, `invalidated` and `refused` all suppress nothing, and a
  // `resolved` mask left in the union would remove its pixels as neighbourhood candidates for the
  // pixels *around* it — hiding a regression sitting next to the thing that was just fixed. This is
  // handed out rather than left inside because the union cannot be built before the gates have run
  // and the scorer cannot run before it exists, so the two batches meet exactly here.
  const survivingMasks = [];
  evaluations.forEach((evaluation, index) => {
    if (statuses.get(records[index].id)?.status !== "valid") return;
    survivingMasks.push({ id: records[index].id, mask: evaluation.mask });
  });

  return {
    statuses: Object.fromEntries(statuses),
    survivingMasks,
    validationFailures: sortFailures(validationFailures, records),
  };
}

/**
 * What one `readArtifact` answer means: bytes, a refusal the reader is better placed to make, or a
 * failure to read at all. Only the two tokens the reader can legitimately establish are honoured —
 * anything else it invents is treated as an unreadable artifact rather than trusted into the result.
 *
 * Returns `{ reason }` or `{ bytes, byteLength }`. A prefix answer must carry a `byteLength` that is
 * a plain integer and **at least** the bytes handed over; a reader claiming a file is smaller than
 * what it just served is not describing anything, and trusting it would let a prefix answer walk
 * past the byte cap by understating the artifact. That is `artifact-unreadable` rather than
 * `artifact-too-large`: nothing about the size has been established, which is the problem.
 */
function readAnswer(result) {
  if (result instanceof Uint8Array) return { bytes: result, byteLength: result.length };
  if (result && typeof result === "object" && typeof result.error === "string") {
    return {
      reason:
        result.error === "artifact-too-large" || result.error === "path-not-contained"
          ? result.error
          : "artifact-unreadable",
    };
  }
  if (result && typeof result === "object" && result.bytes instanceof Uint8Array) {
    const { bytes, byteLength } = result;
    if (!Number.isSafeInteger(byteLength) || byteLength < bytes.length) {
      return { reason: "artifact-unreadable" };
    }
    return { bytes, byteLength };
  }
  return { reason: "artifact-unreadable" };
}

function pushOnce(list, entry) {
  if (!list.some((existing) => existing.reason === entry.reason && existing.id === entry.id)) list.push(entry);
}

/**
 * The refusals only the document *text* can carry, in one left-to-right walk.
 *
 * Run on text `JSON.parse` has already accepted, so bracket balance and string termination are
 * given and this does not need to be a validating parser. It answers two questions the parsed
 * object can no longer be asked:
 *
 * 1. **Does any object repeat a member name?** RFC 8259 leaves that undefined and runtimes genuinely
 *    differ — V8 keeps the last value, several keep the first, strict parsers refuse the input — so
 *    `{"id":"safe","id":".."}` addresses two different artifact directories from one committed file.
 *    Names are unescaped through `JSON.parse` before comparison, because `"id"` and `"\u0069d"` are
 *    the same member and only one of the two spellings looks like a duplicate.
 * 2. **Is an integer-valued field written as a non-integer?** `Number.isSafeInteger` cannot see it:
 *    `9007199254740991.1` has already been rounded to `…991` by the time it reaches a check, and so
 *    has `2.00000000000000000001` to `2` — so this engine accepts what a lossless consumer, or a
 *    Kotlin `Int` decoder, refuses. No bound closes the hole: at any magnitude some fractional
 *    literal is nearer to an integer than the spacing of doubles there. So the token is checked as
 *    written, at the paths where an integer is required — a box's `x`/`y`/`width`/`height` and an
 *    acceptance's `candidateTolerance`. `element.tolerance` is a real number by design.
 */
const GEOMETRY_KEYS = new Set(["x", "y", "width", "height"]);

/**
 * Where an integer-valued number may legally appear, as a path from the document root.
 *
 * **Scoped by containing object, never by member name.** A name-only check misfires on any other
 * object that happens to carry one: an acceptance with the unknown property `"x": 0.5` is
 * `schema-invalid` for that record, and answering `document-unreadable` instead would drop the
 * `statuses` entry of every well-formed sibling — a different result from a schema-first consumer,
 * which is the divergence this walk exists to prevent rather than to cause.
 */
const INTEGER_TOKEN_PATHS = new Map([
  ["/acceptances/[]/plane/box", GEOMETRY_KEYS],
  ["/acceptances/[]/element/bounds", GEOMETRY_KEYS],
  ["/acceptances/[]", new Set(["candidateTolerance"])],
]);

/**
 * The canonical spelling of `element.tolerance` — the one bounded field that is not an integer.
 *
 * **Plain decimal, at most six fraction digits.** The range is `[0, 0.25]`, so the integer part is
 * always `0`, and JSON's own grammar already forbids `.1`, `+0.1` and a leading `+`.
 *
 * This is a *grammar*, deliberately, and not "the shortest decimal that round-trips". That phrasing
 * cannot be implemented identically in two languages — `0.0000001` prints as `1e-7` from JavaScript
 * and `1.0E-7` from Kotlin — so a rule defined by a runtime's formatter would refuse different
 * documents on each side, which is the divergence this contract exists to prevent, reintroduced by
 * the fix for one.
 *
 * The cap is what makes the element gate exact. Every legal tolerance is an exact multiple of
 * `1 / ELEMENT_TOLERANCE_SCALE`, so the comparison against a displacement ratio is integer
 * arithmetic and neither engine ever forms a float — see {@link elementCauses}. At `1e-6` on a
 * 200 px baseline the granularity is 0.0002 px, orders of magnitude below anything a gate can
 * observe, so nothing expressible is lost.
 *
 * Trailing zeros are **legal**: `0.10` and `0.1` scale to the same integer and so cannot produce
 * different verdicts. The cap alone closes the hole, and banning them would cost a legal spelling
 * for no correctness gain.
 */
export const ELEMENT_TOLERANCE_SCALE = 1_000_000;
const CANONICAL_TOLERANCE = /^0(\.[0-9]{1,6})?$/;
const TOLERANCE_TOKEN_PATHS = new Map([["/acceptances/[]/element", new Set(["tolerance"])]]);

function documentTextRefusal(documentText) {
  const scopes = [];
  const path = [];
  let pendingKey = null;
  let index = 0;
  while (index < documentText.length) {
    const character = documentText[index];
    if (character === "{" || character === "[") {
      scopes.push(character === "{" ? new Set() : null);
      // An array element is addressed as `[]`: the *index* never matters here, only which shape of
      // object the member belongs to.
      path.push(pendingKey ?? (scopes.length > 1 && scopes[scopes.length - 2] === null ? "[]" : ""));
      pendingKey = null;
      index += 1;
      continue;
    }
    if (character === "}" || character === "]") {
      scopes.pop();
      path.pop();
      pendingKey = null;
      index += 1;
      continue;
    }
    if (character === '"') {
      let end = index + 1;
      while (end < documentText.length && documentText[end] !== '"') {
        end += documentText[end] === "\\" ? 2 : 1;
      }
      const raw = documentText.slice(index, end + 1);
      index = end + 1;
      let after = index;
      while (after < documentText.length && /\s/.test(documentText[after])) after += 1;
      if (documentText[after] !== ":") {
        pendingKey = null;
        continue;
      }
      index = after + 1;
      const names = scopes[scopes.length - 1];
      if (!names) continue;
      let name;
      try {
        name = JSON.parse(raw);
      } catch {
        return "document-unreadable";
      }
      if (names.has(name)) return "document-unreadable";
      names.add(name);
      pendingKey = name;
      continue;
    }
    if (character === "-" || (character >= "0" && character <= "9")) {
      let end = index;
      while (end < documentText.length && /[-+0-9.eE]/.test(documentText[end])) end += 1;
      const token = documentText.slice(index, end);
      index = end;
      const scope = path.slice(1).map((step) => `/${step}`).join("");
      const integerKeys = INTEGER_TOKEN_PATHS.get(scope);
      const toleranceKeys = TOLERANCE_TOKEN_PATHS.get(scope);
      // Only where the parse *hides* it, exactly as for the integer fields: a token whose parsed
      // value is *already* outside the range keeps its precise, attributed refusal
      // (`tolerance-out-of-range`, naming the record) rather than being traded for a blunt
      // document-level one. `0.3000000` has too many digits and is also out of range, so it stays
      // attributed; `0.144999999999999999999` is in range once parsed and is only visible here.
      if (
        pendingKey !== null &&
        toleranceKeys?.has(pendingKey) &&
        !CANONICAL_TOLERANCE.test(token) &&
        Number(token) >= ELEMENT_TOLERANCE_RANGE[0] &&
        Number(token) <= ELEMENT_TOLERANCE_RANGE[1]
      ) {
        return "document-unreadable";
      }
      // **Only the tokens the parse *hides*.** A value that is still fractional after parsing —
      // `candidateTolerance: 0.5`, a box `x: 0.5` — is caught by the ordinary record-level check,
      // which names the record and picks the better token (`tolerance-out-of-range`,
      // `schema-invalid`). Answering `document-unreadable` for those would trade a precise,
      // attributed refusal for a blunt one and drop every sibling's `statuses` entry. What no
      // value-level check can see is a fractional token that *rounds onto* an integer, so that is
      // exactly what this catches.
      //
      // **Overflow destroys the evidence just as thoroughly as rounding does.** `1e999` parses to
      // `Infinity`, which is not an integer — so a test asking only "did this round onto an integer"
      // lets the largest exponent spellings through while refusing `2e0`, for the same defect. Both
      // are an exponent at a path that requires a canonical integer, and both are unrecoverable
      // after the parse, so both are decided here. A token that survives as an ordinary fractional
      // number still keeps its attributed record-level refusal.
      if (
        pendingKey !== null &&
        integerKeys?.has(pendingKey) &&
        !CANONICAL_INTEGER.test(token) &&
        (Number.isInteger(Number(token)) || !Number.isFinite(Number(token)))
      ) {
        return "document-unreadable";
      }
      pendingKey = null;
      continue;
    }
    index += 1;
  }
  return null;
}

function parseDocument(documentText) {
  // **Bounded before parsing**, for the reason the artifact reader is bounded before opening: every
  // other budget here fires *after* something has already been materialised unless it is checked
  // first, and `JSON.parse` allocates the whole payload before the acceptance and raster caps can
  // see it. A document with an empty `acceptances` array and one enormous string reaches none of
  // them. 1 MiB is generous against real use — 256 records at a kilobyte each is a quarter of it —
  // and this is the defence in depth: the *reader* should refuse to fetch past the ceiling, exactly
  // as `readArtifact` must, since only it knows the size before the bytes exist.
  if (typeof documentText !== "string") return { failure: { reason: "document-unreadable" } };
  // `TextEncoder` rather than `Buffer.byteLength`, because this module is bundled into
  // `format-compare.js` and a browser has no `Buffer` — see `png-lite.mjs`'s header. Both count
  // UTF-8 bytes, which is what the ceiling is in: a document past it in bytes and inside it in
  // *characters* is the case the `document-over-byte-cap-multibyte` fixture exists for.
  if (new TextEncoder().encode(documentText).length > BUDGET.maxDocumentBytes) {
    return { failure: { reason: "document-too-large" } };
  }

  let document;
  try {
    document = JSON.parse(documentText);
  } catch {
    return { failure: { reason: "document-unreadable" } };
  }
  // **The refusals only the text can carry** — a repeated member name, and a geometry coordinate
  // written as a non-integer. Both are invisible once there is an object: the first because the
  // parser has already chosen a winner, the second because it has already rounded. Both take
  // `document-unreadable`, the token the unknown document-level property gets, for the same reason —
  // the evidence is a property of the bytes and there is no record it can honestly be attributed to.
  const textRefusal = documentTextRefusal(documentText);
  if (textRefusal) return { failure: { reason: textRefusal } };
  if (!document || typeof document !== "object" || Array.isArray(document)) {
    return { failure: { reason: "document-unreadable" } };
  }
  if (document.schema !== KNOWN_DIFFERENCES_SCHEMA) return { failure: { reason: "document-unreadable" } };
  if (!Array.isArray(document.acceptances)) return { failure: { reason: "document-unreadable" } };
  // Unknown *document-level* properties, for the same reason unknown record-level ones are refused:
  // the published schema declares `additionalProperties: false` at both levels, so a schema-first
  // consumer rejects bytes a required-fields-only consumer evaluates normally. `document-unreadable`
  // rather than `schema-invalid` because there is no record to attribute it to — this is a property
  // of the file.
  for (const key of Object.keys(document)) {
    if (key !== "schema" && key !== "acceptances") return { failure: { reason: "document-unreadable" } };
  }
  return { document };
}

/**
 * Everything about a record that one comparison cannot change.
 *
 * Kept comparison-independent on purpose: a build gate's `validationFailures` should not depend on
 * which comparison happened to run, and a broken artifact is broken on every page. The two refusals
 * that genuinely need a comparison — `reference-hash-missing` and `acceptance-is-noop` — are added
 * by {@link comparisonRefusals}.
 *
 * The stages are a ladder: reasons accumulate *within* a stage (both hashes can fail at once) and a
 * stage runs only when every earlier one was clean, so a record with an unparseable shape is never
 * also reported for the paths that shape does not contain.
 */
function preflightRecord(record, index, readArtifact, catalog) {
  const evaluation = {
    index,
    record,
    reasons: [],
    headers: [],
    preflightClean: false,
    mask: null,
    accepted: null,
  };
  const fail = (...reasons) => {
    evaluation.reasons.push(...reasons);
    return evaluation;
  };

  // A record need not be an object at all — `acceptances` is third-party data and can hold `null`,
  // a string, an array. Those are already `id-missing` in the identity scan and the document is
  // rejected for them, but this function still runs first (the pixel budget needs the preflight),
  // so it must not dereference what it was handed. Nothing further is knowable about such a record.
  if (!record || typeof record !== "object" || Array.isArray(record)) return evaluation;

  if (!isSafeId(record.id)) return fail("id-not-safe");

  const shapeReasons = schemaReasons(record);
  if (shapeReasons.length > 0) return fail(...shapeReasons);

  if (catalog && !catalogResolves(record, catalog)) return fail("orphaned-target");

  const pathReasons = [];
  if (!isSafeArtifactPath(record.mask)) pathReasons.push("path-not-contained");
  if (!isSafeArtifactPath(record.acceptedCandidate)) pathReasons.push("path-not-contained");
  // The same portable-identity rule the duplicate-id scan applies, applied where it was missing.
  // `mask.png` beside `MASK.PNG` is two committed files on Linux and one file everywhere else, so
  // the record either hashes the wrong bytes or cannot be checked out — the identical failure the
  // case-folded id check exists to prevent, one level down.
  // **Two spellings, not one path.** The rule is about a collision between *distinct* strings; the
  // same path written twice addresses one committed file, which neither collides with anything nor
  // escapes anywhere. Refusing it here would spend `path-not-contained` on a record whose paths are
  // contained, and take the refusal away from whatever is actually wrong with it.
  if (
    pathReasons.length === 0 &&
    record.mask !== record.acceptedCandidate &&
    record.mask.toLowerCase() === record.acceptedCandidate.toLowerCase()
  ) {
    pathReasons.push("path-not-contained");
  }
  if (pathReasons.length > 0) return fail(...pathReasons);

  // **A prefix, not the file.** This pass reads nothing but chunk headers, so asking for the whole
  // artifact would allocate 8 MiB per raster to look at its first kilobyte — the budget defeated by
  // the pass that enforces it. `maxPreflightBytes` is provably enough for every conforming header.
  const prefix = { prefix: BUDGET.maxPreflightBytes };
  const maskRead = readAnswer(readArtifact(`${record.id}/${record.mask}`, prefix));
  const acceptedRead = readAnswer(readArtifact(`${record.id}/${record.acceptedCandidate}`, prefix));
  const readReasons = [maskRead, acceptedRead].map((answer) => answer.reason).filter(Boolean);
  if (readReasons.length > 0) return fail(...readReasons);

  // Still checked here, because a reader that answers at all has already made its claim about the
  // size and this is the cheap confirmation of it. The reader's obligation is not to *allocate* past
  // the cap; this catches a reader that reported past it anyway — and it is measured against the
  // artifact's own length rather than the prefix, which is the only reason a prefix read can enforce
  // this cap at all.
  const oversized = [];
  if (maskRead.byteLength > BUDGET.maxArtifactBytes) oversized.push("artifact-too-large");
  if (acceptedRead.byteLength > BUDGET.maxArtifactBytes) oversized.push("artifact-too-large");
  if (oversized.length > 0) return fail(...oversized);

  // **Truncated here as well, whatever the reader served.** `{ prefix: N }` is a request, and a host
  // may not be in a position to honour it — a `fetch` that cannot range-request, a reader that
  // predates the option and ignores its second argument. Left to the reader alone, that host would
  // walk a chunk this one refuses and reach `decode-failed` where this one reaches `header-invalid`:
  // the prefix would be a *verdict* that varies by host, which is the one thing this contract exists
  // to prevent. Capping the header pass's view here makes the prefix a resource optimisation and
  // nothing more — a host that ignores it agrees on every verdict and merely pays for the bytes.
  // The reader's `byteLength` is untouched by this, so the 8 MiB cap still measures the artifact.
  const headerBytes = (answer) => answer.bytes.subarray(0, BUDGET.maxPreflightBytes);

  // Both headers are read and validated before either raster joins the running total, so the budget
  // is order-independent: an oversized-but-readable header beside an unreadable one must not give a
  // different verdict depending on which was read first.
  const maskHeader = preflightPng(headerBytes(maskRead), { byteLength: maskRead.byteLength });
  const acceptedHeader = preflightPng(headerBytes(acceptedRead), { byteLength: acceptedRead.byteLength });
  const headerReasons = [];
  if (maskHeader.error) headerReasons.push("header-invalid");
  if (acceptedHeader.error) headerReasons.push("header-invalid");
  // **Each header that parsed is inspected, whichever one failed.** Gating these on *both* headers
  // being readable makes the reason set depend on its siblings: an unreadable mask beside a
  // detectable APNG candidate would report `header-invalid` alone and silently drop `animated-png`,
  // even though that header was read and the evidence was in hand. The reason set is exact and
  // deduplicated per record — the second-read stage already accumulates this way, and there is no
  // reason for the first to differ.
  if (!maskHeader.error && maskHeader.animated) headerReasons.push("animated-png");
  if (!acceptedHeader.error && acceptedHeader.animated) headerReasons.push("animated-png");
  // The mask is greyscale with **no alpha**, and `tRNS` is how a greyscale PNG carries alpha
  // anyway. Permitted on the accepted candidate, refused here: the decode would give a matching
  // sample alpha `0` while `maskCoverage` reads only the grey channel, so a transparent white
  // pixel suppresses a comparison on one consumer and refuses the mask on another that enforces
  // the no-alpha rule as written.
  if (
    !maskHeader.error &&
    (maskHeader.bitDepth !== 8 || maskHeader.colourType !== 0 || maskHeader.hasTransparency)
  ) {
    headerReasons.push("mask-encoding-invalid");
  }
  if (headerReasons.length > 0) return fail(...headerReasons);

  evaluation.headers = [maskHeader, acceptedHeader];
  evaluation.preflightClean = true;
  return evaluation;
}

/**
 * The half that touches pixels, run only after the document's budget has passed.
 *
 * Nothing here can change a *document* verdict, which is what makes the split safe: hashes, decodes,
 * mask semantics and dimensions are all per-acceptance, and a record that fails any of them was
 * already inside the budget it was charged against.
 */
function decodeRecord(evaluation, readArtifact) {
  const { record, headers } = evaluation;
  const [maskHeader, acceptedHeader] = headers;
  const maskRead = readArtifact(`${record.id}/${record.mask}`);
  const acceptedRead = readArtifact(`${record.id}/${record.acceptedCandidate}`);
  const fail = (...reasons) => {
    evaluation.reasons.push(...reasons);
    return evaluation;
  };

  // **The second read is validated again, not trusted.** The preflight retained no bytes, so these
  // are fresh reads — and `readArtifact` may be network-backed, or the tree may change underneath a
  // long evaluation. Checking only presence and hashes would let an artifact that grew past the byte
  // cap, or whose header now declares an over-budget raster, walk straight through caps that were
  // applied to bytes nobody is decoding any more. So the per-artifact checks are re-applied, and the
  // headers must still be the ones the budget was computed from; an artifact that changed between
  // the two reads is not stable enough to evaluate, whatever it now contains.
  const rereadAnswers = [readAnswer(maskRead), readAnswer(acceptedRead)];
  const rereadReasons = rereadAnswers.map((answer) => answer.reason).filter(Boolean);
  if (rereadReasons.length > 0) return fail(...rereadReasons);
  const maskBytes = rereadAnswers[0].bytes;
  const acceptedBytes = rereadAnswers[1].bytes;

  const reread = [
    [rereadAnswers[0], maskHeader],
    [rereadAnswers[1], acceptedHeader],
  ];
  const oversized = reread.filter(([answer]) => answer.byteLength > BUDGET.maxArtifactBytes);
  if (oversized.length > 0) return fail("artifact-too-large");
  // **Both artifacts, then report.** Returning on the first one made the *order* of the pair decide
  // which reason a reader saw: a mask that turned animated while the accepted candidate's header
  // turned unreadable produced `animated-png` alone, and swapping the pair produced `header-invalid`
  // alone. §4 says reasons accumulate within a validation stage and the fixtures pin the exact set,
  // so a stage that drops a distinct reason is the contract disagreeing with itself. Duplicates are
  // collapsed by `sortReasons`, so both artifacts failing the same way still reports one token.
  const rereadFailures = [];
  for (const [answer, before] of reread) {
    // **The reader's `byteLength` is carried into this preflight too.** It is one of the fields
    // `samePreflight` compares, so leaving it to default to `bytes.length` here made an honest
    // reader disagree with itself: the header pass reported the artifact's size, the decode pass
    // reported the size of the whole file it happened to hand over, and every prefix read looked
    // like an artifact that had changed underneath the evaluation.
    const now = preflightPng(answer.bytes.subarray(0, BUDGET.maxPreflightBytes), {
      byteLength: answer.byteLength,
    });
    if (now.error) {
      rereadFailures.push("header-invalid");
      continue;
    }
    if (now.animated) rereadFailures.push("animated-png");
    // **Every field, not an enumerated subset.** An earlier revision compared four of them, which
    // left a second read free to add a `tRNS`, or change the compression, filter or interlace method,
    // while the fields being compared stayed put — and the mask-encoding check below reads the
    // *preflight's* header, so a swapped artifact would have been judged on the old one. Comparing
    // the whole object cannot drift out of step with what the preflight learns.
    // **An artifact that changed is unstable, and that is the whole verdict.** The axis cap is a
    // *first-phase* rule, decided from the preflight the budget was computed against; re-applying it
    // to the second read reported `[header-invalid, artifact-unreadable]` for a mask swapped for an
    // 8193-wide one, where the contract says a changed second read is `artifact-unreadable` and
    // nothing else. `samePreflight` compares every field, so a raster that grew past the cap is
    // already caught — the extra check could only ever add a second token to a refusal that was
    // complete.
    if (!samePreflight(now, before)) rereadFailures.push("artifact-unreadable");
  }
  if (rereadFailures.length > 0) return fail(...rereadFailures);
  if (maskHeader.bitDepth !== 8 || maskHeader.colourType !== 0) return fail("mask-encoding-invalid");

  const hashReasons = [];
  if (!hashesMatch(record.maskSha256, sha256Hex(maskBytes))) hashReasons.push("mask-hash-mismatch");
  if (!hashesMatch(record.acceptedCandidateSha256, sha256Hex(acceptedBytes))) {
    hashReasons.push("accepted-candidate-hash-mismatch");
  }
  if (hashReasons.length > 0) return fail(...hashReasons);

  // A correct hash does not make an artifact usable: bytes can be committed with a correctly
  // computed `sha256` and still be corrupt, non-PNG, or decode to nothing. And a header whose
  // declared dimensions disagree with the scanline data behind them is `header-invalid` rather than
  // `decode-failed` — the second half of that rule is what stops a lying header walking past the cap.
  const decoded = [maskBytes, acceptedBytes].map((bytes) => {
    try {
      return { image: decodePng(bytes) };
    } catch (error) {
      return { reason: error.message === "declared-dimensions-mismatch" ? "header-invalid" : "decode-failed" };
    }
  });
  const decodeReasons = decoded.filter((entry) => entry.reason).map((entry) => entry.reason);
  if (decodeReasons.length > 0) return fail(...decodeReasons);
  const mask = decoded[0].image;
  const accepted = decoded[1].image;

  const coverage = maskCoverage(mask);
  if (coverage.nonBinary) return fail("mask-encoding-invalid");
  if (!coverage.box) return fail("mask-empty");

  const dimensionReasons = [];
  const plane = record.plane.box;
  if (mask.width !== plane.width || mask.height !== plane.height) dimensionReasons.push("dimension-mismatch");
  if (accepted.width !== coverage.box.width || accepted.height !== coverage.box.height) {
    dimensionReasons.push("dimension-mismatch");
  }
  if (dimensionReasons.length > 0) return fail(...dimensionReasons);

  evaluation.mask = mask;
  evaluation.accepted = accepted;
  evaluation.coverage = coverage;
  return evaluation;
}

/** Every property `v1` defines, so anything else is a field one engine would read and another drop. */
const ACCEPTANCE_FIELDS = new Set([
  "id",
  "issue",
  "system",
  "component",
  "previewId",
  "referenceId",
  "variant",
  "overrides",
  "mask",
  "acceptedCandidate",
  "referenceSha256",
  "maskSha256",
  "acceptedCandidateSha256",
  "plane",
  "candidateTolerance",
  "element",
  "note",
  "acceptedAt",
]);

/**
 * Required fields, their types, and the two spellings this schema owns.
 *
 * Absence has to be *representable* — a language default that fills a missing field is how this epic
 * lost the same fact twice — so every required field is checked for presence explicitly rather than
 * read through a default.
 *
 * **Unknown properties are refused, and that is not pedantry.** `known-differences.schema.json`
 * declares `additionalProperties: false`, so a consumer that runs the schema first rejects bytes a
 * consumer that runs only this function accepts — the cross-runtime divergence this whole contract
 * exists to prevent, manufactured by the validator itself. It is also what keeps the two fields cut
 * from `v1` cut: a document carrying `finding` or a `producer` selector is refused rather than
 * silently ignored by one engine and acted on by a later one.
 *
 * `variant` is the one string field that **may be empty**, and it is the exception on purpose:
 * `ServeIssueReport.variantFor` returns `""` for a preview id carrying no `__` axes, and "no axes"
 * is a fact about the preview rather than a mangled record. Every other field emptied means the
 * record no longer names one component. The locator contract already settles this
 * ([§2](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#which-fields-may-be-blank-and-which-may-be-absent)),
 * and refusing a blank `variant` here would make every default preview's acceptance inexpressible.
 */
function schemaReasons(record) {
  const invalid = () => ["schema-invalid"];
  for (const key of Object.keys(record)) if (!ACCEPTANCE_FIELDS.has(key)) return invalid();

  for (const field of ["system", "component", "previewId", "referenceId", "mask", "acceptedCandidate"]) {
    if (typeof record[field] !== "string" || record[field] === "") return invalid();
  }
  if (typeof record.variant !== "string") return invalid();
  if (!parseIssue(record.issue)) return invalid();
  for (const field of ["referenceSha256", "maskSha256", "acceptedCandidateSha256"]) {
    if (!recordedHashValid(record[field])) return invalid();
  }
  for (const field of ["note", "acceptedAt"]) {
    if (record[field] !== undefined && typeof record[field] !== "string") return invalid();
  }
  // The schema declares `format: "date-time"`. JSON Schema treats `format` as an annotation by
  // default, so a consumer with assertion enabled rejects what a type-only check here accepts — and
  // `acceptedAt` is a recorded fact, so a string that is not a timestamp is a producer bug either way.
  if (record.acceptedAt !== undefined && !isRfc3339(record.acceptedAt)) return invalid();
  if (record.overrides !== undefined && !isStringMap(record.overrides)) return invalid();
  if (!isPlane(record.plane)) return invalid();
  // Same reasoning as `element.tolerance`: a non-finite number is out of range, and the integer test
  // below already refuses it as such. Structural invalidity is about *shape*, not magnitude.
  if (typeof record.candidateTolerance !== "number" || Number.isNaN(record.candidateTolerance)) {
    return invalid();
  }
  if (record.element !== undefined && !isElement(record.element)) return invalid();

  const ranges = [];
  if (!Number.isInteger(record.candidateTolerance)) ranges.push("tolerance-out-of-range");
  else if (
    record.candidateTolerance < CANDIDATE_TOLERANCE_RANGE[0] ||
    record.candidateTolerance > CANDIDATE_TOLERANCE_RANGE[1]
  ) {
    ranges.push("tolerance-out-of-range");
  }
  if (record.element) {
    const tolerance = record.element.tolerance;
    if (tolerance < ELEMENT_TOLERANCE_RANGE[0] || tolerance > ELEMENT_TOLERANCE_RANGE[1]) {
      ranges.push("tolerance-out-of-range");
    }
  }
  return ranges;
}

function isStringMap(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  return Object.values(value).every((entry) => typeof entry === "string");
}

/** Exactly these keys, and no others — nested objects are held to the same rule as the record. */
function hasExactly(value, keys) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  return Object.keys(value).every((key) => keys.includes(key));
}

function isBox(value) {
  if (!hasExactly(value, ["x", "y", "width", "height"])) return false;
  // `isSafeInteger`, not `isInteger`: JSON's `9007199254740993` has *already* been rounded to
  // …992 by the time it reaches here, so `isInteger` accepts a coordinate a Kotlin `Long` consumer
  // retains exactly. Refusing what cannot round-trip is cheaper than reasoning about where the two
  // readings would first disagree.
  if (!["x", "y", "width", "height"].every((key) => Number.isSafeInteger(value[key]))) return false;
  if (value.width <= 0 || value.height <= 0) return false;
  // **The far edges too, not just the fields.** Every gate that measures a box adds them — element
  // displacement compares `x + width` against a baseline's — and a sum of two safe integers need not
  // be safe. `{x: 9007199254740990, width: 3}` and `{x: 9007199254740990, width: 2}` both round to
  // the same JavaScript edge, so this engine measures no displacement where an exact-integer
  // consumer measures one: `valid` against `element-moved`, from identical bytes.
  return Number.isSafeInteger(value.x + value.width) && Number.isSafeInteger(value.y + value.height);
}

function isPlane(value) {
  if (!hasExactly(value, ["plane", "box"])) return false;
  if (value.plane !== "content-box" && value.plane !== "full-canvas") return false;
  return isBox(value.box);
}

function isElement(value) {
  if (!hasExactly(value, ["kind", "tag", "bounds", "tolerance"])) return false;
  if (value.kind !== "tag") return false;
  if (typeof value.tag !== "string" || value.tag === "") return false;
  if (!isBox(value.bounds)) return false;
  // **A number, finite or not.** `1e999` is a legal JSON number that parses to `Infinity`, and it is
  // unambiguously outside `[0, 0.25]` — which is a *range* failure with its own attributed token, not
  // a structural one. Refusing it here would report `schema-invalid` where a lossless consumer, which
  // never forms the double at all, says `tolerance-out-of-range`. `NaN` has no JSON literal, so it
  // cannot arrive from a parse; it is excluded anyway, because it would pass both range comparisons.
  return typeof value.tolerance === "number" && !Number.isNaN(value.tolerance);
}

/**
 * The orphan walk: every scope field the catalog can resolve, not just the two ids.
 *
 * A component renamed while its preview and reference ids stay put leaves both id lookups
 * succeeding and the acceptance permanently unreachable, which is the exact invisible-forever
 * failure this exists to catch. `overrides` is the one field with no catalog fact to compare
 * against — an acceptance naming a combination nobody has opened is *unused*, not orphaned.
 */
function catalogResolves(record, catalog) {
  const preview = (catalog.previews ?? []).find(
    (entry) => entry.system === record.system && entry.id === record.previewId,
  );
  if (!preview) return false;
  if (preview.component !== record.component) return false;
  if (preview.variant !== record.variant) return false;
  return (preview.referenceIds ?? []).includes(record.referenceId);
}

/** Every recorded field must match — `system` and `component` are the two a page's key drops. */
function scopeMatches(record, comparison) {
  if (record.system !== comparison.system) return false;
  if (record.component !== comparison.component) return false;
  if (record.previewId !== comparison.previewId) return false;
  if (record.referenceId !== comparison.referenceId) return false;
  if (record.variant !== comparison.variant) return false;
  return sameOverrides(record.overrides ?? {}, comparison.overrides ?? {});
}

function sameOverrides(a, b) {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  return keys.every((key) => b[key] === a[key]);
}

/**
 * The two refusals a comparison decides.
 *
 * `acceptance-is-noop` is sequenced **after** the fingerprint gate: the check compares the stored
 * candidate against the *served* reference, and the reference the acceptance was authored against is
 * not kept — so the moment the hash differs the predicate is being evaluated against the wrong
 * image, and a changed reference whose new pixels happen to match the stored candidate would refuse
 * where the contract intends `invalidated: reference-changed`.
 */
function comparisonRefusals(record, evaluation, comparison) {
  if (!scopeMatches(record, comparison)) return [];
  if (typeof comparison.referenceSha256 !== "string" || comparison.referenceSha256 === "") {
    return ["reference-hash-missing"];
  }
  if (!hashesMatch(record.referenceSha256, comparison.referenceSha256)) return [];
  if (!planeMatches(record.plane, comparison.plane)) return [];
  const reference = comparison.canonicalReference;
  if (!reference) return [];
  if (regionAgrees(evaluation, reference, evaluation.accepted, record.candidateTolerance, true)) {
    return ["acceptance-is-noop"];
  }
  return [];
}

/** Two preflights describe the same artifact — every field the preflight learns, not a chosen few. */
function samePreflight(a, b) {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const key of keys) if (a[key] !== b[key]) return false;
  return true;
}

function planeMatches(recorded, current) {
  if (!current) return false;
  if (recorded.plane !== current.plane) return false;
  return ["x", "y", "width", "height"].every((key) => recorded.box[key] === current.box[key]);
}

/** The five gates, then the precedence table. */
function runGates(record, evaluation, comparison) {
  const causes = [];
  const referenceChanged = !hashesMatch(record.referenceSha256, comparison.referenceSha256);
  if (referenceChanged) causes.push("reference-changed");

  const planeChanged = !planeMatches(record.plane, comparison.plane);
  if (planeChanged) causes.push("plane-changed");

  // `plane-changed` short-circuits the element gates: the published index carries bounds in the
  // comparison's plane, and running the gate against bounds transformed through a plane the
  // acceptance was not authored in manufactures a false `element-moved` on top of a correct
  // `plane-changed`.
  if (!planeChanged && record.element) causes.push(...elementCauses(record.element, comparison.tagIndex ?? {}));

  // The precedence table decides this before the candidate gate contributes anything, so the pixel
  // scan is skipped rather than computed and discarded — a legal mask can be tens of millions of
  // pixels, and an acceptance that is already `invalidated` should not pay for them.
  const nonCandidate = causes.filter((cause) => cause !== "candidate-changed");
  if (nonCandidate.length > 0) return { status: "invalidated", causes: sortCauses(nonCandidate) };

  const candidateChanged = !regionAgrees(
    evaluation,
    comparison.canonicalCandidate,
    evaluation.accepted,
    record.candidateTolerance,
    true,
  );

  if (candidateChanged) {
    const converged = regionAgrees(
      evaluation,
      comparison.canonicalCandidate,
      comparison.canonicalReference,
      record.candidateTolerance,
      false,
    );
    if (converged) return { status: "resolved" };
    return { status: "invalidated", causes: ["candidate-changed"] };
  }
  return { status: "valid" };
}

/**
 * Zero matches is always `element-moved` — that is "the glyph vanished", the case the gate exists
 * for. More than one is `element-ambiguous` and stops there, because with several matches there is
 * no single node whose bounds to measure and one engine would otherwise report both causes.
 */
function elementCauses(element, tagIndex) {
  const entry = Object.hasOwn(tagIndex, element.tag) ? tagIndex[element.tag] : null;
  const count = entry?.count ?? 0;
  if (count > 1) return ["element-ambiguous"];
  if (count < 1) return ["element-moved"];
  const bounds = entry.bounds;
  if (!isBox(bounds)) return ["element-moved"];
  const baseline = element.bounds;
  const displacement = Math.max(
    Math.abs(bounds.x - baseline.x),
    Math.abs(bounds.y - baseline.y),
    Math.abs(bounds.x + bounds.width - (baseline.x + baseline.width)),
    Math.abs(bounds.y + bounds.height - (baseline.y + baseline.height)),
  );
  // **Compared by exact cross multiplication, never floating-point ratio or product.** The
  // tolerance grammar makes the decimal an exact multiple of `1 / ELEMENT_TOLERANCE_SCALE`, so the
  // inclusive gate is `displacement × scale <= toleranceMicros × minDimension`. Both sides must be
  // arbitrary-precision integers: a ratio happens to fix the familiar `0.145 × 200` boundary, but
  // diverges again at schema-valid safe-integer bounds (pinned by the large-products fixture).
  const minDimension = Math.min(baseline.width, baseline.height);
  // **Exact integer arithmetic, in `BigInt`.** `element.tolerance` is spelled as a plain decimal
  // with at most six fraction digits, so it is an exact multiple of `1 / SCALE` and scaling recovers
  // that multiple exactly. The comparison is then a product on each side, and those products are
  // *not* safely representable as doubles — which is the whole reason they are `BigInt` here.
  //
  // An earlier revision of this line asserted they were, on the grounds that `minDimension` is
  // bounded by the 8192 axis cap. **That was wrong.** The axis cap constrains raster headers;
  // `$defs.box` permits an element baseline up to `9007199254740991`, and nothing ties the two
  // together. With `tolerance: 0.000011`, `minDimension: 9007199254727272` and a displacement of
  // `99079191802`, the exact left side exceeds the right by 8 — but both products round to the same
  // double, so the comparison answered `valid` for an element that had moved. That is worse than
  // the ratio form it replaced, which answers `element-moved` on the same input.
  //
  // The magnitudes are bounded by the safe-integer range on the way in (`isBox` checks the fields
  // *and* the far edges), so these `BigInt`s are small and fixed-width; nothing here is proportional
  // to a token, and there is no exponent to bomb.
  //
  // The ratio form that preceded both is exact only where the double happens to be: `0.145 × 200`
  // is `28.999999999999996`, so a displacement of exactly 29 — the inclusive boundary — reported
  // `element-moved` under a scaled tolerance and `valid` under a decimal consumer. And it made the
  // verdict depend on a spelling the parse had destroyed: `0.144999999999999999999` is strictly
  // below `0.145` as a decimal and exactly `0.145` as a double. The grammar removes that spelling;
  // this removes the arithmetic that could not represent the comparison.
  const micros = Math.round(element.tolerance * ELEMENT_TOLERANCE_SCALE);
  const left = BigInt(displacement) * BigInt(ELEMENT_TOLERANCE_SCALE);
  const right = BigInt(micros) * BigInt(minDimension);
  return left > right ? ["element-moved"] : [];
}

/**
 * Compare a canonical-plane raster against a second image inside the mask.
 *
 * `secondIsCrop` says whether the second image is the mask's bounding-box crop
 * (`accepted-candidate.png`) or another full canonical-plane raster (the reference) — the two
 * comparisons differ only in that offset, which is precisely why the contract requires them to
 * share one metric.
 */
function regionAgrees(evaluation, canonical, other, tolerance, secondIsCrop) {
  if (!canonical || !other) return false;
  const mask = evaluation.mask;
  const box = evaluation.coverage.box;
  if (canonical.width !== mask.width || canonical.height !== mask.height) return false;
  // **The second raster's dimensions too, and by the shape it is claimed to have.** Only `canonical`
  // was checked, so a full-plane `other` of different dimensions was indexed with its own stride and
  // could agree at every masked coordinate while holding entirely different pixels there — a mask
  // selecting one pixel let a 2-pixel candidate "resolve" against a 3-pixel reference that is not
  // the recorded canonical plane at all. `resolved` is the strongest verdict this contract can
  // reach, since it closes an issue, so it is the last place to infer a plane from a stride.
  const expected = secondIsCrop
    ? { width: box.width, height: box.height }
    : { width: mask.width, height: mask.height };
  if (other.width !== expected.width || other.height !== expected.height) return false;
  for (let y = box.y; y < box.y + box.height; y++) {
    for (let x = box.x; x < box.x + box.width; x++) {
      if (mask.pixels[(y * mask.width + x) * 4] !== 255) continue;
      const left = (y * canonical.width + x) * 4;
      const right = secondIsCrop
        ? ((y - box.y) * other.width + (x - box.x)) * 4
        : (y * other.width + x) * 4;
      if (!pixelsAgree(canonical.pixels, left, other.pixels, right, tolerance)) return false;
    }
  }
  return true;
}

/** The mask's bounding box, and whether any sample sits between the two legal values. */
export function maskCoverage(mask) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -1;
  let maxY = -1;
  let nonBinary = false;
  for (let y = 0; y < mask.height; y++) {
    for (let x = 0; x < mask.width; x++) {
      const value = mask.pixels[(y * mask.width + x) * 4];
      if (value !== 0 && value !== 255) nonBinary = true;
      if (value !== 255) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) return { box: null, nonBinary };
  return { box: { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 }, nonBinary };
}

/**
 * Deduplicated and ordered.
 *
 * A record has two artifacts and several tokens are shared between them — both headers unreadable is
 * one `header-invalid`, not two. `validationFailures` carries one entry per `(record, reason)`
 * **pair**, so the same token twice for one record is one pair; the two hash tokens are distinct
 * precisely so that failure *can* be told apart per artifact.
 */
function sortReasons(reasons) {
  return [...new Set(reasons)].sort((a, b) => reasonRank.get(a) - reasonRank.get(b));
}

function sortCauses(causes) {
  return [...causes].sort((a, b) => causeRank.get(a) - causeRank.get(b));
}

/**
 * Tokens first, then the record's index in `acceptances[]` within one token.
 *
 * The input index is the only ordering both engines can see: two records failing the same way would
 * otherwise come out in map order in one engine and input order in the other.
 */
function sortFailures(failures, records) {
  const indexOf = (entry) => {
    if (typeof entry.index === "number") return entry.index;
    if (entry.id === undefined) return -1;
    const found = records.findIndex((record) => record?.id === entry.id);
    return found < 0 ? Number.MAX_SAFE_INTEGER : found;
  };
  return failures
    .map((entry, order) => ({ entry, order, index: indexOf(entry) }))
    .sort((a, b) => {
      const byReason = reasonRank.get(a.entry.reason) - reasonRank.get(b.entry.reason);
      if (byReason !== 0) return byReason;
      if (a.index !== b.index) return a.index - b.index;
      return a.order - b.order;
    })
    .map(({ entry }) => {
      if (entry.id !== undefined) return { id: entry.id, reason: entry.reason };
      if (typeof entry.index === "number") return { index: entry.index, reason: entry.reason };
      return { reason: entry.reason };
    });
}

/**
 * Issues every acceptance of which has resolved **in this document** — a candidate set, not a
 * closure decision.
 *
 * The tracking issue is mandatory per acceptance but not unique to one — #42 is three acceptances
 * against one issue — so `resolved` on one of them says nothing about the issue, and closing on the
 * first resolution would be self-defeating: the stale detection would immediately flag the siblings
 * the closure just orphaned. Hence the aggregation.
 *
 * **But one run cannot see far enough to close anything, and the name must not pretend otherwise.**
 * An evaluation reads one `known-differences.json` in one source repo, while the workflow supports
 * many of both, and the same upstream component bug reported from two catalogs is the *normal* way
 * one issue ends up referenced twice. Each run would then see its own records all resolved and close
 * the issue out from under a live acceptance in a document it never opened. `v1` constrains the other
 * side — an issue is owned by exactly one document — but nothing offline can *enforce* that, so what
 * this function returns is the local half of the evidence. The closing step must establish ownership
 * separately; where it cannot, it deletes its resolved records and leaves the issue for a human,
 * which is the safe half of the operation and the one that needs no global knowledge.
 */
export function locallyResolvedIssues(documentRecords, statuses) {
  const groups = new Map();
  for (const record of documentRecords) {
    const issue = parseIssue(record.issue);
    if (!issue) continue;
    const key = issueKey(issue);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(statuses?.[record.id]?.status ?? null);
  }
  return [...groups.entries()]
    .filter(([, values]) => values.length > 0 && values.every((status) => status === "resolved"))
    .map(([key]) => key)
    .sort();
}
