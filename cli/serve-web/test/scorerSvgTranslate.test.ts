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

    it("reads a FRACTIONAL offset, which used to be dropped entirely", () => {
        // The bug this replaced was not a rounding error but a total miss: the pattern was
        // `(-?\d+)`, so a fractional translate did not match at all and the offset became the
        // origin, integer part included. A component at x=340.5 on the board was scored against an
        // SVG drawn 340 pixels away — which reads as the component being drawn wrongly rather than
        // as the two frames being in different places.
        assert.deepEqual(translateOf(svg("translate(12.5, 40)")), {
            x: 12.5,
            y: 40,
        });
        assert.deepEqual(translateOf(svg("translate(340.5, -12.25)")), {
            x: 340.5,
            y: -12.25,
        });
    });

    it("reads a leading-dot fraction, which SVG allows", () => {
        assert.deepEqual(translateOf(svg("translate(.5, -.25)")), {
            x: 0.5,
            y: -0.25,
        });
    });

    it("still refuses a value that is not a number", () => {
        // The pattern establishes the shape before anything is parsed, so a malformed transform
        // answers the origin rather than NaN — which would propagate into every drawn coordinate.
        assert.deepEqual(translateOf(svg("translate(12px, 40)")), {
            x: 0,
            y: 0,
        });
        assert.deepEqual(translateOf(svg("translate(, 40)")), { x: 0, y: 0 });
    });
});
