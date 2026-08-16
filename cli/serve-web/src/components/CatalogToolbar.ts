// `<cp-catalog-toolbar>` — the catalog landing's one toolbar row on a phone.
//
// The landing spends two blocks between its toolbar and its first card on a
// phone: the summary tally, and — on a sectioned catalog — the navigation tree.
// The Theme group and the catalog's actions are menus at every width now (the
// same `.cp-theme-menu` dropdown the component page's Theme control is), so
// `serve.css` has nothing left to fold there; what CSS still cannot do is put
// the filter field on the same row as those menus, because the filter belongs to
// the tree's sidebar — that is where it lives above 960px, beside the grid.
//
// So this moves them, and moves them back. In the DOM rather than with `order`,
// which is the same rule the viewer follows for its own two rows in
// `viewer-drawers.js`: reading order, paint order and tab order stay the same
// order at every width, where a CSS re-order would leave a keyboard walking to
// controls a screenful away from where they appear.
//
// A CONTROLLER element in the `<cp-group-memory>` shape — it renders nothing and
// holds no state of its own, and every element it touches is one the server
// already rendered. It is on the landing page only, which is the only page with
// a toolbar to reflow.
//
// Nothing here is required for the page to work: with the bundle blocked, the
// filter, the chips and the menus are all still on the page in their served
// positions, and the menus still open — they are bare `<details>`, styled by a
// sibling selector, with no script behind them at all.

import { LitElement } from "lit";
import { customElement } from "lit/decorators.js";

const PHONE = "(max-width: 640px)";

/** The toolbar's disclosures — the Theme pill and the `⋯` — which open one at a time. */
const MENUS = ".cp-catalog-theme, .cp-actions-menu";

/** Where an element was before it was moved, so it can be put back exactly. */
interface Home {
    el: Element;
    parent: Node;
    next: Node | null;
}

@customElement("cp-catalog-toolbar")
export class CatalogToolbar extends LitElement {
    private phone: MediaQueryList | null = null;
    private bar: Element | null = null;
    private search: Element | null = null;
    private sub: Element | null = null;
    private homes: Home[] = [];
    /** Whether the rows are currently in the toolbar rather than where the server put them. */
    private moved = false;
    private themeObserver: MutationObserver | null = null;
    private readonly onBreakpoint = () => this.reflow();

    /**
     * A menu closes when it is used.
     *
     * Picking a theme re-renders the grid in place — no navigation, nothing to dismiss the panel —
     * so the menu stayed open over the previews the visitor had just asked to see, for as long as
     * the declared-theme renders took to arrive. The same for Transparent, which is a toggle on the
     * cards behind it. The Theme chips are INSIDE their `<details>` (`.cp-theme-menu-panel`, the
     * viewer's own control), so that one closes the way `viewer-drawers.js` closes it; the actions
     * panel is still its disclosure's sibling, which `closest()` cannot walk, so that one is named
     * here. A link in the actions panel navigates and takes the whole page with it, closed or not.
     *
     * On `document`, not on each panel: the actions panel is moved in and out of the toolbar by
     * the reflow above, and a listener on the thing being moved is a listener that has to be
     * re-bound every time the viewport crosses the breakpoint.
     */
    private readonly onPick = (event: Event) => {
        const target = event.target as Element | null;
        if (!target?.closest) return;
        const themeChip = target.closest(
            ".cp-catalog-theme .cp-theme-btn",
        ) as Element | null;
        if (themeChip) this.close(".cp-catalog-theme");
        if (target.closest(".cp-actions-panel .cp-bg-btn"))
            this.close(".cp-actions-menu");
    };

