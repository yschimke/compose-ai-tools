// Where a capture lives between the page it was taken on and the report it is pasted into.
//
// The problem this exists for is narrow and unavoidable. GitHub's new-issue form takes a prefilled
// *body* and nothing else — there is no way to prefill an attachment — so the only route from a
// screenshot to an issue is the visitor's own clipboard. And a screenshot of the page a bug is
// about has to be taken ON that page, while the report is written a navigation later on
// `/report-bug`. Something has to carry the pixels across that navigation.
//
// `sessionStorage`, not `localStorage`: this is scratch state belonging to one reporting gesture in
// one tab, and it is a picture of whatever the reporter happened to have on screen — which on a
// preview server can be an unreleased design. It should not outlive the tab, and it must not be
// visible to another one.
//
// Every function here takes the storage as an argument rather than reaching for the global. That is
// what makes the eviction rules testable without a browser, and it is also the honest shape: a
// storage that throws (Safari's private mode, a blocked third-party context) is a case this has to
// survive, not an impossibility.

/** One captured picture, plus whatever else the selection yielded. */
export interface Capture {
    /** Stable within a session; the list is keyed by it for removal. */
    id: string;
    /** What was captured, as the list shows it: `Element · table`, `Region`, `Whole view`. */
    label: string;
    /** `data:image/png;base64,…`. A data URL rather than a blob URL because a blob URL dies with
     *  the document that minted it, which is precisely the navigation this survives. */
    dataUrl: string;
    width: number;
    height: number;
    /** Markdown the selection also produced — a picked table, rendered as one. Absent otherwise. */
    markdown?: string;
    /**
     * `location.pathname` of the page this was a picture of.
     *
     * The pile outlives the report it was taken for — it is `sessionStorage`, so it lasts as long as
     * the tab — and the automatic hand-off has to be able to tell "the screenshot for THIS report"
     * from "a screenshot that happens to still be here". Without that, a second report filed later
     * in the same tab, from somewhere else, would silently put the first report's picture on the
     * clipboard and then instruct the reporter to paste it: a screenshot of an unrelated page,
     * attached with every appearance of being deliberate.
     *
     * The path alone, not the query. Two reports about the same preview at different knob settings
     * are the same subject and a capture of one is honest evidence for the other; two reports about
     * different pages are not. Absent on a capture written by a build older than this field, which
     * is treated as "cannot vouch for it" — the Copy button still sends it, the hand-off does not.
     */
    page?: string;
}

/** The `sessionStorage` key. Namespaced like every other key this server sets. */
export const STORE_KEY = "cp-report-captures";

/**
 * How many captures ride along, and how big the pile may get.
 *
 * `sessionStorage` is a ~5 MB budget per origin that this server shares with nothing else, but a
 * full-viewport PNG of a catalog grid is comfortably over a megabyte, so three is the honest
 * ceiling — and three is more than a bug report needs. Exceeding either limit evicts the OLDEST,
 * because the newest capture is the one the reporter just deliberately took.
 */
export const MAX_CAPTURES = 3;
export const MAX_BYTES = 3_500_000;

/** The storage this runs against, or null where there is none to have. */
export function sessionStore(): Storage | null {
    try {
        return globalThis.sessionStorage ?? null;
    } catch {
        // A blocked storage partition throws on *access*, not on use.
        return null;
    }
}

/**
 * Read the pile back, tolerating every way it can be wrong.
 *
 * The value is JSON this page wrote, but it is JSON in a store another tab, an extension, or an
 * older build of this server could have written — so each entry is checked field by field and a
 * malformed one is dropped rather than reaching the DOM. A capture whose `dataUrl` is not a PNG
 * data URL is the one that matters: that string becomes an `<img src>`, and the whole point of
 * pinning the prefix is that nothing else can.
 */
export function readCaptures(store: Storage | null): Capture[] {
    if (!store) return [];
    let raw: string | null = null;
    try {
        raw = store.getItem(STORE_KEY);
    } catch {
        return [];
    }
    if (!raw) return [];
    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return [];
    }
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isCapture);
}

function isCapture(value: unknown): value is Capture {
    if (!value || typeof value !== "object") return false;
    const c = value as Record<string, unknown>;
    return (
        typeof c.id === "string" &&
        typeof c.label === "string" &&
        typeof c.dataUrl === "string" &&
        c.dataUrl.startsWith("data:image/png;base64,") &&
        typeof c.width === "number" &&
        typeof c.height === "number" &&
        (c.markdown === undefined || typeof c.markdown === "string") &&
        (c.page === undefined || typeof c.page === "string")
    );
}

/**
 * Write the pile, dropping the oldest entries until it fits.
 *
 * Two independent limits, and the quota exception is a third: a browser may refuse a write this
 * function believes is within budget (another tab of the same origin has filled the partition), and
 * the right answer there is the same one — drop the oldest and try again — rather than losing the
 * capture the reporter just took. Returns what actually landed, which can be fewer than it was
 * handed and, in the worst case, none at all.
 */
export function writeCaptures(
    store: Storage | null,
    captures: Capture[],
): Capture[] {
    if (!store) return [];
    let kept = captures.slice(-MAX_CAPTURES);
    while (kept.length && bytes(kept) > MAX_BYTES) kept = kept.slice(1);
    while (kept.length) {
        try {
            store.setItem(STORE_KEY, JSON.stringify(kept));
            return kept;
        } catch {
            kept = kept.slice(1);
        }
    }
    try {
        store.removeItem(STORE_KEY);
    } catch {
        // Nothing to do: there is no pile to leave behind either way.
    }
    return [];
}

function bytes(captures: Capture[]): number {
    return captures.reduce((total, c) => total + c.dataUrl.length, 0);
}

/** Append one capture and persist. Returns the pile as it now stands. */
export function addCapture(store: Storage | null, capture: Capture): Capture[] {
    return writeCaptures(store, [...readCaptures(store), capture]);
}

/** Drop one capture by id and persist. */
export function removeCapture(store: Storage | null, id: string): Capture[] {
    return writeCaptures(
        store,
        readCaptures(store).filter((c) => c.id !== id),
    );
}

/**
 * An id for a capture, unique within this session.
 *
 * A counter over the ids already stored rather than a timestamp or a random string: the pile is at
 * most three long, the id is never shown, and this is the only version of it that cannot collide
 * with itself when two captures are taken inside the same millisecond.
 */
export function nextId(existing: Capture[]): string {
    const used = new Set(existing.map((c) => c.id));
    let n = existing.length + 1;
    while (used.has(`shot-${n}`)) n += 1;
    return `shot-${n}`;
}
