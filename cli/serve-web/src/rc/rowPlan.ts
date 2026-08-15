// What one row of the Remote Compose compare table does once a reference is picked.
//
// `scoreRow` in `rc-lanes.js` was a promise chain with the decisions woven through it: two row-level
// short circuits, a build-time fast path, three ways for a lane to have no number, and a browser
// diff at the end. Reading it told you the order things happened in, not which case any given cell
// was in — and the cases are the whole behaviour, because most of them exist to say "there is no
// number here, and here is why" rather than to produce one.
//
// Split out, the row is a list of steps: each one either already knows what its chip says, or names
// the two images that have to be measured to find out.

import { BAKED, type RcCell, type RcRow } from "./model.js";

/** How a chip's number is coloured — the same bands the published page uses. */
export type Band = "na" | "good" | "ok" | "bad";

export type RowStep =
    | {
          kind: "chip";
          laneId: string;
          text: string;
          pct: number | null;
          px: number | null;
          /** A build-time diff image to show beside the cell, when there is one. */
          diff?: string;
      }
    | {
          kind: "measure";
          laneId: string;
          referenceSrc: string;
          laneSrc: string;
      };

/** A number's band. Null — "no reference", "no render", a size mismatch — is never coloured. */
export function band(pct: number | null | undefined): Band {
    if (pct === null || pct === undefined) return "na";
    if (pct < 2) return "good";
    if (pct < 10) return "ok";
    return "bad";
}

/** A mismatch percentage as a chip reads it. */
export function percentText(pct: number): string {
    return `${pct.toFixed(2)}%`;
}

/** Two frames that cannot be compared, said as the reason rather than as a failure. */
export function sizeMismatchText(
    lane: { width: number; height: number },
    reference: { width: number; height: number },
): string {
    return `${lane.width}×${lane.height} ≠ ${reference.width}×${reference.height}`;
}

/**
 * The steps for one row against `reference`, in lane order.
 *
 * Three shapes, in the order they are decided:
 *
 * 1. The reference lane itself produced nothing — one chip on the reference, and the row is done.
 *    Nothing can be compared against a missing image.
 * 2. The BAKED capture is blank and the reference IS the baked lane — every other lane gets "no
 *    reference". Scoped to the baked lane rather than to the whole row on purpose: two *player*
 *    renders still compare fine when the baked capture came out empty, and short-circuiting the
 *    row would throw away the only comparison left.
 * 3. Otherwise, per lane: no render → its own note; the baked reference with a precomputed diff →
 *    the offline number, free and exact; anything else → measure it here.
 */
export function planRow(
    row: RcRow,
    laneIds: string[],
    reference: string,
): RowStep[] {
    const referenceLane = row.lanes[reference];
    if (!referenceLane?.rendered) {
        return [
            {
                kind: "chip",
                laneId: reference,
                text: "no reference",
                pct: null,
                px: null,
            },
        ];
    }
    const others = laneIds.filter((id) => id !== reference);
    if (row.referenceBlank && reference === BAKED) {
        return others.map((laneId) => ({
            kind: "chip" as const,
            laneId,
            text: "no reference",
            pct: null,
            px: null,
        }));
    }
    return others.flatMap((laneId): RowStep[] => {
        const lane: RcCell | undefined = row.lanes[laneId];
        // A lane absent from the row was not part of the run at all — no cell, and nothing to say
        // about it. Distinct from a lane that ran and failed, which carries its own note.
        if (!lane) return [];
        if (!lane.rendered || !lane.render) {
            return [
                {
                    kind: "chip" as const,
                    laneId,
                    text: lane.note || "no render",
                    pct: null,
                    px: null,
                },
            ];
        }
        if (
            reference === BAKED &&
            lane.diff &&
            lane.mismatchPct !== null &&
            lane.mismatchPct !== undefined
        ) {
            return [
                {
                    kind: "chip" as const,
                    laneId,
                    text: percentText(lane.mismatchPct),
                    pct: lane.mismatchPct,
                    px: lane.mismatchPx ?? null,
                    diff: lane.diff,
                },
            ];
        }
        return [
            {
                kind: "measure" as const,
                laneId,
                referenceSrc: referenceLane.render ?? "",
                laneSrc: lane.render,
            },
        ];
    });
}
