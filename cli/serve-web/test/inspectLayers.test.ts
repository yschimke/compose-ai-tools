// How the inspection layers are addressed and deep-linked, as a table.

import assert from "node:assert/strict";
import {
    LAYERS,
    activeLayers,
    baseFrom,
    dataUrlFor,
    fallbackUrl,
    inspectParam,
    kindsFromParam,
    sourcesFor,
} from "../src/inspect/layers.js";

describe("sourcesFor", () => {
    it("fetches one payload for the three layers that share it", () => {
        // Typography, Theme and Layout come from the same endpoint. On an override-bearing frame
        // every extra fetch is another daemon render, which can come back describing different
        // pixels.
        assert.deepEqual(sourcesFor(["typography", "theme", "layout"]), [
            "annotations",
        ]);
    });

    it("fetches both endpoints when both are wanted", () => {
        assert.deepEqual(sourcesFor(["a11y", "theme"]), [
            "a11y",
            "annotations",
        ]);
    });

    it("fetches nothing for nothing", () => {
        assert.deepEqual(sourcesFor([]), []);
        assert.deepEqual(sourcesFor(["nonsense"]), []);
    });
});

describe("activeLayers", () => {
    it("keeps the declared order, not the order asked for", () => {
        // The legend's sections have to read the same way every time; ordering them by which
        // checkbox was ticked first would shuffle the panel under the reader.
        assert.deepEqual(
            activeLayers(["theme", "a11y"]).map((l) => l.kind),
            ["a11y", "theme"],
        );
    });

    it("names each layer for its legend heading", () => {
        assert.deepEqual(
            LAYERS.map((l) => l.label),
            ["Accessibility", "Typography", "Theme", "Layout"],
        );
    });

    it("deep-links the layout layer like any other", () => {
        assert.deepEqual(kindsFromParam("layout,theme"), ["theme", "layout"]);
        assert.equal(inspectParam(["theme", "layout"]), "theme,layout");
    });
});

describe("dataUrlFor", () => {
    it("swaps only the format suffix, keeping every override in the query", () => {
        // Derived from the frame ON SCREEN rather than rebuilt here, so the overlay describes the
        // pixels the visitor is looking at with no second copy of the viewer's query rules.
        assert.equal(
            dataUrlFor(
                "/m3/render/plain.Button.png?at=abc&knob.size=48",
                "a11y",
            ),
            "/m3/render/plain.Button.a11y?at=abc&knob.size=48",
        );
        assert.equal(
            dataUrlFor("/m3/render/plain.Button.svg?x=1", "annotations"),
            "/m3/render/plain.Button.annotations?x=1",
        );
    });

    it("handles a frame with no query at all", () => {
        assert.equal(
            dataUrlFor("/m3/render/x.png", "a11y"),
            "/m3/render/x.a11y",
        );
    });

    it("leaves a path with no known suffix alone but for the addition", () => {
        // A `scroll=long` frame has no inspection product of its own; it falls back to the
        // viewport-sized one rather than 500ing.
        assert.equal(
            dataUrlFor("/m3/render/x?scroll=long", "a11y"),
            "/m3/render/x.a11y?scroll=long",
        );
    });

    it("has no answer before a frame has decoded", () => {
        assert.equal(dataUrlFor("", "a11y"), null);
    });
});

describe("fallbackUrl / baseFrom", () => {
    it("addresses the preview directly, carrying the session keys", () => {
        assert.equal(
            fallbackUrl("/m3", "plain.Button", "a11y", {
                token: "t",
                session: "s",
            }),
            "/m3/render/plain.Button.a11y?token=t&session=s",
        );
    });

    it("encodes an id and keys that need it", () => {
        assert.equal(
            fallbackUrl("/m3", "a/b c", "a11y", { token: "a&b" }),
            "/m3/render/a%2Fb%20c.a11y?token=a%26b",
        );
    });

    it("omits the query when there are no keys", () => {
        assert.equal(fallbackUrl("/m3", "x", "a11y", {}), "/m3/render/x.a11y");
    });

    it("finds the catalog prefix a render URL hangs off", () => {
        assert.equal(baseFrom("/compose-m3/p/plain.Button"), "/compose-m3");
        assert.equal(baseFrom("/compose-m3/p/plain.Button/"), "/compose-m3");
        assert.equal(baseFrom("/p/plain.Button"), "");
        assert.equal(baseFrom("/compose-m3/status"), "/compose-m3/status");
    });
});

describe("kindsFromParam / inspectParam", () => {
    it("round-trips a deep link", () => {
        assert.deepEqual(kindsFromParam("a11y,theme"), ["a11y", "theme"]);
        assert.equal(inspectParam(["a11y", "theme"]), "a11y,theme");
    });

    it("normalises a link to the declared order", () => {
        assert.deepEqual(kindsFromParam("theme,a11y"), ["a11y", "theme"]);
    });

    it("ignores a layer the page does not have", () => {
        // `?inspect=` is visitor-controlled and selects checkboxes by value.
        assert.deepEqual(kindsFromParam("a11y,nonsense"), ["a11y"]);
        assert.deepEqual(kindsFromParam("nonsense"), []);
    });

    it("drops the parameter entirely when nothing is on", () => {
        assert.deepEqual(kindsFromParam(null), []);
        assert.deepEqual(kindsFromParam(""), []);
        assert.equal(inspectParam([]), null);
    });
});
