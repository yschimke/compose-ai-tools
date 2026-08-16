// Measured placement: node slots, sheet crops, and the tip that follows the pointer.
//
// The screen backdrop this page replaced was a flat PNG, so its manifest carried a rectangle per
// component and nothing was measured. An inlined SVG is a document: the node is right there and its
// box is whatever the browser says it is. So the manifest carries no geometry and everything
// positional is measured — strictly more accurate, because a Figma export box includes effect bleed
// and a recorded rectangle disagrees with the drawn shape by a few pixels on anything with a shadow.

import { pct } from "./ink.js";

export interface Box {
    left: number;
    top: number;
    width: number;
    height: number;
}

/**
 * A node's slot as percentages of the zoom layer.
 *
 * Against the CANVAS, not the stage. A zoomed canvas is larger than its stage and offset within it,
 * so measuring against the stage would divide a zoomed distance by an unzoomed span and put every
 * overlay somewhere else. Against the canvas the two share one transform and the ratio comes out
 * identical at any zoom — which is why nothing here is recomputed when a zoom is applied.
 *
 * Null for a box with no area: a stage not laid out yet (a background tab, a font still loading)
 * would otherwise put every overlay at 0×0 and cache that.
 */
export function slotIn(layer: Box, node: Box): Record<string, string> | null {
    if (layer.width <= 0 || layer.height <= 0) return null;
    if (node.width <= 0 || node.height <= 0) return null;
    return {
        left: pct(node.left - layer.left, layer.width),
        top: pct(node.top - layer.top, layer.height),
        width: pct(node.width, layer.width),
        height: pct(node.height, layer.height),
    };
}

/**
 * The node's rectangle inside the sheet raster, in raster pixels.
 *
 * MEASURED — the node's client rect mapped through the root's — rather than read from `getBBox()`.
 * `getBBox()` answers in the element's own user space, so a `transform` on the node or any ancestor
 * puts the crop somewhere else entirely. A measured rect has every transform already applied, and it
 * is the same mapping the slots use, so the crop and the slot cannot drift apart.
 */
export function cropFor(
    raster: { width: number; height: number },
    root: Box,
    node: Box,
): Box | null {
    if (!(root.width > 0 && root.height > 0)) return null;
    if (!(node.width > 0 && node.height > 0)) return null;
    const perUnitX = raster.width / root.width;
    const perUnitY = raster.height / root.height;
    return {
        left: (node.left - root.left) * perUnitX,
        top: (node.top - root.top) * perUnitY,
        width: Math.max(1, node.width * perUnitX),
        height: Math.max(1, node.height * perUnitY),
    };
}

/**
 * Capped by TOTAL pixels rather than by side: the scorer downsamples to 192px on the longest side
 * anyway, so resolution beyond "the smallest node still lands around 192px" buys nothing and costs
 * 4 bytes a pixel.
 */
export const MAX_SHEET_PIXELS = 4e6;

/** The raster size for a sheet of these user units, held under {@link MAX_SHEET_PIXELS}. */
export function sheetSize(units: { width: number; height: number }): {
    width: number;
    height: number;
    scale: number;
} | null {
    if (!(units.width > 0 && units.height > 0)) return null;
    const scale = Math.min(
        1,
        Math.sqrt(MAX_SHEET_PIXELS / (units.width * units.height)),
    );
    return {
        width: Math.max(1, Math.round(units.width * scale)),
        height: Math.max(1, Math.round(units.height * scale)),
        scale,
    };
}

/**
 * Where the description tip goes for a pointer at `client`, in stage coordinates.
 *
 * Offset from the cursor so it never sits under the pointer itself, and FLIPPED to the other side
 * rather than allowed to leave the stage — a tip that ran off the right-hand shapes, or off the
 * bottom row, would be describing something the reader cannot read.
 */
export function tipAt(
    stage: Box,
    tip: { width: number; height: number },
    client: { x: number; y: number },
    pad = 14,
): { left: number; top: number } {
    let x = client.x - stage.left + pad;
    let y = client.y - stage.top + pad;
    if (x + tip.width > stage.width)
        x = client.x - stage.left - tip.width - pad;
    if (y + tip.height > stage.height)
        y = client.y - stage.top - tip.height - pad;
    return { left: Math.max(0, x), top: Math.max(0, y) };
}

/**
 * Both spellings of a Figma node id.
 *
 * Figma writes them with a colon (`58548:7249`); the same id appears hyphenated in its own URLs, and
 * a hand-written manifest may use either. Both are tried rather than normalised, because normalising
 * would mean rewriting the export's own attributes — far more invasive than trying a second
 * spelling.
 */
export function idSpellings(id: string): string[] {
    if (!id) return [];
    const alternate = id.includes(":")
        ? id.replace(/:/g, "-")
        : id.replace(/-/g, ":");
    return alternate === id ? [id] : [id, alternate];
}

/** Whether a `data-node-id` value names this node under either spelling. */
export function idMatches(value: string | null, id: string): boolean {
    return value !== null && idSpellings(id).includes(value);
}
