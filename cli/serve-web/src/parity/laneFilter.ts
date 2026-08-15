// The design-parity feed's lane filter, as a pure function over plain rows.
//
// The rows are already in the document — the page is server-rendered and fully readable with
// JavaScript off — so filtering is only ever a question of which ones stay visible, and that
// question is a table rather than a screenshot.

export interface LaneFilterResult {
    /** Per entry, in the order given: whether it stays visible. */
    keep: boolean[];
    /** How many entries are showing. */
    shown: number;
    /** Whether the "no activity in this lane" message should be shown. */
    empty: boolean;
}

/** The lane every entry belongs to, in document order, filtered against the chosen lane. */
export function filterLanes(lanes: string[], lane: string): LaneFilterResult {
    let shown = 0;
    // `all` is the resting state the server marks current, so it has to mean "no filter" rather
    // than "entries whose lane is literally `all`" — no entry ever carries that lane.
    const keep = lanes.map((own) => {
        const kept = lane === "all" || own === lane;
        if (kept) shown++;
        return kept;
    });
    return { keep, shown, empty: shown === 0 };
}
