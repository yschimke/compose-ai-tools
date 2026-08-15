// The **Page theme** setting: whether the site chrome follows the SELECTED PREVIEW THEME or the
// visitor's operating system.
//
// The catalog's Theme control re-renders the previews; until this existed it said nothing about the
// page around them, which followed `prefers-color-scheme` alone. So opening
// `…/m3-catalog/?theme=dark` handed a dark grid to a light page — the one combination nobody picked.
// With the setting on (the default) the chrome follows the choice instead: pick Dark and the whole
// page goes dark, share the `?theme=dark` link and it opens dark for whoever follows it.
//
// It is a SETTING, not a second toggle, because it is a standing preference rather than page state:
// somebody who keeps their machine in dark mode all day can turn it off in the header's Settings
// menu and keep the OS behaviour, on every page and every catalog, without touching a control again.
// That is also why it is not carried in the URL — a shared link describes the previews, not the
// reader's chrome preference.
//
// Mechanically the whole feature is `color-scheme`. `serve.css` writes every mode-dependent value
// as a `light-dark()` pair (so does the catalog palette `ServeThemeCss` inlines over it), so pinning
// the scheme with `cp-scheme-light` / `cp-scheme-dark` on <html> re-paints the page — chrome,
// catalog palette and semantic badges together — with no second stylesheet to keep in step. The
// class is first set by the pre-paint script in the page <head> (see `ServeWeb.pageThemeScript`), so
// a page served under `?theme=dark` never flashes light; this file keeps it in step afterwards and
// owns the Settings menu.
//
// An explicit light/dark choice moves the chrome. A declared theme does too when its catalog
// metadata resolves an unambiguous mode; an unqualified declared theme still follows the OS.
(function () {
    "use strict";

    var SETTING_KEY = "cp-page-theme";
    var root = document.documentElement;
    // Written into the page by the server: the localStorage key this catalog remembers its theme
    // choice under, shared with the landing grid and the viewer. Empty on a page with no theme
    // control at all (the front door, /status), which simply never pins a scheme.
    var themeKey = root.getAttribute("data-cp-theme-key") || "";

    function stored(key) {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    /** "match" (default) or "system". */
    function setting() {
        return stored(SETTING_KEY) === "system" ? "system" : "match";
    }

    /** The page mode a theme choice implies, or "" when it implies nothing. */
    function modeOf(choice) {
        if (choice === "light" || choice === "dark") return choice;
        var button = null;
        document.querySelectorAll(".cp-theme-btn").forEach(function (candidate) {
            if (candidate.getAttribute("data-theme-choice") === choice) button = candidate;
        });
        return button ? button.getAttribute("data-theme-mode") || "" : "";
    }

    /**
     * The theme choice in force on load, resolved exactly as the pre-paint script does: the URL
     * first (someone picked that chip, or was handed the link), then the choice this catalog
     * remembers. `uiMode` is the viewer's spelling of the same axis.
     */
    function currentChoice() {
        var params = new URLSearchParams(location.search);
        var fromUrl = params.get("theme") || params.get("uiMode");
        if (fromUrl) return fromUrl;
        return (themeKey && stored(themeKey)) || "";
    }

    function paint(mode) {
        root.classList.toggle("cp-scheme-light", mode === "light");
        root.classList.toggle("cp-scheme-dark", mode === "dark");
    }

    /** Re-resolve from the setting + the choice on screen. */
    function refresh(choice) {
        if (setting() === "system") {
            paint("");
            return;
        }
        paint(modeOf(choice === undefined ? currentChoice() : choice));
    }

    // The rest of the page tells us when the visitor picks a theme — the landing grid's chips and
    // the viewer's Theme select both call this — so the chrome turns over with the previews rather
    // than waiting for a reload.
    window.cpPageTheme = { follow: refresh, setting: setting };

    var inputs = document.querySelectorAll("[data-cp-page-theme]");
    if (!inputs.length) return;
    var current = setting();
    inputs.forEach(function (input) {
        input.checked = input.value === current;
        input.addEventListener("change", function () {
            if (!input.checked) return;
            try {
                localStorage.setItem(SETTING_KEY, input.value);
            } catch (e) {}
            refresh();
        });
    });
})();
