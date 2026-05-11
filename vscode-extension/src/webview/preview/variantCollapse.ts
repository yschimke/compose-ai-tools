// Variant-collapse decision helper. Pure function so it's testable
// without a DOM — see `test/variantCollapse.test.ts`.
//
// When the user hasn't picked a function or group, we want to show one
// representative card per function (className + functionName) instead of
// every variant. Picking a function or group from the filter toolbar
// expands the variants for that selection, so the helper short-circuits
// to "no collapse" whenever either filter is narrower than "all".

export interface VariantCollapseCandidate {
    /** `className + "::" + functionName` — the grouping key. */
    functionKey: string;
    /** Whatever the caller uses to identify this card (a previewId works
     *  fine — only used as the set entry the caller hides). */
    id: string;
}

export interface VariantCollapseDecision {
    /** Should collapse actually apply given current filters? */
    active: boolean;
    /** When `active`, the ids of cards to hide as duplicate variants. The
     *  first occurrence of each `functionKey` survives. Empty otherwise. */
    hidden: Set<string>;
}

export interface VariantCollapseInput {
    collapseVariants: boolean;
    /** Current function filter value — `"all"` means no narrowing. */
    fnFilter: string;
    /** Current group filter value — `"all"` means no narrowing. */
    groupFilter: string;
    /** Candidates in display order. Only entries that pass the filter
     *  should be passed in — the helper assumes its input is already
     *  filter-eligible. */
    candidates: readonly VariantCollapseCandidate[];
}

/**
 * Decide which candidate cards to hide when collapsing variants.
 *
 * Collapse is gated on both filters being `"all"` because the user
 * picking a function or group is an explicit "I want to see all variants
 * of this thing" signal.
 */
export function decideVariantCollapse(
    input: VariantCollapseInput,
): VariantCollapseDecision {
    const active =
        input.collapseVariants &&
        input.fnFilter === "all" &&
        input.groupFilter === "all";
    if (!active) {
        return { active: false, hidden: new Set() };
    }
    const seen = new Set<string>();
    const hidden = new Set<string>();
    for (const c of input.candidates) {
        if (seen.has(c.functionKey)) {
            hidden.add(c.id);
        } else {
            seen.add(c.functionKey);
        }
    }
    return { active: true, hidden };
}
