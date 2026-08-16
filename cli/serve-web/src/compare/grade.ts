// The colour a comparison score wears on the `/compare` wall.
//
// THIS IS NOT THE ONLY BANDING OF THIS METRIC, and the differences are deliberate rather than drift.
// One number, three readers, three questions:
//
//   grade (here)                90 / 75      "how does this row look on a wall of rows"
//   matchBand (`spec/verdict`)  99.5 / 97    "is this ONE pair a match, close, or off"
//   MATCH_FLOOR (`parity/…`)    90           "is this pair worth opening at all"
//
// The spec lane's bands are tighter because it is looking at a single pair the visitor chose, where
// 97% is visibly off; the wall's are looser because it is triaging dozens and 89% still deserves to
// read as "roughly right, look later". Unifying them would make one of those two surfaces lie.
//
// Two mirrors this cannot reach yet: `viewer.js` restates the same 90/75 inline for its SVG
// fidelity readout, and `scripts/design-artifacts/render-compare-html.mjs` restates it for the
// published wall. Both become imports as their files are ported.

export type Grade = "good" | "warn" | "bad";

/** Which of the three bands a score falls in. `serve.css` names the classes. */
export function grade(percent: number): Grade {
    if (percent >= 90) return "good";
    if (percent >= 75) return "warn";
    return "bad";
}
