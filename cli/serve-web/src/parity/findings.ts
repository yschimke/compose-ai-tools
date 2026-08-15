// What the parity page's visual-difference scan decides, before any DOM exists.
//
// The scan itself is four overlapping queues around `window.ComposePreviewCompare.scoreImageUrls`;
// everything interesting about it is the judgements it makes on the way out — which measurements
// count as a finding, how a score cell reads, what order the issues table is in, and the sentence
// that summarises the run. Held in the element, each of those could only be checked by scoring a
// real catalog and reading the result. Here they are a table.

/** A measurement from `ComposePreviewCompare.scoreImageUrls`. */
export interface Measurement {
    /** Structural match, 0–100. */
    percent: number;
    /** Proportion drift between the two content boxes, in percent. Absent on some lanes. */
    geometry?: number;
}

export interface Finding {
    name: string;
    /** Href of the focused comparison for this component. */
    review: string;
    /** null when the pair could not be scored at all. */
    score: number | null;
    geometry: number;
    unavailable: boolean;
}

/** How a `.cp-parity-score` cell reads once its pair has been measured. */
export interface ScoreCell {
    text: string;
    /** Whether this is a clean result — drives `cp-ok` versus `cp-parity-missing`. */
    ok: boolean;
    /** Tooltip, or null when there is nothing worth adding. */
    title: string | null;
}

/**
 * Below this, the two renders are different enough to be worth a human's time. The same floor the
 * summary sentence quotes, so the two can never drift apart.
 */
export const MATCH_FLOOR = 90;

/**
 * Proportion drift is only worth the visitor's attention once the two content boxes are genuinely
 * different shapes — under this, it is measurement noise on artwork that matches. Mirrors
 * `format-compare.js`'s own threshold, because the focused comparison page has to agree with the
 * parity page about whether a pair is worth opening.
 */
export const GEOMETRY_REPORT_THRESHOLD = 2;

/**
 * A pair is a finding when it is structurally off OR out of proportion.
 *
 * The second half is the one that is easy to lose: a component can be a 96% structural match and
 * still be visibly the wrong shape, because the score is measured on normalised content boxes.
 */
export function isFinding(score: number, geometry: number): boolean {
    return score < MATCH_FLOOR || geometry >= GEOMETRY_REPORT_THRESHOLD;
}

/** The geometry figure a measurement carries, defaulted for the lanes that do not report one. */
export function geometryOf(measured: Measurement): number {
    return typeof measured.geometry === "number" ? measured.geometry : 0;
}

export function scoreCell(measured: Measurement): ScoreCell {
    const geometry = geometryOf(measured);
    return {
        text: `${measured.percent.toFixed(1)}%`,
        ok: !isFinding(measured.percent, geometry),
        title:
            geometry >= GEOMETRY_REPORT_THRESHOLD
                ? `${geometry.toFixed(1)}% proportion difference`
                : null,
    };
}

/** The cell for a pair that could not be scored — a missing render, or an image that would not decode. */
export function unavailableCell(): ScoreCell {
    return { text: "Unavailable", ok: false, title: null };
}

/** The issues table's "Structural match" column for one finding. */
export function findingResult(finding: Finding): string {
    if (finding.unavailable || finding.score === null) return "Unavailable";
    const drift =
        finding.geometry >= GEOMETRY_REPORT_THRESHOLD
            ? ` · ${finding.geometry.toFixed(1)}% proportion drift`
            : "";
    return `${finding.score.toFixed(1)}%${drift}`;
}

/**
 * Worst first, so the table opens on the pair most worth looking at.
 *
 * Unscorable pairs sort above every score: a component whose render is missing is a bigger problem
 * than one that merely disagrees with its reference, and burying it under the low scores is how it
 * goes unnoticed.
 */
export function sortFindings(findings: Finding[]): Finding[] {
    return [...findings].sort((a, b) => (a.score ?? -1) - (b.score ?? -1));
}

/** The opening line, before the first pair has been measured. */
export function checkingOf(total: number): string {
    return `Checking ${total} mapped comparison(s)…`;
}

/** Progress while the queues are still draining. */
export function progressOf(completed: number, total: number): string {
    return `Checked ${completed} of ${total} comparisons…`;
}

/**
 * The sentence under the "Visual differences" heading once the scan has finished.
 *
 * The unscorable pairs are counted OUT of the differences rather than reported alongside them.
 * `parity.js` pushed them onto the same list and then quoted its whole length as "require review",
 * so a run where every failure was an unavailable render read as "3 of 40 are unavailable; 3
 * require review" — the same three components, said twice, as though six things were wrong.
 */
export function summaryOf(total: number, findings: Finding[]): string {
    const unavailable = findings.filter((f) => f.unavailable).length;
    const differing = findings.length - unavailable;
    const plural = (n: number, one: string, many: string) =>
        n === 1 ? one : many;
    if (unavailable > 0) {
        const rest =
            differing > 0
                ? ` ${differing} of the rest ${plural(differing, "has", "have")} a structural or proportion difference.`
                : " The rest are a structural match.";
        return (
            `${unavailable} of ${total} mapped ${plural(total, "comparison", "comparisons")} ` +
            `could not be scored.${rest}`
        );
    }
    if (differing > 0) {
        return (
            `${differing} mapped ${plural(differing, "component", "components")} ` +
            `${plural(differing, "has", "have")} a structural or proportion difference.`
        );
    }
    return `All ${total} mapped ${plural(total, "component is", "components are")} at least ${MATCH_FLOOR}% structural match.`;
}
