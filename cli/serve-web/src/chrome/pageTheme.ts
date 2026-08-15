// The **Page theme** setting: whether the site chrome follows the SELECTED PREVIEW THEME or the
// visitor's operating system. Replaces `assets/page-theme.js`.
//
// The catalog's Theme control re-renders the previews; until this existed it said nothing about the
// page around them, which followed `prefers-color-scheme` alone. So opening `…/m3-catalog/?theme=dark`
// handed a dark grid to a light page — the one combination nobody picked. With the setting on (the
// default) the chrome follows the choice instead.
//
// It is a SETTING, not a second toggle, because it is a standing preference rather than page state:
// somebody who keeps their machine in dark mode all day can turn it off in the header's Settings
// menu and keep the OS behaviour everywhere, without touching a control again. That is also why it
// is not carried in the URL — a shared link describes the previews, not the reader's chrome.
//
// Mechanically the whole feature is `color-scheme`: `serve.css` writes every mode-dependent value as
// a `light-dark()` pair, so pinning the scheme with `cp-scheme-light` / `cp-scheme-dark` on `<html>`
// repaints chrome, catalog palette and semantic badges together, with no second stylesheet to keep
// in step. The class is first set by the pre-paint script in `<head>` (`ServeWeb.pageThemeScript`),
// so a page served under `?theme=dark` never flashes light; this keeps it in step afterwards and
// owns the Settings menu.

const SETTING_KEY = "cp-page-theme";

/** "match" follows the picked preview theme (the default); "system" follows the OS. */
export type PageThemeSetting = "match" | "system";

export interface PageThemeApi {
    /** Re-resolve the chrome, optionally against a choice the caller just applied. */
    follow(choice?: string): void;
    setting(): PageThemeSetting;
}

declare global {
    interface Window {
        cpPageTheme?: PageThemeApi;
    }
}

function stored(key: string): string | null {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

export function setting(): PageThemeSetting {
    return stored(SETTING_KEY) === "system" ? "system" : "match";
}

/**
 * The localStorage key this catalog remembers its theme choice under, shared with the landing grid
 * and the viewer. Empty on a page with no theme control at all (the front door, `/status`), which
 * simply never pins a scheme.
 */
function themeKey(): string {
    return document.documentElement.getAttribute("data-cp-theme-key") || "";
}

/** The page mode a theme choice implies, or `""` when it implies nothing. */
function modeOf(choice: string): string {
    if (choice === "light" || choice === "dark") return choice;
    let button: Element | null = null;
    for (const candidate of document.querySelectorAll(".cp-theme-btn")) {
        if (candidate.getAttribute("data-theme-choice") === choice)
            button = candidate;
    }
    return button ? button.getAttribute("data-theme-mode") || "" : "";
}

/**
 * The theme choice in force on load, resolved exactly as the pre-paint script does: the URL first
 * (someone picked that chip, or was handed the link), then the choice this catalog remembers.
 * `uiMode` is the viewer's spelling of the same axis.
 */
function currentChoice(): string {
    const params = new URLSearchParams(location.search);
    const fromUrl = params.get("theme") || params.get("uiMode");
    if (fromUrl) return fromUrl;
    const key = themeKey();
    return (key && stored(key)) || "";
}

function paint(mode: string): void {
    const root = document.documentElement;
    root.classList.toggle("cp-scheme-light", mode === "light");
    root.classList.toggle("cp-scheme-dark", mode === "dark");
}

/** Re-resolve from the setting plus the choice on screen. */
export function follow(choice?: string): void {
    if (setting() === "system") {
        paint("");
        return;
    }
    paint(modeOf(choice === undefined ? currentChoice() : choice));
}

/**
 * Wire the Settings menu's radios. Split from the global on purpose: the API has to exist early —
 * `serve-chrome.js` is evaluated before the page's own scripts — while these inputs live in the
 * header and the `.cp-theme-btn` chips `follow()` reads live in `<main>`, so the wiring waits for a
 * parsed document. Under the old end-of-body `<script>` that was true by position; now it is said.
 */
export function wireSettingsMenu(): void {
    const inputs = document.querySelectorAll<HTMLInputElement>(
        "[data-cp-page-theme]",
    );
    if (!inputs.length) return;
    const current = setting();
    for (const input of inputs) {
        input.checked = input.value === current;
        input.addEventListener("change", () => {
            if (!input.checked) return;
            try {
                localStorage.setItem(SETTING_KEY, input.value);
            } catch {
                // Storage blocked: the choice applies to this page and is not remembered.
            }
            follow();
        });
    }
}

/**
 * Publish the global, then wire the menu once the document can be queried. The rest of the page
 * tells us when the visitor picks a theme — the landing grid's chips and the viewer's Theme select
 * both call `follow()` — so the chrome turns over with the previews rather than waiting for a
 * reload. Every caller reads `window.cpPageTheme` lazily inside a handler, so publishing early is
 * safe and publishing late would not be.
 */
export function installPageTheme(): void {
    window.cpPageTheme = { follow, setting };
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wireSettingsMenu, {
            once: true,
        });
    } else {
        wireSettingsMenu();
    }
}
