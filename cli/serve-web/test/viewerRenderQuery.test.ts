// What lands in a render URL.
//
// Every case here is a choice between "serve the baked snapshot" and "ask the daemon to render",
// and neither wrong answer announces itself: an over-eager parameter costs a render on every copied
// link, and a missing one serves a picture of something other than what the controls say. The page
// looks fine both ways.

import assert from "node:assert/strict";
import {
    appendQuery,
    explodeParamOn,
    explodeParams,
    knobEmitted,
    rcKnobEmitted,
    rcKnobValue,
    sizeOverrides,
    sizePx,
    withExplode,
    withScroll,
    withSnapshotFormat,
} from "../src/viewer/renderQuery.js";

describe("knobEmitted", () => {
    it("omits a knob still at its declared default", () => {
        // Any `knob.*` at all routes a published catalog to the daemon, so restating the default
        // costs a render to reproduce the picture already baked.
        assert.equal(knobEmitted("Send", "Send", "string"), false);
        assert.equal(knobEmitted("Send now", "Send", "string"), true);
    });

    it("sends an EMPTIED string knob, because empty is a value", () => {
        // A cleared label, or a variant seeded to "". Dropping it would show the default label
        // while the field reads blank.
        assert.equal(knobEmitted("", "Send", "string"), true);
    });

    it("drops an emptied knob of every other kind", () => {
        // The map replaces the daemon's whole override bag, so `knob.count=` is indistinguishable
        // from clearing it.
        for (const kind of ["int", "float", "bool", "color"]) {
            assert.equal(knobEmitted("", "4", kind), false, kind);
        }
    });

    it("treats an undeclared kind as string, matching the server", () => {
        assert.equal(knobEmitted("", "x", "string"), true);
    });

    it("sends an empty knob whose default was ALSO empty only when the kind is string", () => {
        // Both rules fire at once here, and the order matters: the emptiness check comes first, so
        // a number knob seeded blank stays off the URL rather than being sent as unchanged-empty.
        assert.equal(
            knobEmitted("", "", "string"),
            false,
            "unchanged is unchanged",
        );
        assert.equal(knobEmitted("", "", "int"), false);
    });
});

describe("rcKnobEmitted / rcKnobValue", () => {
    it("never sends an empty RC seed, whatever the kind", () => {
        // Stricter than an author knob on purpose: an RC seed is typed by its `<kind>:` prefix and
        // there is no seed that means "empty".
        assert.equal(rcKnobEmitted("", "x"), false);
        assert.equal(rcKnobEmitted("", ""), false);
    });

    it("omits a seed at its declared default", () => {
        assert.equal(rcKnobEmitted("#FF0000", "#FF0000"), false);
        assert.equal(rcKnobEmitted("#00FF00", "#FF0000"), true);
    });

    it("prefixes the kind, which is what types the seed", () => {
        assert.equal(rcKnobValue("color", "#FF0000"), "color:#FF0000");
        assert.equal(rcKnobValue("", "hello"), "string:hello");
    });
});

describe("explodeParamOn", () => {
    it("accepts every boolean form the render endpoint accepts", () => {
        // A stricter reading here showed the flat PNG and then dropped the parameter on the next
        // URL sync — so a bookmarked link worked on the server and not in the page.
        for (const raw of ["1", "true", "on", "yes", "TRUE", "On"]) {
            assert.equal(explodeParamOn(raw), true, raw);
        }
        assert.equal(explodeParamOn(""), true, "a bare ?exploded");
    });

    it("refuses everything else, including absence", () => {
        for (const raw of ["0", "false", "off", "no", "maybe"]) {
            assert.equal(explodeParamOn(raw), false, raw);
        }
        assert.equal(explodeParamOn(null), false);
        assert.equal(explodeParamOn(undefined), false);
    });
});

describe("explodeParams", () => {
    const knob = (param: string, value: string, defaultValue: string) => ({
        param,
        value,
        defaultValue,
    });

    it("stays at the bare parameter while every knob is authored-default", () => {
        assert.deepEqual(
            explodeParams([
                knob("explodeTilt", "24", "24"),
                knob("explodeGap", "", "8"),
            ]),
            ["exploded=1"],
        );
    });

    it("carries a tuned knob, because the angle is part of the link", () => {
        assert.deepEqual(
            explodeParams([
                knob("explodeTilt", "40", "24"),
                knob("explodeSpin", "0", "0"),
            ]),
            ["exploded=1", "explodeTilt=40"],
        );
    });

    it("escapes a value rather than trusting it into the query", () => {
        assert.deepEqual(explodeParams([knob("explodeGap", "1 2&3", "8")]), [
            "exploded=1",
            "explodeGap=1%202%263",
        ]);
    });
});

