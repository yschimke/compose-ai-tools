// Which source the spec lane is comparing the render against.
//
// The lane's four views — Spec, Diff, Triptych, Slider — are instruments over A PAIR OF IMAGES, and
// they are agnostic about where the second image came from. So a second comparison is a second
// SOURCE for the existing lane, not a second mode: the latching rules in `views.ts`, the single
// normalisation pass that keeps the four in one pixel space, and the URL state all carry over
// untouched (issue #4621).
//
// The decisions live here, next door to the lane, for the same reason `views.ts` and `verdict.ts`
// do: the lane itself is imperative DOM against a server-rendered picker, and the questions worth
// getting right — which source is active, whether a switch changes anything, what the panel should
// admit about where its pixels came from — are pure and answerable without a browser.

/** One thing the lane can put on the stage beside the render, as the server described it. */
export interface SpecSource {
    /** `kit` / `parallel` — the picker's value, and the token URL state would carry. */
    id: string;
    /** What the button reads, e.g. `Figma` or `wear-m3-catalog`. */
    label: string;
    /** Same-origin URL of the image to compare against. */
    src: string;
    /**
     * One line naming where these pixels came from, shown while the source is selected. Empty for a
     * source that needs no caveat.
     */
    provenance?: string;
}

/**
 * The active source: the one marked pressed, else the first.
 *
 * Falling back to the first rather than to "none" is what makes the picker's initial state
 * describable in markup alone — the server marks its default and the lane agrees, but a picker that
 * somehow arrives with nothing pressed still compares against something rather than going blank.
 */
export function activeSource(
    sources: readonly SpecSource[],
    pressedId: string | null,
): SpecSource | null {
    if (sources.length === 0) return null;
    if (pressedId) {
        for (const source of sources)
            if (source.id === pressedId) return source;
    }
    return sources[0];
}

/**
 * Whether the lane offers a genuine choice.
 *
 * One source is not a picker with a single button — it is no picker. A catalog that declares no
 * `compareWith` pairing therefore sees exactly the lane it saw before, with no control that acts on
 * nothing.
 */
export function offersChoice(sources: readonly SpecSource[]): boolean {
    return sources.length > 1;
}

/**
 * Whether switching to [nextId] is worth doing.
 *
 * Re-entering the lane costs a raster request and a fresh normalisation pass, so re-picking the
 * source already showing has to be a no-op rather than a cheap-looking rebuild.
 */
export function changesSource(
    sources: readonly SpecSource[],
    pressedId: string | null,
    nextId: string,
): boolean {
    const active = activeSource(sources, pressedId);
    if (!active) return false;
    if (active.id === nextId) return false;
    return sources.some((source) => source.id === nextId);
}

/**
 * What the lane should say about the panel it is showing.
 *
 * The two kinds are NOT symmetric and the label is where that is admitted. The kit reference is a
 * specification — static, imported, fixed at publish time. The sibling's panel is another catalog's
 * RENDER, produced under its own theme, knobs and overrides rather than the ones that produced the
 * render beside it. Implying they are the same kind of thing is the detail most likely to make this
 * feature quietly misleading, so a source that carries a caveat states it.
 */
export function sourceNote(source: SpecSource | null): string {
    if (!source) return "";
    return source.provenance ? source.provenance.trim() : "";
}
