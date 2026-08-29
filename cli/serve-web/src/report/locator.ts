// Writing a selection into the `compose-parity-locator/v1` block.
//
// The Kotlin writer (`ServeIssueReport`) emits the block; this fills the one part of it that is not
// known until somebody clicks. Both sides therefore write the same two fields, and they have to
// agree byte for byte — the block is canonical bytes so two records are comparable without parsing
// one, and the producer that indexes reports refuses a non-canonical field rather than storing a
// guess. `test/reportLocator.test.ts` asserts this module against the SHARED fixture the Kotlin
// writer and the JavaScript parser are already pinned to, so no engine can move the contract alone.
//
// The invariants are enforced here, at the point the rectangle is MADE, exactly as
// `ServeIssueReport.Bounds` enforces them in its constructor. A writer that emits a rectangle its
// own producer refuses hands the reporter a prefilled body that looks right and takes the whole
// issue out of the index when the workflow next runs — a failure with no symptom until someone
// wonders why the report never appeared.

/** The only plane `v1` accepts. See D1: both tag-index producers publish render pixels. */
export const RENDER_PIXELS = "render-pixels";

/** A selected region, in the render's own pixel space. */
export interface Bounds {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** What the page has chosen: a tagged element, a dragged region, or both. */
export interface Selection {
    /** The `testTag` verbatim — no trimming anywhere in the chain. */
    element?: string;
    bounds?: Bounds;
}

/**
 * `element` as a **JSON string**, which is what keeps a tag from becoming syntax.
 *
 * The block is line-oriented `key: value` and a `testTag` is arbitrary text: one containing a
 * newline would not stay one field (`row\nrevision: injected` reads back as an element plus a
 * revision nobody wrote) and one carrying a fence delimiter could end the block early and drop the
 * whole issue from the index. Quoting also makes a tag with leading or trailing whitespace
 * expressible, which a format whose readers trim otherwise cannot carry at all.
 */
export function canonicalElement(element: string): string {
    return JSON.stringify(element);
}

/**
 * Canonical bounds JSON: the same code-point key order the overrides carry, so a block is
 * comparable byte for byte without parsing it back. Code point order is height < space < width < x
 * < y, and it is written out rather than sorted because there are five of them and they are fixed.
 */
export function canonicalBounds(bounds: Bounds): string {
    return (
        `{"height":${bounds.height},"space":"${RENDER_PIXELS}",` +
        `"width":${bounds.width},"x":${bounds.x},"y":${bounds.y}}`
    );
}

/**
 * Whether a rectangle is one `v1` will accept.
 *
 * The origin may be **negative**, deliberately: a uniquely tagged node can extend above or left of
 * the render root, and both tag-index producers emit signed coordinates for that case. Requiring a
 * non-negative origin here would mean a selection could not copy the bounds the index handed it.
 * Clipping is the comparison's plane transform's business.
 *
 * The extent must be positive and every coordinate an integer. A drag selection starts in DISPLAY
 * pixels and is converted before it gets here; a conversion that produced a sub-pixel or an empty
 * rectangle is a bug at the point of construction, and this is where it stops.
 */
export function usableBounds(bounds: Bounds | undefined): bounds is Bounds {
    if (!bounds) return false;
    const all = [bounds.x, bounds.y, bounds.width, bounds.height];
    if (!all.every((n) => Number.isInteger(n))) return false;
    return bounds.width >= 1 && bounds.height >= 1;
}

/**
 * The `element:` / `bounds:` lines a selection contributes, each newline-terminated.
 *
 * Empty for no selection, which is what makes the substitution below reproduce the block the server
 * would have written on its own. An unusable rectangle contributes nothing rather than an invalid
 * line — a report that names its element and no region is a real, useful report; one carrying a
 * rectangle the producer refuses is not a report at all.
 */
export function selectionLines(selection: Selection): string {
    let out = "";
    // Only an EMPTY tag is dropped. `"item"` and `" item "` are different identities to a tag index
    // and normalising here would point the acceptance at the wrong one — or at none.
    if (selection.element)
        out += `element: ${canonicalElement(selection.element)}\n`;
    if (usableBounds(selection.bounds))
        out += `bounds: ${canonicalBounds(selection.bounds)}\n`;
    return out;
}

/** The placeholder the server leaves in the template's locator block for the lines above. */
export const SELECTION_PLACEHOLDER = "{{selection}}";

/**
 * [template] with the selection placeholder replaced by [selectionLines].
 *
 * The placeholder occupies a whole LINE, and this matches it as one — the line has to equal the
 * placeholder exactly, which is how the server writes it. A first-occurrence substring replace was
 * the obvious spelling and the wrong one: any earlier locator value ending in the placeholder text
 * (a preview id or a variant carrying it, both catalog-authored and so third-party data) would be
 * rewritten instead, and the real placeholder would then be filed verbatim — a malformed locator
 * that takes the whole issue out of the parity index, with nothing to notice it.
 *
 * The line is consumed WITH its newline: substituting the empty string then yields exactly the
 * block a server with no selection to report writes by itself, where a stray blank line inside the
 * fence is one the producer's line parser reads a field short.
 */
export function fillSelection(template: string, selection: Selection): string {
    const lines = template.split("\n");
    const at = lines.indexOf(SELECTION_PLACEHOLDER);
    if (at < 0) return template;
    const filled = selectionLines(selection);
    // `selectionLines` is newline-TERMINATED, so splice in its lines and drop the trailing empty
    // piece; an empty selection splices nothing and removes the placeholder line entirely.
    lines.splice(at, 1, ...(filled ? filled.split("\n").slice(0, -1) : []));
    return lines.join("\n");
}

// ---- the whole block, for a report the server could not write ---------------------------------
//
// Everything above fills in a block `ServeIssueReport` already wrote. What follows writes one from
// nothing, and exists for a single caller: the comparison wall's multi-row picker, where which
// comparisons the report names is decided by ticking rows after the page was served. The server
// cannot write those blocks and the browser therefore has to — so this is a third engine on
// `compose-parity-locator/v1`, pinned to the same shared fixture as the Kotlin writer and the
// JavaScript producer (`test/reportLocator.test.ts`).

/** The fence both delimiters carry. Kept in step with `ServeIssueReport.LOCATOR_FENCE`. */
export const LOCATOR_FENCE = "compose-parity-locator/v1";

/** The placeholder line a pickable page's template carries. `ServeIssueReport.LOCATORS_PLACEHOLDER`. */
export const LOCATORS_PLACEHOLDER = "{{locators}}";

/** One comparison, as the block names it. */
export interface Locator {
    repository: string;
    system: string;
    componentId: string;
    previewId: string;
    referenceId: string;
    variant: string;
    overrides?: Record<string, string>;
    revision?: string | null;
    element?: string;
    bounds?: Bounds;
}

/**
 * Compare two strings by CODE POINT, which is not what `Array.prototype.sort` does.
 *
 * The default comparator orders by UTF-16 code unit, so an astral key (`😀`, a surrogate pair
 * starting at U+D83D) sorts *before* a BMP key above U+D800 (`！`, U+FF01) — the opposite of the
 * canonical order, which the Kotlin writer produces with an explicit code-point comparator. Two
 * writers disagreeing about that produce two byte-different blocks for one comparison, which is
 * exactly what "canonical" exists to stop. The shared fixture's `astral-override-keys` case is this
 * function's reason to exist and the thing that catches its absence.
 */
function compareCodePoints(a: string, b: string): number {
    const left = [...a];
    const right = [...b];
    const shared = Math.min(left.length, right.length);
    for (let i = 0; i < shared; i++) {
        const one = left[i].codePointAt(0) ?? 0;
        const two = right[i].codePointAt(0) ?? 0;
        if (one !== two) return one - two;
    }
    return left.length - right.length;
}

/** Overrides as canonical JSON: code-point key order, and `{}` when there are none. */
export function canonicalOverrides(
    overrides: Record<string, string> = {},
): string {
    const keys = Object.keys(overrides).sort(compareCodePoints);
    return `{${keys
        .map(
            (key) => `${JSON.stringify(key)}:${JSON.stringify(overrides[key])}`,
        )
        .join(",")}}`;
}

/**
 * The block for one comparison, newline-terminated, byte for byte what
 * `ServeIssueReport.locatorBlock` writes for the same fields.
 *
 * Field ORDER is part of the format, not a style: the block is compared without being parsed, so a
 * reordered one is a different record for the same comparison.
 */
export function locatorBlock(locator: Locator): string {
    let out = "```" + LOCATOR_FENCE + "\n";
    out += `repository: ${locator.repository}\n`;
    out += `system: ${locator.system}\n`;
    out += `component: ${locator.componentId}\n`;
    out += `preview: ${locator.previewId}\n`;
    out += `reference: ${locator.referenceId}\n`;
    out += `variant: ${locator.variant}\n`;
    out += `overrides: ${canonicalOverrides(locator.overrides)}\n`;
    out += selectionLines({
        element: locator.element,
        bounds: locator.bounds,
    });
    if (locator.revision) out += `revision: ${locator.revision}\n`;
    out += "```\n";
    return out;
}

/**
 * The axis segments a preview id already encodes, which is the `variant:` line.
 *
 * Ported from `ServeIssueReport.variantFor`, and deliberately the same one-liner: the value is the
 * preview id's own tail with its separator swapped, so there is nothing here to get subtly right
 * beyond not inventing a second rule for it.
 */
export function variantOf(previewId: string): string {
    const at = previewId.indexOf("__");
    return at < 0
        ? ""
        : previewId
              .slice(at + 2)
              .split("__")
              .join("/");
}

/**
 * [template] with its `{{locators}}` line replaced by [blocks], or removed when there are none.
 *
 * Whole-line matching and whole-line consumption, exactly as [fillSelection] does it and for the
 * same reason: the line has to equal the placeholder, so a locator value that merely ends with the
 * placeholder text cannot be rewritten instead. Removing the line reproduces byte for byte the body
 * a server with nothing to fill writes on its own, which is what a report filed with no rows ticked
 * must be.
 */
export function fillLocators(template: string, blocks: string[]): string {
    const lines = template.split("\n");
    const at = lines.indexOf(LOCATORS_PLACEHOLDER);
    if (at < 0) return template;
    // A blank line ahead of the first fence, written HERE rather than by the server: the server's
    // own line has no separator before it, so that a body with nothing ticked is the body it writes
    // without this placeholder at all.
    const filled = blocks.length ? "\n" + blocks.join("") : "";
    lines.splice(at, 1, ...(filled ? filled.split("\n").slice(0, -1) : []));
    return lines.join("\n");
}
