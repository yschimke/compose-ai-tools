// The Remote Compose compare model, as the server inlines it.
//
// Mirrors `ServeRcCompare.ClientModel` — every field here is emitted by
// `encodeClientModel`, so a change on either side has to be a change on both.

/** One player lane's result for one document. */
export interface RcCell {
    rendered?: boolean;
    /** Staged render URL. Empty when the player produced nothing. */
    render?: string;
    /** The BUILD-TIME pixel diff against the AndroidX Java render. Empty for that lane itself. */
    diff?: string;
    /** Build-time mismatch against the AndroidX Java render; null when unrendered or unscorable. */
    mismatchPct?: number | null;
    mismatchPx?: number | null;
    /** The player's own reason for producing nothing. */
    note?: string;
}

export interface RcLane {
    id: string;
    label: string;
    /** Column heading, and what a chip is labelled with. */
    short: string;
}

export interface RcRow {
    label: string;
    /** The AndroidX Java capture is blank, so it is no reference — see `planRow`. */
    referenceBlank?: boolean;
    lanes: Record<string, RcCell>;
}

export interface RcModel {
    /** pixelmatch's `threshold` option, as the offline run was configured. */
    threshold?: number;
    lanes: RcLane[];
    rows: RcRow[];
}

/** The reference nobody picked. Not a lane id, so it can never collide with one. */
export const NO_REFERENCE = "none";

/**
 * The one lane whose diffs were computed offline; every other reference is diffed in-browser.
 *
 * Still `baked` on the wire — the id is what the delivery branch stages its assets under and what
 * `?ref=` carries, so it survives the lane being renamed to **AndroidX Java** for readers.
 */
export const BAKED = "baked";

export function laneIdsOf(model: RcModel): string[] {
    return model.lanes.map((lane) => lane.id);
}

/** A lane's column heading, falling back to its id so an unknown lane still names itself. */
export function shortLabelOf(model: RcModel, id: string): string {
    return model.lanes.find((lane) => lane.id === id)?.short ?? id;
}

/**
 * The reference a URL asks for, or `none`.
 *
 * Validated against the model's own lanes rather than trusted: `?ref=` is visitor-controlled and
 * ends up selecting images and a `[data-lane="…"]` query, so an unrecognised value has to fall back
 * rather than address something.
 */
export function referenceFrom(search: string, laneIds: string[]): string {
    const asked = new URLSearchParams(search).get("ref");
    return asked && laneIds.includes(asked) ? asked : NO_REFERENCE;
}
