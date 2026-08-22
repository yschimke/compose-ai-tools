// Which of the wall's two picture columns comes first, and what its header calls itself.
//
// One rule decides it, and it is a rule about the whole product rather than about this table: **an
// imported design spec is always drawn to the LEFT of the render it is being compared against.**
// The viewer's spec lane says it three ways already — the triptych (Spec / Diff / Render), the
// wipe's seam (spec left, render right) and the focused `/compare/<id>` page (Reference / Diff /
// Actual) — and this wall's `reference` lane is the same comparison at catalog scale, reached from
// the landing page's own "compare to Figma" action. It used to read the other way round, so a
// reader who stepped from the catalog into the viewer found the two frames had swapped sides
// between one click and the next.
//
// The `svg` and `rc` lanes are NOT that comparison: they pit a render against an export **of that
// same render**, where the render is the source of truth and the export is the thing on trial. So
// they keep the render first, and only the design-spec lane leads with the spec.
//
// DOM-free on purpose: `CompareWall` reads these two answers and moves the cells.

import type { Format } from "./pairing.js";

/**
 * Whether the design spec leads the pair.
 *
 * True only for `reference` — see the module note: the other two lanes compare a render against
 * its own export, which is a different question and keeps the render on the left.
 */
export function specLeadsColumns(format: Format): boolean {
    return format === "reference";
}

/**
 * What the non-render column's header calls itself.
 *
 * The head used to be the constant `SVG`, which was already wrong on two of the three lanes and
 * would be actively misleading now that the columns can swap: a header reading `SVG` over the
 * Figma column, beside one reading `Rendered PNG` over a picture that is not the render, tells the
 * reader the pair is the other way round. [referenceLabel] is the tool the catalog's references
 * actually came from ("Figma"), the same word the format button and the catalog action use.
 */
export function targetHeadLabel(
    format: Format,
    referenceLabel: string,
): string {
    if (format === "reference") return referenceLabel || "Design reference";
    return format === "rc" ? "Remote Compose" : "SVG";
}
