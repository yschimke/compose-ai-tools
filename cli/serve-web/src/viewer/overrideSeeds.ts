// Which of the page URL's override values may drive a control, and how they are read.
//
// The viewer restores its controls from `location.search` on load and on Back/Forward
// (`hydrateFromUrl`). That restore has to reach the SAME answer the server rendered into the
// markup, because the two describe one page: the server paints the first frame and anything reading
// the HTML without JS, the hydrator paints every frame after. Where they disagree the control ends
// up claiming a value the pixels beside it never had — and the hydrator wins, so a server-side
// decision alone is cosmetic.
//
// Three rules, all of them the server's (`ServeOverrides.knobControlValue` / `rcControlValue`, and
// `ServeHttpServer.seedableOverrideParams`). They live here as pure functions so the client half is
// testable on its own rather than only observable as un-executed markup.

/** The `<kind>` tags a `knob.<key>=<kind>:<value>` may carry. */
const KNOB_KINDS = new Set(["string", "int", "float", "bool", "color"]);

/**
 * The axes this page will not let the URL drive, as the server named them
 * (`knob.<key>` / `rc.<name>`), read off `data-unseeded-overrides`.
 *
 * Non-empty on a page whose image deliberately did NOT apply them: a pinned revision, an accepted
 * `?fallback=baked` with no lane that could have honoured one, or a replayed preview for the axes
 * replay drops. Absent or empty everywhere else, which is the ordinary case.
 */
export function unseededOverrides(root: Element | null): Set<string> {
    const raw = root?.getAttribute("data-unseeded-overrides") || "";
    if (raw === "") return new Set();
    // A JSON array, not a delimited list: a knob key is an author string and nothing forbids a
    // comma in one. Splitting `knob.price,discount` yields two names that match nothing, so the
    // real axis would quietly stop being withheld — and the value the render ignored would come
    // back. Malformed input withholds NOTHING rather than guessing, which is the same answer as an
    // ordinary page and leaves the server's markup as the only thing that has to be right.
    try {
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return new Set();
        return new Set(
            parsed.filter((s): s is string => typeof s === "string"),
        );
    } catch {
        return new Set();
    }
}

/**
 * The lanes the BROWSER draws: the Wasm app, the Remote Compose JS canvas, and the CMP-Wasm player.
 *
 * Named here because withholding is a fact about the image the SERVER sent — a pinned revision's
 * historical bytes, a baked fallback, a replay that could not apply the axis. None of that binds a
 * lane that mounts the component in the browser and honours the control directly, so on those the
 * URL's value is the truthful one and hydration runs free.
 */
const IN_BROWSER_LANES = new Set(["wasm", "rc", "rc-wasm"]);

/**
 * Whether a page's withheld axes apply to [mode] — true for every server-rendered lane (the
 * snapshot, the daemon stream, SVG, a recording, an imported spec), false for [IN_BROWSER_LANES].
 *
 * Absent / unknown reads as the snapshot, which is where a page without `?mode=` opens.
 */
export function laneAppliesWithholding(mode: string | null): boolean {
    return !IN_BROWSER_LANES.has(mode || "");
}

/**
 * The value a declared knob's control should open on.
 *
 * `initial` is the declaration (`data-knob-initial`) and is the answer whenever the URL has nothing
 * to say — including when it names this axis but the page withheld it, and when it carries an empty
 * value for a non-string knob, which the server's parser skips so the render kept the declaration.
 * A legacy `<kind>:` tag is stripped only when it matches the declared kind, since a declared
 * *string* knob may legitimately hold text beginning `int:`.
 */
export function knobHydratedValue(opts: {
    wireKey: string;
    urlValue: string | null;
    initial: string;
    declaredKind: string;
    unseeded: Set<string>;
}): string {
    const { wireKey, urlValue, initial, declaredKind, unseeded } = opts;
    if (urlValue === null) return initial;
    if (unseeded.has("knob." + wireKey)) return initial;
    let value = urlValue;
    const sep = value.indexOf(":");
    if (sep > 0) {
        const prefix = value.substring(0, sep);
        if (KNOB_KINDS.has(prefix) && prefix === declaredKind)
            value = value.substring(sep + 1);
    }
    if (value === "" && declaredKind && declaredKind !== "string")
        return initial;
    return value;
}

/**
 * The same, for a Remote Compose knob.
 *
 * Stricter, because the server's parser types an `rc.` from its own wire tag rather than from the
 * declaration: a seed applies only when the kind it will parse as matches the declared one — an
 * explicit tag that agrees, or no tag on a `string` knob — and a blank value is skipped wholesale.
 * Anything else leaves the control on what the render used.
 */
export function rcHydratedValue(opts: {
    name: string;
    urlValue: string | null;
    initial: string;
    declaredKind: string;
    unseeded: Set<string>;
}): string {
    const { name, urlValue, initial, declaredKind, unseeded } = opts;
    if (urlValue === null) return initial;
    if (unseeded.has("rc." + name)) return initial;
    if (urlValue.trim() === "") return initial;
    const sep = urlValue.indexOf(":");
    const tag = sep > 0 ? urlValue.substring(0, sep) : null;
    const wireKind = tag && RC_KINDS.has(tag) ? tag : null;
    const value = wireKind !== null ? urlValue.substring(sep + 1) : urlValue;
    return (wireKind ?? "string") === declaredKind ? value : initial;
}

/** The `<kind>` tags an `rc.<name>=<kind>:<value>` may carry — [KNOB_KINDS] plus `dp`. */
const RC_KINDS = new Set(["string", "int", "float", "dp", "bool", "color"]);

/** `true` for `1` or `true` in any case — the rule the server's parser reads a bool by. */
export function isChecked(value: string): boolean {
    return value === "1" || value.toLowerCase() === "true";
}
