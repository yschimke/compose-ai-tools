// Behavioural contract for the page-shell bundle: `window.cpUrlState` and `window.cpPageTheme`.
//
// Neither had a test. Both are globals every serve page installs and several legacy scripts read,
// which makes them the two easiest things in this codebase to break invisibly — nothing imports
// them, so nothing fails to compile when they change shape.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, stubStorage } from "./setup.js";
import { installUrlState } from "../src/chrome/installUrlState.js";
import {
    follow,
    installPageTheme,
    setting,
    wireSettingsMenu,
} from "../src/chrome/pageTheme.js";

/** Point the document at a URL without navigating; happy-dom allows the assignment. */
function at(search: string): void {
    history.replaceState(null, "", `/catalog/${search}`);
}

describe("window.cpUrlState", () => {
    beforeEach(() => {
        at("");
        installUrlState();
    });
    afterEach(() => resetDom());

    it("publishes the global the legacy scripts read", () => {
        assert.equal(typeof window.cpUrlState?.push, "function");
        assert.equal(typeof window.cpUrlState?.sync, "function");
    });

    it("reads an absent param as empty, not null", () => {
        // Callers treat missing and empty as the same "not chosen"; a null would need every
        // caller to say so.
        assert.equal(window.cpUrlState?.get("tab"), "");
        at("?tab=components");
        assert.equal(window.cpUrlState?.get("tab"), "components");
    });

    it("leaves params it was not given alone", () => {
        // `token` and `session` are the server's; losing them would break the page outright.
        at("?token=abc&session=compose-m3");
        window.cpUrlState?.push({ tab: "screens" });
        const now = new URLSearchParams(location.search);
        assert.equal(now.get("token"), "abc");
        assert.equal(now.get("session"), "compose-m3");
        assert.equal(now.get("tab"), "screens");
    });

    it("clears a param rather than writing an empty one", () => {
        // The default state has to be the clean URL a visitor can be handed.
        at("?tab=screens");
        window.cpUrlState?.push({ tab: "" });
        assert.equal(location.search, "");
    });

    it("pushes a discrete choice and replaces a continuous one", () => {
        const start = history.length;
        window.cpUrlState?.push({ tab: "screens" });
        const afterPush = history.length;
        window.cpUrlState?.replace({ filter: "but" });
        assert.ok(afterPush > start, "push adds a history entry");
        assert.equal(history.length, afterPush, "replace does not");
    });

    it("writes nothing when the URL would not change", () => {
        at("?tab=screens");
        const before = history.length;
        window.cpUrlState?.push({ tab: "screens" });
        assert.equal(
            history.length,
            before,
            "an identical URL is not re-pushed",
        );
    });

    it("drops the params a slice owns but no longer supplies", () => {
        // The viewer's knob params are open-ended (`knob.<key>`), so clearing one has to remove
        // the param — which a per-name update cannot express.
        at("?knob.label=Hi&knob.size=2&token=abc");
        window.cpUrlState?.sync(
            { "knob.label": "Bye" },
            (n) => n.startsWith("knob."),
            false,
        );
        const now = new URLSearchParams(location.search);
        assert.equal(now.get("knob.label"), "Bye");
        assert.equal(now.get("knob.size"), null, "the unsupplied knob is gone");
        assert.equal(now.get("token"), "abc", "an unowned param survives");
    });
});

