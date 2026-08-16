// Which rows the wall shows, and in what order.

/** What the filter needs to know about one row, read off its server-written attributes. */
export interface RowFacts {
    /** Lower-cased haystack of everything searchable about the row. */
    hay: string;
    /** The preview ids behind this row, for the `?preview=` narrow. */
    previewIds: string;
    /** Whether the current format can pair this row at all. */
    hasFormat: boolean;
}

/**
 * Whether a row survives the filter.
 *
 * Three independent narrows, all of which must pass. `?preview=` is the one worth naming: it is how
 * the viewer links INTO this wall for one component, so it has to compose with the search box rather
 * than being overridden by it — someone who arrives from a preview and then types is narrowing
 * within that preview, not starting a new search across the catalog.
 */
export function keepRow(
    row: RowFacts,
    query: string,
    preview: string,
): boolean {
    if (!row.hasFormat) return false;
    const needle = query.trim().toLowerCase();
    if (needle && !row.hay.includes(needle)) return false;
    const wanted = preview.trim().toLowerCase();
    if (wanted && !row.previewIds.toLowerCase().includes(wanted)) return false;
    return true;
}

/** The count under the search box, pluralised. */
export function countLabel(visible: number): string {
    return `${visible} ${visible === 1 ? "comparison" : "comparisons"}`;
}

/**
 * Worst first.
 *
 * The wall exists to find what is wrong, so the rows that are wrong have to be the ones on screen
 * without scrolling. A row that could not be scored sorts to `-1` and leads — "we could not measure
 * this" outranks any measured score, because an unmeasured pair is the one nobody is looking at.
 */
export function byWorstFirst(a: number, b: number): number {
    return a - b;
}

/** The score attribute's value as a number, with the unscored/failed sentinel. */
export function scoreOf(raw: string | null): number {
    const value = parseFloat(raw ?? "");
    return Number.isNaN(value) ? -1 : value;
}
