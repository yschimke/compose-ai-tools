// Which query parameters the viewer manages, and which it must leave alone.
//
// `window.cpUrlState.sync` DROPS any owned parameter the caller does not supply a value for. That
// makes this list load-bearing in both directions, and neither mistake announces itself:
//
//   - claim a parameter the viewer does not set, and every sync silently deletes it — a session
//     token, a catalog filter, someone else's deep link, gone on the next slider drag;
//   - fail to claim one the viewer DOES set, and a stale value survives after the control that put
//     it there has been turned off, so the address bar describes a page that is not on screen.
//
// The prefixed families are open-ended by design: author knobs and Remote Compose seeds are named
// by the preview, so they cannot be enumerated here.

/** Parameters the viewer writes by name. */
export const URL_STATE_PARAMS: readonly string[] = [
    "device",
    "localeTag",
    "orientation",
    "fontScale",
    "uiMode",
    "themeProvider",
    "focus",
    "gestures",
    "touchOverlay",
    "scroll",
    "mode",
    "sizeMode",
    "rcPlayer",
    "specView",
    "motion",
    "widthPx",
    "heightPx",
    "minWidthPx",
    "minHeightPx",
    "maxWidthPx",
    "maxHeightPx",
    "exploded",
    "explodeTilt",
    "explodeSpin",
    "explodeGap",
    "explodeDepth",
];

/** Prefixes whose members are named by the preview rather than by this list. */
export const URL_STATE_PREFIXES: readonly string[] = ["knob.", "rc."];

export function ownsUrlParam(name: string): boolean {
    return (
        URL_STATE_PARAMS.includes(name) ||
        URL_STATE_PREFIXES.some((prefix) => name.startsWith(prefix))
    );
}
