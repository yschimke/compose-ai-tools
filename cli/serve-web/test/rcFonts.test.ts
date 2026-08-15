// Behavioural contract for `window.cpRcFonts`.
//
// `assets/rc-fonts.js` had no test of any kind. Its two load-bearing promises — that `ready()`
// never rejects, and that it walks the registry exactly once — are precisely the ones a substring
// match on the source could never check, and both are what a Remote Compose lane depends on to
// paint in the right face at all (#3480).

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { ready, resetForTest } from "../src/rcFonts.js";

type FakeFace = {
    status: string;
    load: () => Promise<unknown>;
    loads: number;
};

/** A `FontFace` stand-in; `behaviour` decides how its `load()` settles. */
function face(
    status: string,
    behaviour: "resolve" | "reject" | "throw" = "resolve",
): FakeFace {
    const f: FakeFace = {
        status,
        loads: 0,
        load() {
            f.loads++;
            if (behaviour === "throw") throw new Error("cannot start");
            return behaviour === "reject"
                ? Promise.reject(new Error("404"))
                : Promise.resolve(f);
        },
    };
    return f;
}

/** Install a `document.fonts` registry; `readyValue` stands in for `fonts.ready`. */
function stubFonts(
    faces: FakeFace[],
    options: { forEachThrows?: boolean; readyRejects?: boolean } = {},
): { forEachCalls: number } {
    const counter = { forEachCalls: 0 };
    Object.defineProperty(document, "fonts", {
        configurable: true,
        value: {
            forEach(callback: (f: FakeFace) => void) {
                counter.forEachCalls++;
                if (options.forEachThrows) throw new Error("no registry");
                faces.forEach(callback);
            },
            ready: options.readyRejects
                ? Promise.reject(new Error("fonts.ready failed"))
                : Promise.resolve("fonts-ready"),
        },
    });
    return counter;
}

/** Remove `document.fonts` entirely, as an engine without the Font Loading API would. */
function removeFonts(): void {
    Object.defineProperty(document, "fonts", {
        configurable: true,
        value: undefined,
    });
}

describe("window.cpRcFonts", () => {
    afterEach(() => {
        resetForTest();
        resetDom();
    });

    it("registers itself on the window for the legacy callers to find", () => {
        assert.equal(typeof window.cpRcFonts?.ready, "function");
    });

    it("loads every face the page declared but has not loaded", async () => {
        const unloaded = face("unloaded");
        const another = face("unloaded");
        stubFonts([unloaded, another]);
        await ready();
        assert.equal(unloaded.loads, 1);
        assert.equal(another.loads, 1);
    });

    it("skips a face that is already loaded", async () => {
        const done = face("loaded");
        stubFonts([done]);
        await ready();
        assert.equal(done.loads, 0);
    });

    it("resolves with fonts.ready once the declared faces settle", async () => {
        stubFonts([face("unloaded")]);
        assert.equal(await ready(), "fonts-ready");
    });

    it("walks the registry once however many callers ask", async () => {
        const counter = stubFonts([face("unloaded")]);
        const first = ready();
        const second = ready();
        assert.equal(
            first,
            second,
            "the same promise is handed to both callers",
        );
        await Promise.all([first, second]);
        await ready();
        assert.equal(counter.forEachCalls, 1);
    });

    it("resolves even when a face fails to load", async () => {
        const broken = face("unloaded", "reject");
        const fine = face("unloaded");
        stubFonts([broken, fine]);
        // A missing font paints as its fallback. That is a worse render, not a broken lane, so it
        // must never reach the caller as a rejection.
        await ready();
        assert.equal(fine.loads, 1, "one bad face does not abandon the others");
    });

    it("resolves when a face cannot even start loading", async () => {
        const hostile = face("unloaded", "throw");
        const fine = face("unloaded");
        stubFonts([hostile, fine]);
        await ready();
        assert.equal(fine.loads, 1);
    });

    it("resolves when fonts.ready itself rejects", async () => {
        stubFonts([face("unloaded")], { readyRejects: true });
        await ready();
    });

    it("resolves when the registry cannot be iterated", async () => {
        stubFonts([face("unloaded")], { forEachThrows: true });
        await ready();
    });

    it("resolves on an engine with no Font Loading API", async () => {
        removeFonts();
        await ready();
    });
});
