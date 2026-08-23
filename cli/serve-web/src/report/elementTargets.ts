// What a reporter can point at, and in which plane it ends up recorded.
//
// Two ways to name a part of a render, and they are not interchangeable.
//
// A TAG is an identity: `testTag → {count, bounds, space}` from the published index. It survives a
// re-render, a re-layout and an inserted sibling, which is the whole reason the index exists — a
// `SemanticsRefs` path does not, because it indexes siblings sharing an anchor, so inserting a
// Button ahead of `r/role:Button[0]` silently retargets that string at different pixels.
//
// A REGION is a rectangle and nothing more. It cannot be resolved against a later render, so it can
// only ever support a geometric acceptance — but it needs nothing from the server, which makes it
// the honest fallback everywhere a tag would be a guess.
//
// Selection is a REPORT affordance. Nothing here accepts a difference; it only says which part of
// the picture the report is about.

import type { Bounds } from "./locator.js";

/** One entry of the published index, as `ServeAnnotationsPayload.encodeTags` writes it. */
interface WireTagEntry {
    count?: number;
    bounds?: { x: number; y: number; width: number; height: number } | null;
    space?: string;
}

/** A tag offered in the picker. */
export interface TagTarget {
    /** The tag VERBATIM — no trimming anywhere in the chain. */
    tag: string;
    count: number;
    /** Absent for a tag whose every carrying node had a zero-area box. */
    bounds?: Bounds;
    /**
     * True when more than one node carries the tag.
     *
     * Such a tag is **not a usable element identity** — a consumer resolving "the node with this
     * tag" would silently pick one of several — so it may be listed (knowing the tag exists is
     * useful) but never chosen as an element selector. `count` counts every node carrying the tag
     * including ones whose bounds are unusable, precisely so a zero-area duplicate cannot hide
     * behind a usable sibling and report a genuinely ambiguous tag as unique.
     */
    ambiguous: boolean;
}

/** The only plane the index is allowed to publish; see D1. */
const RENDER_PIXELS = "render-pixels";

/**
 * The index payload as a picker's worth of targets, in tag order.
 *
 * Sorted by tag rather than left in the payload's own (depth-first) order because a `<select>` the
 * reader scans is a list to find a name in, not a walk of the tree.
 *
 * An entry that declares no space, or declares one this version does not know, is **dropped**. Not
 * defaulted: silently reading an undeclared index as render-pixel is exactly what the discriminator
 * was added to prevent, and a future canonical-plane producer must not be mistaken for this one by
 * an older page. An entry counting fewer than one node is dropped for the same reason — it is a
 * producer bug, not something to resolve badly.
 */
export function tagTargets(payload: unknown): TagTarget[] {
    const tags = (payload as { tags?: Record<string, WireTagEntry> } | null)
        ?.tags;
    if (!tags || typeof tags !== "object") return [];
    const out: TagTarget[] = [];
    for (const tag of Object.keys(tags)) {
        if (!tag) continue;
        const entry = tags[tag];
        const count = Number(entry?.count ?? 0);
        if (!Number.isInteger(count) || count < 1) continue;
        if (entry?.space !== RENDER_PIXELS) continue;
        const box = entry?.bounds;
        const bounds =
            box && box.width > 0 && box.height > 0
                ? {
                      x: Math.trunc(box.x),
                      y: Math.trunc(box.y),
                      width: Math.trunc(box.width),
                      height: Math.trunc(box.height),
                  }
                : undefined;
        out.push({ tag, count, bounds, ambiguous: count > 1 });
    }
    // Code-point order, matching how the locator's own maps are ordered, so two pages built from
    // the same index offer the same list in the same sequence.
    return out.sort((a, b) => (a.tag < b.tag ? -1 : a.tag > b.tag ? 1 : 0));
}

/** A rectangle in the DISPLAY plane — CSS pixels, relative to the frame's rendered box. */
export interface DisplayRect {
    x: number;
    y: number;
    width: number;
    height: number;
}

/**
 * A dragged rectangle converted from display pixels into the render's own pixels.
 *
 * This conversion is the whole reason a drag selection is safe to record at all. `v1` accepts
 * `render-pixels` and nothing else — both parsers refuse any other space rather than storing the
 * guess — because a rectangle recorded in the display plane makes an element that never moved
 * report as *moved* the first time someone views the page at a different width. The frame's
 * `naturalWidth / clientWidth` is the exact scale, and it is read from the image on screen rather
 * than from any page state, so a zoom, a responsive reflow and a device-pixel-ratio change are all
 * already accounted for.
 *
 * Rounded outward: `floor` the origin, `ceil` the far edge. A selection that grew by half a pixel
 * still contains what the reporter dragged around, where one that shrank may have clipped the very
 * edge they were pointing at. Null when the frame has not decoded (no natural size to scale by) or
 * when the result has no area — a click with no drag is not a region.
 */
export function toRenderPixels(
    rect: DisplayRect,
    frame: { naturalWidth: number; clientWidth: number },
): Bounds | null {
    if (!frame.naturalWidth || !frame.clientWidth) return null;
    const scale = frame.naturalWidth / frame.clientWidth;
    if (!Number.isFinite(scale) || scale <= 0) return null;
    const x = Math.floor(rect.x * scale);
    const y = Math.floor(rect.y * scale);
    const right = Math.ceil((rect.x + rect.width) * scale);
    const bottom = Math.ceil((rect.y + rect.height) * scale);
    const width = right - x;
    const height = bottom - y;
    if (width < 1 || height < 1) return null;
    return { x, y, width, height };
}
