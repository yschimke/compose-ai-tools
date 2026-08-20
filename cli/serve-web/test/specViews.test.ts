// Who gets to decide which spec view is showing, as a table.

import assert from "node:assert/strict";
import {
    DEFAULT_VIEW,
    INITIAL,
    choose,
    hydrate,
    isView,
    normaliseView,
    onOpen,
    PLAIN_VIEW,
    prefer,
    viewParam,
    type ViewChoice,
} from "../src/spec/views.js";

describe("normaliseView", () => {
    it("takes the four views the lane has", () => {
        for (const view of ["spec", "diff", "triptych", "slider"]) {
            assert.equal(normaliseView(view), view);
            assert.equal(isView(view), true);
        }
    });

    it("falls back rather than addressing a view that does not exist", () => {
        for (const bad of ["", null, undefined, "SPEC", "wipe", "../spec"]) {
            assert.equal(normaliseView(bad), DEFAULT_VIEW, String(bad));
            assert.equal(isView(bad), false);
        }
    });
});

describe("choose", () => {
    it("takes the view and latches the choice", () => {
        const state = choose(INITIAL, "diff");
        assert.equal(state.view, "diff");
        assert.equal(state.chosen, true);
    });

    it("latches even on the default, which is still a choice", () => {
        // Pressing the view already showing is someone saying they want it, not saying nothing —
        // it is what stops a later entry-view request from moving them off it.
        assert.equal(choose(INITIAL, DEFAULT_VIEW).chosen, true);
        assert.equal(choose(INITIAL, "spec").chosen, true);
    });
});

describe("hydrate", () => {
    it("treats a named view in the URL as an explicit choice", () => {
        // It is either what the visitor picked before sharing or reloading, or where Back is
        // returning them to — both are someone having said what they want.
        const state = hydrate(INITIAL, "slider");
        assert.equal(state.view, "slider");
        assert.equal(state.chosen, true);
    });

    it("does not latch on a URL that says nothing about the view", () => {
        // Arriving somewhere that names no view is not the visitor choosing the default, so a chip
        // on that page is still free to open on its own.
        for (const quiet of [null, "", "nonsense"]) {
            const state = hydrate(INITIAL, quiet);
            assert.equal(state.view, DEFAULT_VIEW, String(quiet));
            assert.equal(state.chosen, false, String(quiet));
        }
    });

    it("does not un-latch a choice already made", () => {
        const state = hydrate(choose(INITIAL, "diff"), null);
        assert.equal(state.chosen, true, "the latch never clears");
        assert.equal(state.view, DEFAULT_VIEW);
    });
});

describe("prefer / onOpen", () => {
    it("opens on the view the chip asked for", () => {
        const state = onOpen(prefer(INITIAL, "diff"));
        assert.equal(state.view, "diff");
    });

    it("spends the request, so a later entry is not dragged back", () => {
        const opened = onOpen(prefer(INITIAL, "diff"));
        assert.equal(opened.preferred, "");
        // The visitor moves on, then re-enters: the chip's old request must not reappear.
        const moved = choose(opened, "slider");
        assert.equal(onOpen(moved).view, "slider");
    });

    it("is ignored once anyone has chosen", () => {
        // The bug this prevents: a shared `?specView=diff` link, or a Back into one, being
        // overwritten by the chip that happens to sit on the same page.
        const fromUrl = hydrate(INITIAL, "diff");
        const asked = prefer(fromUrl, "spec");
        assert.equal(asked.preferred, "", "the request is not even recorded");
        assert.equal(onOpen(asked).view, "diff");

        const clicked = prefer(choose(INITIAL, "slider"), "spec");
        assert.equal(onOpen(clicked).view, "slider");
    });

    it("normalises what the chip asks for", () => {
        assert.equal(onOpen(prefer(INITIAL, "nonsense")).view, DEFAULT_VIEW);
    });

    it("leaves the view alone when nothing was requested", () => {
        const state: ViewChoice = {
            view: "diff",
            chosen: false,
            preferred: "",
        };
        assert.equal(onOpen(state).view, "diff");
    });
});

describe("viewParam", () => {
    it("says nothing for the default, which needs no parameter", () => {
        assert.equal(viewParam(DEFAULT_VIEW), "");
        assert.equal(viewParam("diff"), "diff");
    });

    it("spells the plain reference out, now that it is not the default", () => {
        // #4376 moved the default to the triptych, so `spec` is a view like any other and a URL
        // that leaves it unsaid means the triptych. A shared link to the reference alone has to
        // carry it or it reopens on three panels.
        assert.equal(viewParam(PLAIN_VIEW), "spec");
    });
});

describe("the lane's two fixed views", () => {
    it("opens on the triptych and keeps `spec` as the one that paints nothing", () => {
        // Both are load-bearing and they are no longer the same view: DEFAULT_VIEW decides what
        // the lane opens on and what the URL may omit, PLAIN_VIEW decides which view leaves the
        // stage's raster `<img>` alone. Collapsing them again is the regression this pins.
        assert.equal(DEFAULT_VIEW, "triptych");
        assert.equal(PLAIN_VIEW, "spec");
    });
});
