// What a node's diff badge says, and which band it triages into.
//
// Two numbers arrive from the scorer and they answer different questions. Every mistake this module
// exists to prevent is one of them wearing the other's label — and each of those mistakes has
// already been made here once.

import type { Measurement } from "../compare/api.js";

/** Above this proportion drift, the render is a different SHAPE and the badge says so. */
export const STRETCH_THRESHOLD = 2;
/** Below this the geometry is dust and naming it in the tooltip would be noise. */
export const GEOMETRY_MENTION = 0.05;

export type Band = "close" | "drifting" | "far";

export interface Badge {
    /** The headline: drift, and only drift. */
    text: string;
    title: string;
    band: Band;
    /** What the band was decided on — the worse of the two measures. */
    value: number;
}

/**
 * The badge for one scored node.
 *
 * `scoreImages` answers with a MATCH percentage — identical images score 100. This lane reports
 * DRIFT, so it is inverted here. Getting that backwards prints "100.0%" in red for a perfect match
 * and green for a total mismatch: a readout that lies rather than one that is merely wrong.
 *
 * Proportion difference is deliberately held OUT of the match number by the scorer, which normalises
 * both content boxes onto one size first. Ignoring it would report a component rendered at the wrong
 * aspect as a near-perfect match — so it marks the badge, is spelled out in the tooltip, and counts
 * for the BAND.
 *
 * But it is NOT the headline. Taking `max(drift, geometry)` as the number — the first attempt — made
 * every badge on this fixture read 52.4%, which was the aspect difference wearing the label of a
 * pixel difference: two measures conflated into one lie. The number is the drift, so it keeps
 * meaning "how different does this look"; the band is the worse of the two, so a component rendered
 * at the wrong shape still triages red however well its pixels line up once normalised.
 */
export function badgeFor(result: Measurement): Badge {
    const drift = Math.max(0, 100 - result.percent);
    const geometry = Math.max(0, result.geometry ?? 0);
    const stretched = geometry > STRETCH_THRESHOLD;
    const worst = Math.max(drift, geometry);
    return {
        text: `${drift.toFixed(1)}%${stretched ? " ⇲" : ""}`,
        title:
            `${drift.toFixed(1)}% different` +
            (geometry > GEOMETRY_MENTION
                ? ` · ${geometry.toFixed(1)}% proportion difference`
                : ""),
        band: bandFor(worst),
        value: worst,
    };
}

/**
 * Three bands rather than a gradient: the reader is triaging, and "which of these needs looking at"
 * is a decision, not a measurement.
 */
export function bandFor(worst: number): Band {
    return worst < 2 ? "close" : worst < 10 ? "drifting" : "far";
}
