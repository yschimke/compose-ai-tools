// Which theme the viewer asks for, and what the Theme bar shows.
//
// The pair of rules here look like one rule and are not, and both ways of collapsing them produce a
// page that looks right and behaves wrong. That is the whole content of this file.

import assert from "node:assert/strict";
import {
    activeThemeChoice,
    chosenThemeProvider,
    chosenUiMode,
    pinsTheme,
    themeBarButton,
} from "../src/viewer/themeChoice.js";

const select = (
    over: Partial<{
        value: string;
        disabled: boolean;
        active: boolean;
        defaultValue: string;
    }> = {},
) => ({
    value: "dark",
    disabled: false,
    active: true,
    // The preview under test is baked light unless a case says otherwise, so the default `value`
    // above is a genuine override.
    defaultValue: "light",
    ...over,
});

describe("activeThemeChoice", () => {
    it("answers the picked theme once the visitor has picked", () => {
        assert.equal(activeThemeChoice(select()), "dark");
    });

    it("answers nothing before the first pick, even though the select shows one", () => {
        // A preview arrives with its baked theme displayed and `data-theme-active="0"`. Naming the
        // theme the pixels already have is information; SENDING it would route a published catalog
        // to the daemon to re-render a picture it has already baked.
        assert.equal(activeThemeChoice(select({ active: false })), "");
    });

    it("answers nothing while the control is disabled", () => {
        // A pinned page re-renders nothing, so every override is moot.
        assert.equal(activeThemeChoice(select({ disabled: true })), "");
    });

    it("still names the theme a frozen frame was rendered with", () => {
        // The spec and motion lanes disable every re-rendering control, this one included — but
        // the frame under the lane was produced with the picked theme and the spec lane is
        // actively comparing against it. Answering "" made `query()` drop `themeProvider`, which
        // `syncUrl` then deleted from the address bar: the page went on showing (and diffing) a
        // Light Medium Contrast render under a URL claiming the baseline, so reloading it gave a
        // different picture than the one on screen.
        assert.equal(
            activeThemeChoice(select({ disabled: true }), true),
            "dark",
        );
    });

    it("does not resurrect a choice that was never made", () => {
        // Frozen or not, an untouched select is still only DISPLAYING the baked theme.
        assert.equal(
            activeThemeChoice(select({ disabled: true, active: false }), true),
            "",
        );
    });

    it("answers nothing on a page with no theme control at all", () => {
        assert.equal(activeThemeChoice(null), "");
    });

    it("answers nothing for a pick that lands back on the baked theme", () => {
        // #4218. The light/dark toggle writes `uiMode` into the URL on the way through, so
        // clicking dark and back to light leaves the select active with `light` in it. The visitor
        // has made no net choice, and answering "light" here pinned `?uiMode=light` in the address
        // bar — which then read as an override and suppressed the Figma comparison on pixels that
        // are exactly the ones it was scored against.
        assert.equal(
            activeThemeChoice(
                select({ value: "light", defaultValue: "light" }),
            ),
            "",
        );
    });

    it("keeps a frozen frame from naming a theme it did not deviate to", () => {
        // The frozen-frame carve-out exists so the URL goes on describing the pinned picture. A
        // default-valued choice describes it just as well by saying nothing.
        assert.equal(
            activeThemeChoice(
                select({ value: "dark", defaultValue: "dark", disabled: true }),
                true,
            ),
            "",
        );
    });

    it("still names the opposite appearance on a dark-baked preview", () => {
        // The forgiveness is per-preview, not "light is always free": on a `__dark` variant it is
        // `dark` that asks for nothing and `light` that is a real override.
        assert.equal(
            activeThemeChoice(select({ value: "light", defaultValue: "dark" })),
            "light",
        );
    });

    it("treats every choice as a pin when the catalog names no baked theme", () => {
        // An empty `data-default-theme` is "the server could not say", not "light". The select
        // still displays Light, but the baked pixels may not be a light render, so a `uiMode`
        // there is a request that has to travel.
        assert.equal(
            activeThemeChoice(select({ value: "light", defaultValue: "" })),
            "light",
        );
    });
});

