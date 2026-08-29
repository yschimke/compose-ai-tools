// Turning a ticked row on the comparison wall into a `compose-parity-locator/v1` block.
//
// The wall's own report is page-scoped — it names the page and the lane, because a wall singles out
// no preview — and that is the right report for "this whole lane is scoring zero". It is the wrong
// one for the reader who has just noticed four components drifting the same way and wants to say so
// once: filed page-scoped, that issue carries no identity, so the catalog's index skips it and it
// never appears in the Bugs column of any of the four rows it is about.
//
// An umbrella issue naming several components is a shape the format already has. This is the part
// that decides what one ticked row contributes to it.

import { locatorBlock, variantOf, type Locator } from "../report/locator.js";

/** The page-level halves of a locator, which no row can know. */
export interface PickFacts {
    /** `owner/name` the issue is filed against; the locator's `repository:` line. */
    repository: string;
    /** The served design system id. */
    system: string;
    /** Delivery provenance as `owner/repo@branch`, or null for a session that has none. */
    revision: string | null;
}

/**
 * The locator a row contributes, read off the focused comparison it links to.
 *
 * The pair is taken from that href rather than from attributes of its own, and deliberately: it is
 * the URL the wall already re-points at whichever pair the row is SHOWING, so a report filed from
 * the dark lane names the dark preview and the dark reference without a second thing to keep in
 * step. Null when the href names no pair — the lane cannot compare this row, and a locator missing
 * either id is one the producer refuses.
 */
export function locatorForRow(
    detailHref: string,
    base: string,
    componentId: string,
    facts: PickFacts,
): Locator | null {
    if (!detailHref || !componentId) return null;
    let url: URL;
    try {
        url = new URL(detailHref, base);
    } catch {
        return null;
    }
    const previewId = decodeURIComponent(
        url.pathname.slice(url.pathname.lastIndexOf("/") + 1),
    );
    const referenceId = url.searchParams.get("reference") ?? "";
    if (!previewId || !referenceId) return null;
    return {
        repository: facts.repository,
        system: facts.system,
        componentId,
        previewId,
        referenceId,
        variant: variantOf(previewId),
        // Empty, and not a guess: the wall renders every row at the catalog's own defaults, and a
        // locator claiming overrides nobody set would point an acceptance at a frame that was never
        // on screen.
        overrides: {},
        revision: facts.revision,
    };
}

/**
 * The blocks a set of picked rows contributes, in the order they were picked.
 *
 * **One block per component, enforced here as well as in the UI.** The producer refuses a body
 * whose blocks name a component, a preview or a reference twice — and refuses the *whole body*, so
 * a report naming two variants of one component is not partly indexed, it is dropped entirely with
 * nothing to notice. The wall stops that at the tick by disabling the second variant's checkbox;
 * this is the same rule at the point the body is written, because a rule enforced only in the UI is
 * one a re-render can lose.
 */
export function locatorBlocks(locators: Array<Locator | null>): string[] {
    const components = new Set<string>();
    const previews = new Set<string>();
    const references = new Set<string>();
    const blocks: string[] = [];
    for (const locator of locators) {
        if (!locator) continue;
        if (
            components.has(locator.componentId) ||
            previews.has(locator.previewId) ||
            references.has(locator.referenceId)
        )
            continue;
        components.add(locator.componentId);
        previews.add(locator.previewId);
        references.add(locator.referenceId);
        blocks.push(locatorBlock(locator));
    }
    return blocks;
}
