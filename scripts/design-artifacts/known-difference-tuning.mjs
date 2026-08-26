/**
 * The comparison metric's tuning numbers, as the offline engines read them.
 *
 * **This is a mirror, and the mirror is enforced.** The browser's copy is
 * [`cli/serve-web/src/scorer/tuning.ts`](../../cli/serve-web/src/scorer/tuning.ts), which is where
 * each number's rationale is written down and which the live scorer actually imports. Every one of
 * them is load-bearing to the number that comes out — the bidirectional search at
 * `EDGE_SEARCH_RADIUS`, the `LUMA_TOLERANCE` floor and the `MAX_SIDE` cap decide what a comparison
 * reports — so two copies drifting apart is precisely the silent divergence
 * [§4](../../docs/design/COMPONENT_PARITY_WORKFLOW.md#two-engines-one-semantics) exists to prevent.
 *
 * `known-difference-score.test.mjs` reads the TypeScript file and asserts the two agree, value by
 * value. A constant added there and not here fails that test rather than surfacing months later as
 * an unexplained score difference between the browser and the offline run.
 *
 * A shared JSON file would remove the duplication outright and is the better end state; it is not
 * this batch's change, because `tuning.ts` is imported by the built browser bundle and moving it
 * touches the asset-freshness check that keeps the committed bundle honest.
 */

export const SCORE_TUNING = {
  /**
   * Which pixel path produced a score — the carrier D3 asks for, so a published number can be told
   * from a rebaselined one. `1` was the browser-only `drawImage` era; `2` is the portable area
   * average both engines now run. The rationale lives with the browser's copy in `tuning.ts`.
   */
  SCORE_VERSION: 2,
  /** Longest side of the downscale a score is computed over. */
  MAX_SIDE: 192,
  /** How far an edge pixel may look for its partner, and what it is charged per unit of distance. */
  EDGE_SEARCH_RADIUS: 5,
  EDGE_POSITION_COST: 10,
  /** The 4-neighbour luma gradient at which a pixel counts as an edge. */
  EDGE_GRADIENT_THRESHOLD: 12,
  /** Below this gap a pixel has not moved; at {@link FULL_DIFFERENCE_DELTA} it is charged in full. */
  LUMA_TOLERANCE: 16,
  FULL_DIFFERENCE_DELTA: 128,
  /** How far the content mask reaches beyond the pixels that carry detail. */
  CONTENT_DILATION: 1,

  /**
   * The grounds a comparison is scored on — both of them, every time, worst result kept.
   *
   * As RGB triples rather than the CSS strings `tuning.ts` carries, because nothing offline has a
   * canvas to hand them to; the mirror test compares them by colour, not by spelling.
   */
  COMPARISON_GROUNDS: [
    [255, 255, 255],
    [0, 0, 0],
  ],

  /**
   * How far two luminance planes may differ and still be the same picture.
   *
   * Not a `tuning.ts` export — it is a literal inside `groundsWorthScoring`'s `samePlane` there.
   * Named here because this file is what a second engine reads, and an unnamed constant is one more
   * thing two implementations pick for themselves.
   */
  GROUND_PLANE_TOLERANCE: 1,
};