describe("withScroll / withExplode / withSnapshotFormat", () => {
    const knobs = [{ param: "explodeTilt", value: "40", defaultValue: "24" }];

    it("joins onto an existing query, or starts one", () => {
        assert.equal(withScroll("", true), "scroll=long");
        assert.equal(withScroll("a=1", true), "a=1&scroll=long");
        assert.equal(withScroll("a=1", false), "a=1");
    });

    it("puts the exploded view on the SVG lane ONLY", () => {
        // It is a presentation of the vector export. Appending it to the raster lane would silently
        // do nothing, so the toggle turns SVG on rather than offering the combination.
        assert.equal(
            withExplode(".svg", "a=1", true, knobs),
            "a=1&exploded=1&explodeTilt=40",
        );
        assert.equal(withExplode(".png", "a=1", true, knobs), "a=1");
        assert.equal(withExplode(".svg", "a=1", false, knobs), "a=1");
    });

    it("composes both, scroll first", () => {
        assert.equal(
            withSnapshotFormat(".svg", "a=1", {
                scrollLong: true,
                exploded: true,
                knobs,
            }),
            "a=1&scroll=long&exploded=1&explodeTilt=40",
        );
    });

    it("keeps scroll on the raster lane even when 3D is pressed", () => {
        // The two are independent: a long-scroll PNG is a real request, and dropping it because an
        // unrelated toggle is on would serve the viewport-sized render instead.
        assert.equal(
            withSnapshotFormat(".png", "", {
                scrollLong: true,
                exploded: true,
                knobs,
            }),
            "scroll=long",
        );
    });
});

describe("appendQuery", () => {
    it("leaves the query alone when there is nothing to add", () => {
        assert.equal(appendQuery("a=1", []), "a=1");
        assert.equal(appendQuery("", []), "");
    });
});

describe("sizePx", () => {
    it("scales dp to device pixels", () => {
        assert.equal(sizePx("100", 2), "200");
        assert.equal(sizePx("100", 1), "100");
    });

    it("rounds, and never below one pixel", () => {
        assert.equal(sizePx("10.4", 1), "10");
        assert.equal(sizePx("0.1", 1), "1");
    });

    it("answers null for anything that is not a positive size", () => {
        // `null` rather than a clamp: a zero-width render is not a smaller picture, it is a failed
        // one, and `widthPx=0` would ask the daemon for it.
        assert.equal(sizePx("", 2), null);
        assert.equal(sizePx("0", 2), null);
        assert.equal(sizePx("-5", 2), null);
        assert.equal(sizePx("wide", 2), null);
    });
});

describe("sizeOverrides", () => {
    const read = (field: string) =>
        ({
            fixedW: "300",
            fixedH: "600",
            minW: "320",
            minH: null,
            maxW: "600",
            maxH: "900",
        })[field] ?? null;

    it("reads only its own mode's fields", () => {
        // The form keeps every field's value when the mode changes, deliberately, so switching back
        // restores them — which means a mode that read foreign fields would leave a stale
        // `widthPx` on a URL that no longer asks for a fixed size.
        assert.deepEqual(sizeOverrides("fixed", read), {
            widthPx: "300",
            heightPx: "600",
        });
        assert.deepEqual(sizeOverrides("min", read), { minWidthPx: "320" });
        assert.deepEqual(sizeOverrides("max", read), {
            maxWidthPx: "600",
            maxHeightPx: "900",
        });
    });

    it("gives `within` BOTH bounds, which is what makes it its own mode", () => {
        assert.deepEqual(sizeOverrides("within", read), {
            minWidthPx: "320",
            maxWidthPx: "600",
            maxHeightPx: "900",
        });
    });

    it("contributes nothing when no mode is chosen", () => {
        assert.deepEqual(sizeOverrides("", read), {});
    });

    it("omits a field the form left blank rather than sending a zero", () => {
        assert.ok(!("minHeightPx" in sizeOverrides("min", read)));
    });
});
