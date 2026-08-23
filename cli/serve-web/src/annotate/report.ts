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
 * The report body's render and score placeholders, filled.
 *
 * Page-derived values reach the form's hidden INPUT and nothing else — never an `href` or any other
 * navigation sink. The template is server-written; only these placeholders are substituted.
 *
 * A null [scores] **drops the whole row** rather than leaving the placeholder or writing a word
 * where a measurement belongs. That case is reachable now that the body is composed as soon as the
 * page parses rather than only when the scorer finishes: a comparison the browser could not score —
 * a reference the host cannot produce, a frame that never decoded — used to leave the report
 * untouched, so a selection made on such a page would have reached nothing. Dropping the row
 * reproduces exactly what the server writes when it has no measurements of its own.
 */
export function fillReport(
    template: string,
    renderUrl: string,
    scores: string | null,
): string {
    const filled = template.replace("{{render}}", renderUrl);
    if (scores !== null) return filled.replace("{{rawScores}}", scores);
    return filled
        .split("\n")
        .filter((line) => !line.includes("{{rawScores}}"))
        .join("\n");
}
