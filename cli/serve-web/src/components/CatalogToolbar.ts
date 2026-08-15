// `<cp-catalog-toolbar>` — the catalog landing's one toolbar row on a phone.
//
// The landing spends four blocks between its heading and its first card: the
// catalog's actions, the Theme group, the filter field, and — on a sectioned
// catalog — the navigation tree. `serve.css` folds the two chip groups into
// menus and turns the tree into a scrolling strip; what CSS cannot do is put the
// filter field on the same row as those menus, because the filter belongs to the
// tree's sidebar (that is where it lives above 960px, beside the grid) and the
// actions are a block of their own above the toolbar.
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
    private actions: Element | null = null;
    private search: Element | null = null;
    private homes: Home[] = [];
    /** Whether the rows are currently in the toolbar rather than where the server put them. */
    private moved = false;
    private themeObserver: MutationObserver | null = null;
    private readonly onBreakpoint = () => this.reflow();

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        // The sticky toolbar when the catalog has one — it holds the Theme group and it is already
        // what sticks — and the actions row otherwise: a catalog with a single theme publishes no
        // Theme control at all, so there the actions row IS the only bar there is.
        this.bar =
            document.querySelector(".cp-catalog-tools") ??
            document.querySelector(".cp-catalog-actions");
        this.actions = document.querySelector(".cp-catalog-actions");
        this.search = document.querySelector(".cp-catalog-menu .cp-searchbar");
        // Ordered as the row should read — the filter is the wide one, the menus bracket it —
        // rather than as the markup happens to run.
        this.homes = [this.search, this.actions]
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
    }

    disconnectedCallback(): void {
        this.phone?.removeEventListener?.("change", this.onBreakpoint);
        this.themeObserver?.disconnect();
        this.themeObserver = null;
        super.disconnectedCallback();
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
        if (phone) {
            for (const { el } of this.homes) {
                // Appending puts the filter after the Theme pill and before the `⋯`. When the bar
                // IS the actions row there is nothing to append after, so the filter goes first.
                if (el === this.search && this.bar === this.actions)
                    this.bar.insertBefore(el, this.bar.firstChild);
                else this.bar.appendChild(el);
            }
        } else {
            for (const { el, parent, next } of this.homes)
                parent.insertBefore(el, next);
        }
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
