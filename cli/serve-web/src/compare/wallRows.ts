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
    const previewIds = row.previewIds.toLowerCase().split(/\s+/);
    if (wanted && !previewIds.includes(wanted)) return false;
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

/**
 * A PUBLISHED score off a row's `data-match-<variant>`, or null where the row carries none.
 *
 * Null rather than {@link scoreOf}'s `-1` because the two absences are opposites. `-1` means "this
 * browser tried to measure the pair and could not", which leads the wall. A missing published score
 * means only that the delivery branch had nothing to say — a catalog baked before the producer
 * existed, a run with no browser, a lane that publishes no score at all — and a row nobody has
 * measured yet is not a finding.
 */
export function bakedScoreOf(raw: string | null): number | null {
    const value = parseFloat(raw ?? "");
    return Number.isFinite(value) ? value : null;
}

/**
 * Worst first among the rows that HAVE a published score, with the rest left where they were.
 *
 * The seed order, used before this browser has measured anything: it puts the catalog's own worst
 * pairs on screen at first paint instead of after a raster decode per row. Unscored rows sort last
 * and keep their served order between them — see {@link bakedScoreOf} for why they are not `-1`.
 * Once every visible row has been measured the wall re-sorts with {@link byWorstFirst}, where an
 * unmeasurable row leads.
 */
export function byWorstKnownFirst(a: number | null, b: number | null): number {
    if (a === null) return b === null ? 0 : 1;
    if (b === null) return -1;
    return a - b;
}