    /**
     * The toolbar's two menus are one menu bar, so only one of them is ever open.
     *
     * `Theme` and `⋯` are independent `<details>`, and nothing made opening one close the other —
     * so opening `⋯` and then Theme left both open, their absolutely positioned panels overlapping
     * at the same `z-index`, with the actions panel (later in the DOM) painting over the Theme
     * panel's first rows. Measured on a 1280px page: Theme spans x=1060–1200, actions x=1118–1252,
     * and `elementFromPoint` over Theme's first row answered `cp-bg-btn` — the Transparent button,
     * not the theme the visitor was reaching for. The choice underneath was unclickable until the
     * `⋯` was dismissed by hand.
     *
     * Driven by `toggle` rather than by clicks on the summaries, so it holds however the disclosure
     * was opened — pointer, Enter, or a script.
     */
    private readonly onDisclosureToggle = (event: Event) => {
        const opened = event.target as HTMLDetailsElement | null;
        if (!opened?.matches?.(MENUS) || !opened.open) return;
        for (const peer of document.querySelectorAll<HTMLDetailsElement>(MENUS))
            if (peer !== opened) peer.open = false;
    };

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        // The sticky toolbar: it holds the Theme pill and the `⋯` menu, and it is already what
        // sticks. The actions row is nested inside it, so the old fallback to that row — for a
        // catalog with no Theme control, back when the actions stood on their own above the
        // toolbar — no longer names anything the toolbar does not already contain.
        this.bar = document.querySelector(".cp-catalog-tools");
        this.search = document.querySelector(".cp-catalog-menu .cp-searchbar");
        this.sub = document.querySelector(".cp-sub");
        // The actions row is no longer in this list: it is emitted inside the toolbar's
        // `.cp-head-toggles` at every width, beside the Theme pill, so there is nothing to move.
        // The filter is the only control left to bring in, and the summary line does not join the
        // row at all; see `placeOnPhone`.
        this.homes = [this.search, this.sub]
            .filter((el): el is Element => !!el && el !== this.bar)
            .map((el) => ({
                el,
                parent: el.parentNode as Node,
                next: el.nextSibling,
            }));
        this.phone = window.matchMedia?.(PHONE) ?? null;
        this.phone?.addEventListener?.("change", this.onBreakpoint);
        this.reflow();
        this.watchThemeValue();
        document.addEventListener("click", this.onPick);
        // `toggle` does not bubble, so this listens in the CAPTURE phase, which reaches a
        // non-bubbling event on the way down. On `document` rather than on each `<details>` for the
        // same reason `onPick` is: the panels are moved in and out of the toolbar by the reflow.
        document.addEventListener("toggle", this.onDisclosureToggle, true);
    }

    disconnectedCallback(): void {
        this.phone?.removeEventListener?.("change", this.onBreakpoint);
        document.removeEventListener("click", this.onPick);
        document.removeEventListener("toggle", this.onDisclosureToggle, true);
        this.themeObserver?.disconnect();
        this.themeObserver = null;
        super.disconnectedCallback();
    }

    private close(selector: string): void {
        const menu = document.querySelector(selector);
        if (menu instanceof HTMLDetailsElement) menu.open = false;
    }

    private reflow(): void {
        if (!this.bar) return;
        const phone = !!this.phone?.matches;
        // Only on a CROSSING. Re-inserting a node where it already is looks like a no-op and is
        // not one: it detaches and re-attaches the element, and the browser rebuilds what it hangs
        // off the attachment. The filter field is an `<input type="search">`, whose clear button
        // and focus ring are exactly that — so a desktop page that "restored" what it had never
        // moved came back subtly different from one this element never touched, which is how the
        // page capture caught it. Nothing to do until the shape actually changes.
        if (phone === this.moved) return;
        this.moved = phone;
        if (phone) for (const { el } of this.homes) this.placeOnPhone(el);
        else
            for (const { el, parent, next } of this.homes)
                parent.insertBefore(el, next);
    }

    /** Where a moved block goes on a phone. */
    private placeOnPhone(el: Element): void {
        if (!this.bar) return;
        // The summary line — "1194 preview(s) · 186 views · hold a card for a live session" — is a
        // TALLY, not a control: it says what the catalog holds, which is a thing to read once and
        // never again, and on a phone it was the last row standing between the toolbar and the
        // previews it counts. It goes below the grid, beside the download action and the
        // provenance strip, where the rest of the catalog's own metadata already sits. Not
        // hidden: the live-session hint in it is the only place a phone is told a card can be
        // held, and a phone is where that gesture exists.
        if (el === this.sub) {
            const download = document.querySelector(".cp-catalog-download");
            if (download?.parentNode)
                download.parentNode.insertBefore(el, download);
            else document.querySelector(".cp-main")?.appendChild(el);
            return;
        }
        // The filter takes the width, the menus ride at the trailing edge — so it goes in front of
        // the `.cp-head-toggles` group that holds them, and first when the bar has no such group
        // (a catalog with neither a Theme control nor an action to offer).
        const toggles = this.bar.querySelector(".cp-head-toggles");
        this.bar.insertBefore(el, toggles ?? this.bar.firstChild);
    }

    /**
     * Keep the Theme pill naming the theme in force.
     *
     * Folded away, the pill is all that says which theme the grid is on, and the answer is the
     * visitor's: the landing's own script marks a chip pressed from `localStorage` (or from
     * `?theme=`) on load, and again on every click, with no page load in between. Mirroring
     * `aria-pressed` keeps this decoupled from that script, which has no hook of its own.
     */
    private watchThemeValue(): void {
        const bar = document.getElementById("cp-catalog-theme-bar");
        const value = document.getElementById("cp-catalog-theme-value");
        if (!bar || !value) return;
        const sync = () => {
            const pressed = bar.querySelector(
                '.cp-theme-btn[aria-pressed="true"]',
            );
            const name = pressed?.textContent?.trim();
            // Only when it actually changes: the server seeds the pill with the leading built-in,
            // which is the right answer for most visitors, and rewriting the same string is a DOM
            // mutation for nothing.
            if (name && name !== value.textContent) value.textContent = name;
        };
        sync();
        this.themeObserver = new MutationObserver(sync);
        this.themeObserver.observe(bar, {
            subtree: true,
            attributes: true,
            attributeFilter: ["aria-pressed"],
        });
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-catalog-toolbar": CatalogToolbar;
    }
}
