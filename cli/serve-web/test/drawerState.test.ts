// The drawer rules, as a table.
//
// `assets/viewer-drawers.js` had no test of any kind, and its defaults are exactly the sort of
// thing that changes underneath you: #3893 stopped serving `cp-controls-open` and nothing failed
// until page captures started timing out on controls inside a `display: none` column. These pin
// each band's answer so a change to one is a change to a line here.

import assert from "node:assert/strict";
import {
    drawerToClose,
    foldKey,
    resolveControlsOpen,
    resolveNavOpen,
    shouldPersistDrawer,
    toggleIdFor,
} from "../src/viewer/drawerState.js";

const PHONE = { mobile: true, wide: false };
const MIDDLE = { mobile: false, wide: false };
const WIDE = { mobile: false, wide: true };

describe("drawerState", () => {
    describe("resolveNavOpen", () => {
        it("follows the CSS default when nothing is stored", () => {
            // Shown wide, hidden in the middle band. This is the case the explicit class exists
            // for: without resolving it, `classList.contains` would read the wide band's default
            // as closed and the toggle would be inert at the width where the column costs most.
            assert.equal(resolveNavOpen(WIDE, null), true);
            assert.equal(resolveNavOpen(MIDDLE, null), false);
        });

        it("lets a stored choice win in both directions off the phone", () => {
            assert.equal(resolveNavOpen(WIDE, "0"), false);
            assert.equal(resolveNavOpen(MIDDLE, "1"), true);
        });

        it("is closed on a phone whatever a desktop visit stored", () => {
            // An open bottom sheet is a modal over the preview, never a resting state.
            assert.equal(resolveNavOpen(PHONE, "1"), false);
            assert.equal(resolveNavOpen(PHONE, null), false);
        });

        it("ignores a stored value that is neither 1 nor 0", () => {
            assert.equal(resolveNavOpen(WIDE, "yes"), false);
        });
    });

    describe("resolveControlsOpen", () => {
        it("keeps whatever default the server's markup shipped", () => {
            // The one that moved in #3893: this used to be true because `cp-controls-open` was
            // emitted, and is false now that it is not. The rule did not change — the input did.
            assert.equal(resolveControlsOpen(MIDDLE, null, false), false);
            assert.equal(resolveControlsOpen(MIDDLE, null, true), true);
        });

        it("lets a stored choice win over the server default", () => {
            assert.equal(resolveControlsOpen(WIDE, "1", false), true);
            assert.equal(resolveControlsOpen(WIDE, "0", true), false);
        });

        it("is closed on a phone so the preview leads", () => {
            assert.equal(resolveControlsOpen(PHONE, "1", true), false);
            assert.equal(resolveControlsOpen(PHONE, null, true), false);
        });
    });

    describe("shouldPersistDrawer", () => {
        it("remembers off the phone and stores nothing on it", () => {
            assert.equal(shouldPersistDrawer(WIDE), true);
            assert.equal(shouldPersistDrawer(MIDDLE), true);
            // A sheet is transient by nature; storing one open would cover the next preview.
            assert.equal(shouldPersistDrawer(PHONE), false);
        });
    });

    describe("drawerToClose", () => {
        it("closes the other sheet on a phone so they never stack", () => {
            assert.equal(
                drawerToClose(PHONE, "cp-nav-open"),
                "cp-controls-open",
            );
            assert.equal(
                drawerToClose(PHONE, "cp-controls-open"),
                "cp-nav-open",
            );
        });

        it("leaves both open off the phone, where they are columns", () => {
            assert.equal(drawerToClose(MIDDLE, "cp-nav-open"), null);
            assert.equal(drawerToClose(WIDE, "cp-controls-open"), null);
        });
    });

    it("maps each drawer to the toggle that drives it", () => {
        assert.equal(toggleIdFor("cp-nav-open"), "cp-nav-toggle");
        assert.equal(toggleIdFor("cp-controls-open"), "cp-controls-toggle");
    });

    it("scopes fold keys per catalog", () => {
        // Unscoped, folding this catalog's thirty-state axis would fold a normally-inline axis on
        // every unrelated catalog served from the same origin.
        assert.equal(
            foldKey("compose-m3", "cp-nav-toggle"),
            "cp-fold:compose-m3.cp-nav-toggle",
        );
        assert.notEqual(
            foldKey("compose-m3", "cp-nav-toggle"),
            foldKey("wear-m3", "cp-nav-toggle"),
        );
    });
});
