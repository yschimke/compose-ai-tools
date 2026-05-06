// Pure label-formatting for the pixel-diff stats line shown beneath the
// Side / Overlay / Onion mode bar in both the live preview panel's
// per-card diff overlay and the History panel's row-level diff
// expansion. Lifted from the duplicated `applyDiffStats` bodies so the
// formatting is unit-testable without a DOM, and so the two consumer
// modules share one source of truth for the wording.
//
// `DiffStats` lives here (rather than in `./pixelDiff.ts`) so this
// module has no DOM type dependencies — the host tsconfig can compile
// it alongside `cardData.ts` / `historyData.ts` and pick it up via the
// `files` list for unit tests. `pixelDiff.ts` re-exports `DiffStats`
// for callers that already imported it from there.

/** Outcome of a pixel diff between two base64 PNGs.
 *  Discriminated by `"error" in s` and `s.sameSize`. */
export type DiffStats =
    | { error: string }
    | {
          sameSize: false;
          leftW: number;
          leftH: number;
          rightW: number;
          rightH: number;
      }
    | {
          sameSize: true;
          w: number;
          h: number;
          diffPx: number;
          total: number;
          percent: number;
      };

/** What the stats line should display: the visible text plus a tag the
 *  CSS uses to colour the line. `state === null` means "no styling
 *  state" (matches `removeAttribute("data-state")`). */
export interface DiffStatsLabel {
    text: string;
    state: "size-mismatch" | "identical" | "changed" | null;
}

/**
 * Render [stats] as a `{ text, state }` pair. Mirrors the previous
 * `applyDiffStats` branches:
 *
 *  - `null` → empty label, no state (used while the diff is still
 *    streaming — the host has set `"computing…"` and just received
 *    the result before the formatter ran).
 *  - `{ error }` → error message, no state.
 *  - size-mismatch → `"sizes differ — WxH vs WxH"`, `state="size-mismatch"`.
 *  - same size, zero diff → `"identical · WxH"`, `state="identical"`.
 *  - same size, non-zero diff → `"<diffPx> px (<percent>%) · WxH"`,
 *    `state="changed"`. Percent uses 3 decimal places below 0.01%
 *    (so a single-pixel diff on a megapixel render reads as
 *    `0.0001%` rather than `0.00%`), 2 decimal places otherwise.
 */
export function formatDiffStatsLabel(stats: DiffStats | null): DiffStatsLabel {
    if (!stats) return { text: "", state: null };
    if ("error" in stats) return { text: stats.error, state: null };
    if (!stats.sameSize) {
        return {
            text:
                "sizes differ — " +
                stats.leftW +
                "×" +
                stats.leftH +
                " vs " +
                stats.rightW +
                "×" +
                stats.rightH,
            state: "size-mismatch",
        };
    }
    if (stats.diffPx === 0) {
        return {
            text: "identical · " + stats.w + "×" + stats.h,
            state: "identical",
        };
    }
    const p = stats.percent * 100;
    const pct = p < 0.01 ? p.toFixed(3) : p.toFixed(2);
    return {
        text:
            stats.diffPx.toLocaleString() +
            " px (" +
            pct +
            "%) · " +
            stats.w +
            "×" +
            stats.h,
        state: "changed",
    };
}
