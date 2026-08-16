// What lands in a render URL, and what deliberately does not.
//
// Every rule here decides the same thing: whether the viewer stays on the INSTANT BAKED SNAPSHOT or
// routes to the daemon for a fresh render. A published catalog serves a pre-rendered PNG for the
// plain URL and re-renders on demand the moment any override appears, so a parameter emitted when
// it did not need to be turns a free page load into a render — on every link anyone copies, and on
// every reader who follows one. The reverse mistake is worse: a parameter dropped when it WAS
// needed serves a picture of something other than what the controls say.
//
// Neither failure looks like a failure. The page renders, the picture is plausible, and only the
// latency or a careful comparison gives it away — which is why these are worth having as a table
// rather than as conditions spread through a 3,000-line IIFE.
//
// Everything is DOM-free: `viewer.js` reads the controls and passes plain values.

/** A knob's declared type. Anything undeclared parses as `string` server-side. */
export type KnobKind = "string" | "int" | "float" | "bool" | "color";

/**
 * Whether an author-declared knob (`knob.<key>=`) belongs in the URL.
 *
 * Two rules, and the first is the subtle one. An empty STRING is a real value — a cleared label, or
 * a variant seeded to `""` — so it is sent. An emptied NUMBER field has nothing to send, and
 * `knob.count=` would be indistinguishable from clearing it in a map that replaces the daemon's
 * whole override bag.
 *
 * The second: a knob still at its declared default is omitted, because any `knob.*` at all routes a
 * published catalog to the daemon. Restating the default would cost a render to reproduce the
 * picture already baked.
 */
export function knobEmitted(
    value: string,
    initial: string,
    kind: KnobKind | string,
): boolean {
    if (value === "" && kind !== "string") return false;
    return value !== initial;
}

/**
 * Whether a Remote Compose knob (`rc.<name>=<kind>:<value>`) belongs in the URL.
 *
 * Stricter than {@link knobEmitted} on one point: an empty value is never sent, whatever the kind.
 * An RC seed is typed by its `<kind>:` prefix and there is no seed that means "empty".
 */
export function rcKnobEmitted(value: string, initial: string): boolean {
    if (value === "") return false;
    return value !== initial;
}

/** An RC knob's wire value: the kind prefix is what types the seed server-side. */
export function rcKnobValue(kind: string, value: string): string {
    return `${kind || "string"}:${value}`;
}

/**
 * Whether an `?exploded=` parameter asks for the 3D view.
 *
 * The same boolean forms `ServeExplodedSvg.enabled` accepts, so a hand-typed or bookmarked
 * `?exploded=on` opens the view the render endpoint would serve for that URL. A stricter reading
 * here showed the flat PNG and then dropped the parameter on the next URL sync — the link worked
 * on the server and not in the page that owns the address bar.
 */
export function explodeParamOn(raw: string | null | undefined): boolean {
    if (raw === null || raw === undefined) return false;
    if (raw === "") return true; // a bare `?exploded`
    return ["1", "true", "on", "yes"].includes(String(raw).toLowerCase());
}

export interface ExplodeKnob {
    /** The URL parameter name, e.g. `explodeTilt`. */
    param: string;
    value: string;
    /** The authored default, from `data-cp-default`. */
    defaultValue: string;
}

/**
 * The exploded view's parameters.
 *
 * Every knob lands in the URL, which is the whole reason the projection is server-side: the angle
 * someone tuned is part of the link they copy, the SVG they download, and the picture a reviewer
 * sees in a PR — not client state that dies with the tab. A knob left at its authored default is
 * still omitted, so the common URL stays `?exploded=1` rather than five parameters restating the
 * server's own defaults.
 */
export function explodeParams(knobs: ExplodeKnob[]): string[] {
    const parts = ["exploded=1"];
    for (const knob of knobs) {
        if (knob.value === "" || knob.value === knob.defaultValue) continue;
        parts.push(`${knob.param}=${encodeURIComponent(knob.value)}`);
    }
    return parts;
}

/** Append `parts` to a query string that may be empty. */
export function appendQuery(qs: string, parts: string[]): string {
    if (!parts.length) return qs;
    const added = parts.join("&");
    return qs ? `${qs}&${added}` : added;
}

/**
 * "Full page (scroll)" — the server routes SVG to `compose/figma-svg-long` and PNG to
 * `render/scroll/long`, so the same parameter serves both lanes.
 */
export function withScroll(qs: string, scrollLong: boolean): string {
    return scrollLong ? appendQuery(qs, ["scroll=long"]) : qs;
}

/**
 * The exploded view rides ONLY the `.svg` extension.
 *
 * It is a presentation of the vector export; appending it to the raster PNG lane would silently do
 * nothing, which is why the toggle turns SVG on rather than offering the combination.
 */
export function withExplode(
    ext: string,
    qs: string,
    on: boolean,
    knobs: ExplodeKnob[],
): string {
    if (ext !== ".svg" || !on) return qs;
    return appendQuery(qs, explodeParams(knobs));
}

export function withSnapshotFormat(
    ext: string,
    qs: string,
    options: { scrollLong: boolean; exploded: boolean; knobs: ExplodeKnob[] },
): string {
    return withExplode(
        ext,
        withScroll(qs, options.scrollLong),
        options.exploded,
        options.knobs,
    );
}

/**
 * A size field's device pixels, or `null` when the field says nothing usable.
 *
 * `null` rather than a clamped number for a blank or non-positive entry: a zero-width render is not
 * a smaller picture, it is a failed one, and sending `widthPx=0` would ask the daemon for it.
 */
export function sizePx(value: string, density: number): string | null {
    const dp = parseFloat(value);
    if (!(dp > 0)) return null;
    return String(Math.max(1, Math.round(dp * density)));
}

export type SizeMode = "fixed" | "min" | "max" | "within" | "";

/** The size fields a mode reads, and the override key each becomes. */
export const SIZE_FIELDS: Record<
    Exclude<SizeMode, "">,
    Array<[field: string, key: string]>
> = {
    fixed: [
        ["fixedW", "widthPx"],
        ["fixedH", "heightPx"],
    ],
    min: [
        ["minW", "minWidthPx"],
        ["minH", "minHeightPx"],
    ],
    max: [
        ["maxW", "maxWidthPx"],
        ["maxH", "maxHeightPx"],
    ],
    // `within` is BOTH bounds, which is what makes it a mode of its own rather than a label: a
    // reader asking for "within 320–600" is asking for two constraints at once.
    within: [
        ["minW", "minWidthPx"],
        ["minH", "minHeightPx"],
        ["maxW", "maxWidthPx"],
        ["maxH", "maxHeightPx"],
    ],
};

/**
 * The size overrides a mode contributes.
 *
 * `read` is handed the field name and answers its device-pixel value or `null`. A mode reads only
 * its own fields, so switching from `fixed` to `min` cannot leave a stale `widthPx` on the URL —
 * the fields keep their values in the form, deliberately, so switching back restores them.
 */
export function sizeOverrides(
    mode: SizeMode,
    read: (field: string) => string | null,
): Record<string, string> {
    const overrides: Record<string, string> = {};
    if (!mode || !(mode in SIZE_FIELDS)) return overrides;
    for (const [field, key] of SIZE_FIELDS[mode as Exclude<SizeMode, "">]) {
        const value = read(field);
        if (value !== null) overrides[key] = value;
    }
    return overrides;
}
