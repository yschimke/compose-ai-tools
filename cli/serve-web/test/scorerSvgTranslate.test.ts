// Reading the offset a Figma SVG export puts on its root group.
//
// The whole SVG lane depends on this one regex. Get it wrong and the lane scores a mis-registered
// raster, which reads as the component being drawn wrongly rather than as the two frames being in
// different places — the most misleading failure this surface can produce.

import assert from "node:assert/strict";
import { translateOf } from "../src/scorer/svgTranslate.js";

const svg = (transform: string) =>
    `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="240">` +
    `<g transform="${transform}"><rect width="10" height="10"/></g></svg>`;

describe("translateOf", () => {
    it("reads the root translate", () => {
        assert.deepEqual(translateOf(svg("translate(30, 12)")), {
            x: 30,
            y: 12,
        });
    });

    it("reads a negative offset — a node above or left of the board origin", () => {
        assert.deepEqual(translateOf(svg("translate(-30, -12)")), {
            x: -30,
            y: -12,
        });
    });

    it("tolerates the whitespace an exporter may or may not emit", () => {
        assert.deepEqual(translateOf(svg("translate( 30 , 12 )")), {
            x: 30,
            y: 12,
        });
        assert.deepEqual(translateOf(svg("translate(30,12)")), {
            x: 30,
            y: 12,
        });
    });

    it("answers the origin when there is no translate at all", () => {
        assert.deepEqual(translateOf(svg("scale(2)")), { x: 0, y: 0 });
        assert.deepEqual(translateOf(""), { x: 0, y: 0 });
    });

    it("takes the FIRST translate, which is the placement", () => {
        // Inner transforms are the component's own geometry. Taking the deepest or the last would
        // subtract a child's position from the whole drawing.
        const nested =
            `<svg><g transform="translate(30, 12)">` +
            `<g transform="translate(4, 4)"><rect/></g></g></svg>`;
        assert.deepEqual(translateOf(nested), { x: 30, y: 12 });
    });

    it("KNOWN BUG: silently drops a fractional offset entirely", () => {
        // Not a rounding error — a total miss. The pattern is `(-?\d+)`, so `translate(12.5, 40)`
        // does not match at all and the offset becomes the ORIGIN, including the integer part it
        // could have read. Figma emits fractional positions routinely, so a component sitting at
        // x=340.5 on the board is scored against an SVG drawn 340 pixels away from it.
        //
        // Pinned as it behaves rather than as it should, so the port stays a refactor. Fixing this
        // means flipping this test to `{ x: 12.5, y: 40 }`; see the follow-up.
        assert.deepEqual(translateOf(svg("translate(12.5, 40)")), {
            x: 0,
            y: 0,
        });
        assert.deepEqual(translateOf(svg("translate(340.5, -12.25)")), {
            x: 0,
            y: 0,
        });
    });
});
