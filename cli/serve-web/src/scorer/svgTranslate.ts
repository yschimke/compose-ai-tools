// The offset a Figma SVG export puts on its root group.
//
// The exporter writes the node's position on the board into a `translate(...)`, so drawing the SVG
// at the origin puts the component wherever it happened to sit in the file — hundreds of pixels off
// the PNG it is being scored against. Reading the translate back out and subtracting it is what
// registers the two lanes on each other. Get it wrong and the SVG lane scores a mis-registered
// raster, which reads as the component being wrong rather than the alignment.

export interface Translate {
    x: number;
    y: number;
}

// Deliberately the FIRST match, not the deepest: the root group's transform is what the exporter
// writes first, and inner transforms are the component's own geometry, not its placement.
//
// NOTE: integers only. A `translate(12.5, 40)` does not match at all, so the offset — including its
// integer part — silently becomes the origin and the SVG lane scores two frames that are hundreds
// of pixels apart. Figma emits fractional positions routinely, so this fires in practice. Ported
// as-is deliberately, so this change stays a refactor; see the follow-up.
const TRANSLATE = /translate\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)/;

/** The root translate, or the origin when there is none. */
export function translateOf(svgText: string): Translate {
    const match = TRANSLATE.exec(svgText);
    return match
        ? { x: parseInt(match[1], 10), y: parseInt(match[2], 10) }
        : { x: 0, y: 0 };
}