describe("window.cpPageTheme", () => {
    beforeEach(() => {
        at("");
        document.documentElement.className = "";
        document.documentElement.removeAttribute("data-cp-theme-key");
    });
    afterEach(() => resetDom());

    it("defaults to matching the preview theme", () => {
        stubStorage();
        assert.equal(setting(), "match");
    });

    it("pins the scheme from an explicit light or dark choice", () => {
        stubStorage();
        follow("dark");
        assert.ok(
            document.documentElement.classList.contains("cp-scheme-dark"),
        );
        follow("light");
        assert.ok(
            document.documentElement.classList.contains("cp-scheme-light"),
        );
        assert.ok(
            !document.documentElement.classList.contains("cp-scheme-dark"),
        );
    });

    it("resolves a declared theme through the chip that declares its mode", () => {
        stubStorage();
        document.body.innerHTML = `
          <button class="cp-theme-btn" data-theme-choice="brand" data-theme-mode="dark"></button>`;
        follow("brand");
        assert.ok(
            document.documentElement.classList.contains("cp-scheme-dark"),
        );
    });

    it("leaves the OS in charge for a theme that implies no mode", () => {
        stubStorage();
        document.body.innerHTML = `
          <button class="cp-theme-btn" data-theme-choice="brand" data-theme-mode=""></button>`;
        follow("brand");
        assert.ok(
            !document.documentElement.classList.contains("cp-scheme-dark"),
        );
        assert.ok(
            !document.documentElement.classList.contains("cp-scheme-light"),
        );
    });

    it("takes the choice from the URL before the remembered one", () => {
        const store = stubStorage();
        document.documentElement.setAttribute(
            "data-cp-theme-key",
            "cp-theme:m3",
        );
        localStorage.setItem("cp-theme:m3", "light");
        at("?theme=dark");
        follow();
        assert.ok(
            document.documentElement.classList.contains("cp-scheme-dark"),
            "the shared link wins over what this browser remembers",
        );
        assert.equal(
            store.get("cp-theme:m3"),
            "light",
            "and does not overwrite it",
        );
    });

    it("accepts the viewer's spelling of the same axis", () => {
        stubStorage();
        at("?uiMode=dark");
        follow();
        assert.ok(
            document.documentElement.classList.contains("cp-scheme-dark"),
        );
    });

    it("matches a baked viewer theme instead of a stale remembered catalog theme", () => {
        stubStorage();
        document.documentElement.setAttribute(
            "data-cp-theme-key",
            "cp-theme:m3",
        );
        localStorage.setItem("cp-theme:m3", "dark");
        document.body.innerHTML = `
          <div class="cp-viewer" data-preview-id="button__ideal__default__light"
               data-bg-theme="light"></div>`;

        follow();

        assert.ok(
            document.documentElement.classList.contains("cp-scheme-light"),
            "Match the preview theme follows the clean baked URL",
        );
        assert.ok(
            !document.documentElement.classList.contains("cp-scheme-dark"),
        );
    });

    it("hands the chrome back to the OS when set to system", () => {
        stubStorage();
        follow("dark");
        localStorage.setItem("cp-page-theme", "system");
        follow("dark");
        assert.equal(setting(), "system");
        assert.ok(
            !document.documentElement.classList.contains("cp-scheme-dark"),
        );
    });

    it("checks the radio matching the stored setting and applies a change", () => {
        const store = stubStorage();
        localStorage.setItem("cp-page-theme", "system");
        document.body.innerHTML = `
          <input type="radio" name="pt" data-cp-page-theme value="match">
          <input type="radio" name="pt" data-cp-page-theme value="system">`;
        wireSettingsMenu();
        const [match, system] = [
            ...document.querySelectorAll<HTMLInputElement>(
                "[data-cp-page-theme]",
            ),
        ];
        assert.equal(
            system.checked,
            true,
            "the stored setting is the one shown",
        );
        assert.equal(match.checked, false);

        system.checked = false;
        match.checked = true;
        match.dispatchEvent(new Event("change"));
        assert.equal(store.get("cp-page-theme"), "match");
    });

    it("publishes the global before the document is ready", () => {
        stubStorage();
        // The install runs from `serve-chrome.js`, which the shell emits ahead of the page's own
        // markup — so the API has to exist even though the menu it wires does not yet.
        installPageTheme();
        assert.equal(typeof window.cpPageTheme?.follow, "function");
        assert.equal(window.cpPageTheme?.setting(), "match");
    });
});
