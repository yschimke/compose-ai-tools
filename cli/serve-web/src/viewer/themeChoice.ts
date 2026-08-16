// Which theme the viewer is asking for, and what the Theme bar shows.
//
// Two questions that look like one and are not. What the select DISPLAYS and what the page has
// actually CHOSEN differ until the first pick: a preview arrives with its baked theme showing in the
// select, marked inactive, because naming the theme the pixels already have is information — but
// sending it as an override would route a published catalog to the daemon to re-render a picture it
// has already baked.
//
// So `activeThemeChoice` is gated on that flag and the bar's pressed state is not. Collapsing the
// two either way produces a page that looks right and behaves wrong: gate the bar and it reads as
// "no theme" over pixels that plainly have one; ungate the choice and every first render is a
// needless re-render of the baked frame.
//
// DOM-free: `viewer.js` reads the select and passes plain values.

/** The `theme:` prefix an app-declared provider carries in the select's option values. */
const PROVIDER_PREFIX = "theme:";

export interface ThemeSelectState {
    value: string;
    disabled: boolean;
    /** The select's `data-theme-active` — `"1"` once the visitor has actually picked. */
    active: boolean;
}

/**
 * The theme the page is asking the server for, or `""` for "whatever is baked".
 *
 * Empty before the first pick, and empty while the control is disabled — but only for the reason
 * the disable was originally about. `disabled` carries two unrelated meanings and conflating them
 * loses a choice that is still in effect:
 *
 *   * **Nothing can re-render** (a pinned page, a static catalog). No override is deliverable, so
 *     there is no choice to name. This is the `frozenFrame = false` case, and the default.
 *   * **This lane froze the frame** — the spec lane and the motion lane put a FIXED picture on the
 *     stage, so `syncServerControls` disables every re-rendering control including this one. The
 *     theme is not moot there: the render underneath was produced with it, and the spec lane is
 *     actively comparing against that render. Answering `""` made `query()` stop emitting
 *     `themeProvider`, which `syncUrl` then DELETED from the address bar — so the page went on
 *     showing a Light Medium Contrast render, and diffing it, under a URL that claimed the
 *     baseline. Reload that URL and you got a different picture than the one you were looking at.
 *
 * So a frozen frame keeps naming its theme. Nothing is re-rendered by it — every control that
 * would is disabled anyway — but the URL, the copyable link and the spec lane's baseline test all
 * go on describing the frame that is actually on the stage.
 */
export function activeThemeChoice(
    select: ThemeSelectState | null,
    frozenFrame = false,
): string {
    if (!select || !select.active) return "";
    if (select.disabled && !frozenFrame) return "";
    return select.value;
}

/** The `uiMode` override — only the two system appearances, never a provider. */
export function chosenUiMode(choice: string): string {
    return choice === "light" || choice === "dark" ? choice : "";
}

/** The `themeProvider` override — the provider FQN, without its prefix. */
export function chosenThemeProvider(choice: string): string {
    return choice.startsWith(PROVIDER_PREFIX)
        ? choice.slice(PROVIDER_PREFIX.length)
        : "";
}

export interface ThemeBarButton {
    /** Whether the button can be pressed at all. */
    disabled: boolean;
    /** Whether it reads as the current theme. */
    pressed: boolean;
}

/**
 * One Theme-bar chip's state, mirroring what the select has already been told.
 *
 * `pressed` tracks what the select DISPLAYS, not {@link activeThemeChoice}: before the first pick
 * the select still shows the preview's baked theme, and a bar with nothing pressed would read as
 * "no theme" over pixels that plainly have one.
 *
 * `option` is the matching `<option>`'s disabled state, or `null` when the select has no such
 * option — a theme this preview cannot render is disabled rather than hidden, because its absence
 * is itself information.
 */
export function themeBarButton(
    buttonValue: string,
    select: { value: string; disabled: boolean },
    option: { disabled: boolean } | null,
): ThemeBarButton {
    return {
        disabled: select.disabled || !option || option.disabled,
        pressed: select.value === buttonValue,
    };
}
