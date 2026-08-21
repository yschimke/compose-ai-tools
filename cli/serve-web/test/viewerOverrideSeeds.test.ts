// The client half of "a control opens on what the pixels beside it used".
//
// The server decides which of the URL's override axes a page may seed its controls from, and paints
// that into the markup. That decision is only half of it: `hydrateFromUrl` re-reads
// `location.search` on load and on Back/Forward, so a rule the hydrator does not share is undone a
// frame after the page appears — and a test that reads only the served HTML cannot see it happen.
// These cover the rules the hydrator applies, directly.

import assert from "node:assert/strict";
import {
    isChecked,
    knobHydratedValue,
    effectiveUnseeded,
    rcHydratedValue,
    unseededOverrides,
} from "../src/viewer/overrideSeeds.js";

const none = new Set<string>();

function root(attr: string | null): Element {
    return {
        getAttribute: (name: string) =>
            name === "data-unseeded-overrides" ? attr : null,
    } as unknown as Element;
}

describe("unseededOverrides", () => {
    it("is empty on the ordinary page, which carries no attribute", () => {
        assert.equal(unseededOverrides(root(null)).size, 0);
        assert.equal(unseededOverrides(null).size, 0);
    });

    it("reads the axes the server withheld", () => {
        const set = unseededOverrides(root('["knob.enabled","rc.count"]'));
        assert.ok(set.has("knob.enabled"));
        assert.ok(set.has("rc.count"));
        assert.equal(set.size, 2);
    });

    /**
     * A knob key is an author string and nothing forbids a comma in one. Comma-joining would split
     * `knob.price,discount` into two names that match nothing, so the real axis would quietly stop
     * being withheld and a pinned page would restore the value its render ignored.
     */
    it("keeps a key containing a comma in one piece", () => {
        const set = unseededOverrides(root('["knob.price,discount"]'));
        assert.ok(set.has("knob.price,discount"));
        assert.equal(set.size, 1);
    });

    it("withholds nothing on malformed input rather than guessing", () => {
        assert.equal(unseededOverrides(root("knob.enabled")).size, 0);
        assert.equal(unseededOverrides(root("{")).size, 0);
        assert.equal(unseededOverrides(root('{"knob.a":1}')).size, 0);
    });

    it("ignores non-string entries", () => {
        assert.equal(unseededOverrides(root('["knob.a",3,null]')).size, 1);
    });
});

describe("knobHydratedValue", () => {
    const base = {
        wireKey: "enabled",
        initial: "true",
        declaredKind: "bool",
        unseeded: none,
    };

    it("takes the URL's value when the page seeded it", () => {
        assert.equal(
            knobHydratedValue({ ...base, urlValue: "false" }),
            "false",
        );
    });

    it("defers to the declaration when the URL says nothing", () => {
        assert.equal(knobHydratedValue({ ...base, urlValue: null }), "true");
    });

    /**
     * The case that makes the server-side guard real. A pinned page, or an accepted baked fallback,
     * answers with pixels that ignored this axis — so the hydrator has to leave the control on the
     * declaration rather than restoring what the address bar still says.
     */
    it("defers to the declaration for an axis the page withheld", () => {
        assert.equal(
            knobHydratedValue({
                ...base,
                urlValue: "false",
                unseeded: new Set(["knob.enabled"]),
            }),
            "true",
        );
    });

    it("withholds by exact key, so a namesake axis is unaffected", () => {
        assert.equal(
            knobHydratedValue({
                ...base,
                urlValue: "false",
                unseeded: new Set(["knob.enabledish", "rc.enabled"]),
            }),
            "false",
        );
    });

    it("strips a legacy kind tag that matches the declared kind", () => {
        assert.equal(
            knobHydratedValue({ ...base, urlValue: "bool:false" }),
            "false",
        );
    });

    it("leaves a tag the declared kind does not claim, which a string knob may hold", () => {
        assert.equal(
            knobHydratedValue({
                wireKey: "label",
                urlValue: "int:3",
                initial: "Tap me",
                declaredKind: "string",
                unseeded: none,
            }),
            "int:3",
        );
    });

    it("keeps the declaration for an empty value on a typed knob, which the parser skips", () => {
        const typed = {
            wireKey: "count",
            initial: "5",
            declaredKind: "int",
            unseeded: none,
        };
        assert.equal(knobHydratedValue({ ...typed, urlValue: "" }), "5");
        assert.equal(knobHydratedValue({ ...typed, urlValue: "int:" }), "5");
        assert.equal(knobHydratedValue({ ...typed, urlValue: "3" }), "3");
    });

    it("keeps an empty STRING, which is a real value — a cleared label", () => {
        assert.equal(
            knobHydratedValue({
                wireKey: "label",
                urlValue: "",
                initial: "Tap me",
                declaredKind: "string",
                unseeded: none,
            }),
            "",
        );
    });
});

