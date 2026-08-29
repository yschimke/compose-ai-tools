// What the reference page says about the pair, and what it hands the report form.

import type { ComparisonResult } from "../compare/detail.js";
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";

/** Changed pixels as a percentage, guarding the frame that never decoded. */
export function changedPercentOf(result: ComparisonResult): number {
    return result.pixels ? (result.changed * 100) / result.pixels : 0;
}

/**
 * The line under the two panels.
 *
 * Two numbers because they answer different questions — structural match is "how alike are these",
 * changed pixels is "how much of the frame moved". A 99% structural match with 8% of pixels
 * differing is a uniform shift; the reverse is a small element in the wrong place. The geometry
 * figure joins them only once it is more than rasteriser noise.
 */
export function resultLine(result: ComparisonResult): string {
    const geometry =
        result.geometry >= GEOMETRY_REPORT_THRESHOLD
            ? ` · ${result.geometry.toFixed(1)}% proportion difference`
            : "";
    return (
        `${result.score.toFixed(1)}% structural match · ` +
        `${changedPercentOf(result).toFixed(2)}% pixels changed${geometry}`
    );
}

/** The same measurements as one sentence, for the report body. */
export function rawScores(result: ComparisonResult): string {
    let text =
        `${result.score.toFixed(1)}% structural match; ` +
        `${changedPercentOf(result).toFixed(2)}% pixels changed`;
    if (result.geometry >= GEOMETRY_REPORT_THRESHOLD) {
        text += `; ${result.geometry.toFixed(1)}% proportion difference`;
    }
    return text;
}

/**
 * The render URL a report should quote, with the session token removed.
 *
 * A report is written to be pasted somewhere else, and a URL carrying the token grants whoever reads
 * it the access the reporter had. Everything else in the query stays: the overrides are what make
 * the URL reproduce the frame being reported.
 */
export function reportRenderUrl(actualUrl: string, base: string): string {
    const url = new URL(actualUrl, base);
    url.searchParams.delete("token");
    return url.toString();
}

/**
 * The one line the server writes for the browser's measurements.
 *
 * Matched EXACTLY rather than by substring, and that is the whole point of naming it: the row is
 * the only line `ServeIssueReport.body` writes containing this placeholder, so anything else
 * carrying that text came from catalog-authored data (a preview id, a variant derived from one)
 * and must not be touched. Kept in step with `ServeIssueReport.body`'s `| Raw comparison |` row —
 * `reportBodyRows.test.ts` and `ServeWebFixtureTest` both fail if the two drift.
 */
const RAW_SCORES_ROW = "| Raw comparison | `{{rawScores}}` |";

/**
 * The render placeholder as it appears in the body: a markdown link/image **destination**.
 *
 * `ServeIssueReport.body` emits it in exactly two shapes — `![alt]({{render}})` when the render is
 * embeddable and `[PNG at these settings]({{render}})` when it is not — and never as bare text. So
 * the destination form is the anchor, and a bare occurrence in catalog-authored text (a preview id
 * carrying the literal) is left alone instead of being rewritten while the real link keeps the
 * placeholder.
 *
 * That mattered less when the body was only composed after a successful score, because a failing
 * comparison left the server's own body in place. It matters now: the body is composed as soon as
 * the page parses, so a bad substitution would replace a perfectly good server-written report.
 */
const RENDER_DESTINATION = "]({{render}})";

/**
 * Whether [template] has a render link to fill in at all.
 *
 * The body writer holds off composing anything until it has a render URL, because filing
 * `{{render}}` verbatim is worse than filing the server's own body. That rule is right for every
 * template that HAS one — and wrong for the comparison wall's, which names no render because a wall
 * shows every preview at once. Asking the template rather than assuming makes the page-scoped
 * report composable, which is what lets a picked set of rows reach the body at all.
 */
export function needsRender(template: string): boolean {
    return template.includes(RENDER_DESTINATION);
}

/**
 * The report body's render and score placeholders, filled.
 *
 * Page-derived values reach the form's hidden INPUT and nothing else — never an `href` or any other
 * navigation sink. The template is server-written; only these placeholders are substituted.
 *
 * A null [scores] **drops the score row** rather than leaving the placeholder or writing a word
 * where a measurement belongs, reproducing exactly what the server writes when it has no
 * measurements of its own. That case is reachable because the body is composed as soon as the page
 * parses rather than only when the scorer finishes: a comparison the browser could not score — a
 * reference the host cannot produce, a frame that never decoded — used to leave the report
 * untouched, so a selection made on such a page would have reached nothing.
 *
 * Dropping it is done by exact-row identity, not by filtering lines that *contain* the placeholder.
 * The latter was the obvious spelling and deleted every matching line, so a catalog-authored value
 * carrying that text would take the `| Preview |` row and the locator's required `preview:` field
 * with it — a report that is malformed or unindexable, produced by the very path meant to make a
 * failed comparison still reportable.
 */
export function fillReport(
    template: string,
    renderUrl: string,
    scores: string | null,
): string {
    const filled = template.replace(RENDER_DESTINATION, `](${renderUrl})`);
    const lines = filled.split("\n");
    const at = lines.indexOf(RAW_SCORES_ROW);
    // No row means nothing to fill: a body the server wrote without one (it had no measurements
    // either), or one whose row format has drifted — and in both cases editing some other line that
    // happens to carry the text would be worse than leaving the body alone.
    if (at < 0) return filled;
    if (scores === null) lines.splice(at, 1);
    else lines[at] = RAW_SCORES_ROW.replace("{{rawScores}}", scores);
    return lines.join("\n");
}
