// `<preview-grid>` — typed wrapper around the imperative card grid.
//
// First step of the preview-grid migration. The cards themselves are
// still imperative `<div class="preview-card">` elements created by
// `behavior.ts` (`createCard` / `updateCardMetadata` / `renderPreviews`),
// so this element does NOT manage its own light-DOM children; it just
// surfaces the typed methods that used to be free-standing `grid.X`
// calls in `behavior.ts` and the cross-component reach-in from
// `<compile-errors-banner>`. A future commit can lift `<preview-card>`
// itself, at which point this element will start owning the children
// reactively.
//
// Concretely, this owns:
//
//  - The class list on the host (`preview-grid layout-X`, plus the
//    `compile-stale` modifier).
//  - The DOM walks that used to live in `applyFilters` / `applyLayout`
//    / `getVisibleCards` — exposed as typed methods so callers don't
//    have to repeat the `.preview-card` / `.filtered-out` / `.focused`
//    selectors verbatim.
//
// We intentionally extend `HTMLElement`, not `LitElement`: the host's
// children are imperative and Lit's render cycle would clobber them.
// All state (layout mode, compile-stale flag) is set via explicit
// method calls — no reactive `@state` needed.
//
// Light DOM keeps `media/preview.css` rules targeting `.preview-grid`,
// `.preview-card`, etc. applying unchanged.

import { decideVariantCollapse } from "../variantCollapse";

export type LayoutMode = "grid" | "flow" | "column" | "focus";

export interface FilterValues {
    fn: string;
    group: string;
}

export interface ApplyFiltersOptions {
    /** When true, collapse variants of the same function (same
     *  className+functionName) so only the first card survives. Only takes
     *  effect when neither the function nor the group filter is narrower
     *  than `"all"` — picking a function or group is treated as an
     *  explicit "show all variants for this selection" signal. */
    collapseVariants?: boolean;
}

export class PreviewGrid extends HTMLElement {
    private layoutMode: LayoutMode = "grid";
    private compileStale = false;

    connectedCallback(): void {
        this.applyClasses();
    }

    /** Set the layout mode and update the host class list. */
    setLayoutMode(mode: LayoutMode): void {
        this.layoutMode = mode;
        this.applyClasses();
    }

    getLayoutMode(): LayoutMode {
        return this.layoutMode;
    }

    /** Toggle the `compile-stale` modifier — called by
     *  `<compile-errors-banner>` while the banner is visible. */
    setCompileStale(stale: boolean): void {
        if (this.compileStale === stale) return;
        this.compileStale = stale;
        this.applyClasses();
    }

    /** Cards in DOM order, regardless of filter state. */
    getCards(): HTMLElement[] {
        return Array.from(this.querySelectorAll<HTMLElement>(".preview-card"));
    }

    /** Cards currently visible (i.e. not filtered out). */
    getVisibleCards(): HTMLElement[] {
        return this.getCards().filter(
            (c) => !c.classList.contains("filtered-out"),
        );
    }

    /** Find a card by its `data-preview-id`. */
    findCard(previewId: string): HTMLElement | null {
        return this.querySelector<HTMLElement>(
            `.preview-card[data-preview-id="${cssEscape(previewId)}"]`,
        );
    }

    /**
     * DOM walk that used to live in `applyFilters`. Toggles the
     * `filtered-out` class on each card based on the function/group
     * picks and returns how many ended up visible — callers use that
     * count to decide whether to surface the "no matches" banner.
     *
     * When `options.collapseVariants` is true and both filters are
     * `"all"`, variants of the same function (matched on
     * `data-class-name + data-function`) collapse to the first card —
     * the rest pick up `filtered-out` (and an extra
     * `collapsed-variant` class so callers can tell the two apart).
     */
    applyFilters(
        filters: FilterValues,
        options: ApplyFiltersOptions = {},
    ): number {
        const { fn, group } = filters;
        const cards = this.getCards();
        const filterEligible: HTMLElement[] = [];
        const matches = new Map<HTMLElement, boolean>();
        for (const card of cards) {
            const cardFn = card.dataset.function ?? "";
            const cardGroup = card.dataset.group ?? "";
            const show =
                (fn === "all" || cardFn === fn) &&
                (group === "all" || cardGroup === group);
            matches.set(card, show);
            if (show) filterEligible.push(card);
        }
        const decision = decideVariantCollapse({
            collapseVariants: !!options.collapseVariants,
            fnFilter: fn,
            groupFilter: group,
            candidates: filterEligible.map((c) => ({
                id: c.dataset.previewId ?? "",
                functionKey:
                    (c.dataset.className ?? "") +
                    "::" +
                    (c.dataset.function ?? ""),
            })),
        });
        let visibleCount = 0;
        for (const card of cards) {
            const passes = matches.get(card) ?? false;
            const collapsed =
                passes &&
                decision.active &&
                decision.hidden.has(card.dataset.previewId ?? "");
            const show = passes && !collapsed;
            card.classList.toggle("filtered-out", !show);
            card.classList.toggle("collapsed-variant", collapsed);
            if (show) visibleCount++;
        }
        return visibleCount;
    }

    /**
     * Mark a single visible card as focused and hide the others —
     * the focus-mode class management formerly inlined in
     * `applyLayout`. Pass `null` to clear all focus classes (used
     * when leaving focus mode or when the visible set is empty).
     */
    applyFocusVisibility(focused: HTMLElement | null): void {
        const visible = this.getVisibleCards();
        if (focused === null) {
            for (const card of this.getCards()) {
                card.classList.remove("focused", "hidden-by-focus");
            }
            return;
        }
        for (const card of this.getCards()) {
            card.classList.remove("focused");
        }
        for (const card of visible) {
            card.classList.toggle("hidden-by-focus", card !== focused);
        }
        focused.classList.add("focused");
    }

    private applyClasses(): void {
        const tokens = ["preview-grid", `layout-${this.layoutMode}`];
        if (this.compileStale) tokens.push("compile-stale");
        this.className = tokens.join(" ");
    }
}

// CSS.escape isn't universally typed in older lib targets and isn't
// available in the Node/test environment some webview tests run in.
// previewIds in this codebase come from the discovery pipeline and use
// a stable `[A-Za-z0-9._$-]` alphabet, but we still escape defensively
// because they're used as attribute selectors in `findCard`.
function cssEscape(value: string): string {
    return value.replace(/(["\\])/g, "\\$1");
}

customElements.define("preview-grid", PreviewGrid);