describe("rcHydratedValue", () => {
    const int = {
        name: "count",
        initial: "5",
        declaredKind: "int",
        unseeded: none,
    };

    it("defers to the declaration for an axis the page withheld", () => {
        assert.equal(
            rcHydratedValue({
                ...int,
                urlValue: "int:3",
                unseeded: new Set(["rc.count"]),
            }),
            "5",
        );
    });

    /**
     * Stricter than a plain knob, and deliberately: the server types an `rc.` from its own wire tag
     * rather than from the declaration, so a bare `3` parses as a string and never reaches a
     * declared int. Showing it would contradict the pixels.
     */
    it("ignores a seed that would not parse as the declared kind", () => {
        assert.equal(rcHydratedValue({ ...int, urlValue: "3" }), "5");
        assert.equal(rcHydratedValue({ ...int, urlValue: "float:2" }), "5");
    });

    it("takes a seed whose tag agrees, bare", () => {
        assert.equal(rcHydratedValue({ ...int, urlValue: "int:3" }), "3");
    });

    it("takes an untagged seed on a string knob", () => {
        assert.equal(
            rcHydratedValue({
                name: "label",
                urlValue: "Tap",
                initial: "Go",
                declaredKind: "string",
                unseeded: none,
            }),
            "Tap",
        );
    });

    it("ignores a blank seed, which the parser skips wholesale", () => {
        assert.equal(rcHydratedValue({ ...int, urlValue: "" }), "5");
    });
});

describe("effectiveUnseeded", () => {
    const both = new Set(["knob.enabled", "rc.count"]);

    /**
     * Withholding describes the image the SERVER sent, so it binds the lanes the server draws.
     *
     * The lanes the browser draws mount the component and honour the control directly, so the URL's
     * value is the truthful one there. Applying the page-level set to them discards what a history
     * entry recorded: enter Wasm, edit a knob, leave for the snapshot, press Back, and the restore
     * would reset the knob to its declaration before reopening Wasm.
     */
    it("binds everything on a server-rendered lane, including the default", () => {
        for (const mode of [
            "png",
            "snapshot",
            "live",
            "svg",
            "motion",
            "spec",
            "",
            null,
        ])
            assert.deepEqual(
                effectiveUnseeded(both, mode, true),
                both,
                String(mode),
            );
    });

    /**
     * …and on an in-browser lane it still binds the half of the controls that lane cannot reach.
     *
     * `wasmOverridePatch()` forwards `.cp-knob` only; the RC canvas and the CMP-Wasm player forward
     * `.cp-rc-knob` only, and `syncServerControls()` disables the other family. Exempting the whole
     * set would hydrate a DISABLED control to a value the lane on screen never applies.
     */
    it("exempts only the family the browser lane forwards", () => {
        assert.deepEqual(
            effectiveUnseeded(both, "wasm", true),
            new Set(["rc.count"]),
        );
        for (const mode of ["rc", "rc-wasm"])
            assert.deepEqual(
                effectiveUnseeded(both, mode, true),
                new Set(["knob.enabled"]),
                mode,
            );
    });

    /**
     * `?mode=` is a request, not a fact. A stale or hand-shared mode naming a lane this session does
     * not offer leaves the snapshot displayed — the bookmarked-mode guard returns early on a missing
     * or disabled radio — so the withholding has to stand.
     */
    it("keeps withholding when the requested lane cannot be entered", () => {
        assert.deepEqual(effectiveUnseeded(both, "wasm", false), both);
        assert.deepEqual(effectiveUnseeded(both, "rc", false), both);
    });

    it("leaves an empty set alone", () => {
        assert.equal(effectiveUnseeded(new Set(), "wasm", true).size, 0);
    });
});

describe("isChecked", () => {
    it("reads a bool the way the server's parser does", () => {
        for (const yes of ["true", "TRUE", "True", "1"])
            assert.equal(isChecked(yes), true, yes);
        for (const no of ["false", "0", "", "yes"])
            assert.equal(isChecked(no), false, no);
    });
});