describe("pinsTheme", () => {
    it("forgives only the value the preview would have shown anyway", () => {
        assert.equal(pinsTheme("light", "light"), false);
        assert.equal(pinsTheme("dark", "dark"), false);
        assert.equal(pinsTheme("light", "dark"), true);
        assert.equal(pinsTheme("dark", "light"), true);
    });

    it("never forgives a declared provider", () => {
        // A provider is always something someone asked for — no preview is baked in one, so the
        // default it would be compared against cannot match it.
        assert.equal(pinsTheme("theme:com.example.BrandTheme", "light"), true);
    });

    it("is not a pin when there is no choice at all", () => {
        assert.equal(pinsTheme("", "light"), false);
        assert.equal(pinsTheme("", ""), false);
    });

    it("pins whatever it is given when no default is known", () => {
        assert.equal(pinsTheme("light", ""), true);
    });
});

describe("chosenUiMode / chosenThemeProvider", () => {
    it("routes the two system appearances to uiMode", () => {
        assert.equal(chosenUiMode("light"), "light");
        assert.equal(chosenUiMode("dark"), "dark");
    });

    it("routes an app-declared provider to themeProvider, without its prefix", () => {
        assert.equal(
            chosenThemeProvider("theme:com.example.BrandTheme"),
            "com.example.BrandTheme",
        );
    });

    it("keeps the two mutually exclusive", () => {
        // They become different query parameters, and a value answering to both would send the
        // preview two contradictory instructions about how to theme itself.
        for (const value of [
            "light",
            "dark",
            "theme:com.example.X",
            "",
            "sepia",
        ]) {
            const mode = chosenUiMode(value);
            const provider = chosenThemeProvider(value);
            assert.ok(!(mode && provider), `${value} answered to both`);
        }
    });

    it("says nothing for an unrecognised value", () => {
        assert.equal(chosenUiMode("sepia"), "");
        assert.equal(chosenThemeProvider("sepia"), "");
        assert.equal(chosenUiMode(""), "");
        assert.equal(chosenThemeProvider(""), "");
    });

    it("does not mistake a provider whose FQN merely contains the prefix", () => {
        assert.equal(chosenThemeProvider("app.theme:Thing"), "");
    });

    it("carries an empty provider FQN through as empty rather than as a bare prefix", () => {
        assert.equal(chosenThemeProvider("theme:"), "");
    });
});

describe("themeBarButton", () => {
    const enabled = { disabled: false };

    it("presses the chip the select DISPLAYS, not the one it has chosen", () => {
        // Before the first pick `activeThemeChoice` is "" while the select still shows the baked
        // theme. Driving `pressed` from the choice would leave the whole bar unpressed over pixels
        // that plainly have a theme.
        const state = themeBarButton(
            "dark",
            { value: "dark", disabled: false },
            enabled,
        );
        assert.equal(state.pressed, true);
        assert.equal(
            themeBarButton("light", { value: "dark", disabled: false }, enabled)
                .pressed,
            false,
        );
    });

    it("disables a chip the select has no option for", () => {
        // A theme this preview cannot render is disabled rather than hidden — its absence is
        // itself information about the preview.
        assert.equal(
            themeBarButton("sepia", { value: "dark", disabled: false }, null)
                .disabled,
            true,
        );
    });

    it("disables a chip whose option the server disabled", () => {
        assert.equal(
            themeBarButton(
                "light",
                { value: "dark", disabled: false },
                { disabled: true },
            ).disabled,
            true,
        );
    });

    it("disables every chip when the select itself is disabled", () => {
        const state = themeBarButton(
            "dark",
            { value: "dark", disabled: true },
            enabled,
        );
        assert.equal(state.disabled, true);
        // Still pressed: a pinned page shows which theme it is pinned AT, it does not forget.
        assert.equal(state.pressed, true);
    });
});
