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
// Fractional offsets are read. They used to be dropped, and not by rounding — the pattern was
// `(-?\d+)`, so `translate(12.5, 40)` did not match AT ALL and the whole offset became the origin,
// integer part included. A component sitting at x=340.5 on the board was therefore scored against
// an SVG drawn 340 pixels away from it, which reads as the component being drawn wrongly rather
// than as the two frames being in different places — the most misleading answer this surface can
// give. Figma emits fractional positions routinely, so this fired in practice.
const TRANSLATE =
    /translate\(\s*(-?(?:\d+(?:\.\d+)?|\.\d+))\s*,\s*(-?(?:\d+(?:\.\d+)?|\.\d+))\s*\)/;

/** The root translate, or the origin when there is none. */
export function translateOf(svgText: string): Translate {
    const match = TRANSLATE.exec(svgText);
    // `Number`, not `parseFloat`: the pattern has already established the shape, and `parseFloat`
    // would quietly accept a trailing tail the pattern refused.
    return match
        ? { x: Number(match[1]), y: Number(match[2]) }
        : { x: 0, y: 0 };
}
