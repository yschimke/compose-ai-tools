// Behavioural contract for `<cp-catalog-toolbar>`.
//
// The claim under test is a layout one — that a phone's toolbar is one row —
// but the part that can silently rot is the DOM move: which elements go into
// the bar, in what order, and whether widening the window puts them back
// exactly where the server had them. A pixel baseline catches the phone shape;
// nothing but this catches the restore, because the harness shoots the wide
// shape from a page that was never narrow.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/CatalogToolbar.js";

/** The catalog's actions, as the server nests them in the toggle group. */
const ACTIONS = `
    <div class="cp-catalog-actions">
      <details class="cp-actions-menu"><summary>⋯</summary></details>
      <div class="cp-actions-panel"><a class="cp-action-chip" href="/compare">compare SVG</a><button class="cp-bg-btn">Transparent</button></div>
    </div>`;

/** The Theme pill, as the server nests it beside them. */
const THEME = `
    <div class="cp-toolbar">
      <details class="cp-theme-menu cp-catalog-theme">
        <summary><span class="cp-toggle-value" id="cp-catalog-theme-value">Light</span></summary>
        <div class="cp-theme-menu-panel">
          <span class="cp-theme cp-theme-bar" id="cp-catalog-theme-bar">
            <button class="cp-theme-btn" data-theme-choice="light" aria-pressed="true">Light</button>
            <button class="cp-theme-btn" data-theme-choice="dark" aria-pressed="false">Dark</button>
          </span>
        </div>
      </details>
    </div>`;

/**
 * A sectioned catalog: the filter lives in the tree's sidebar, so the server emits NO toolbar row
 * and its pills ride at the trailing edge of the identity row (issue #4224).
 */
const SECTIONED = `
  <div class="cp-catalog-head-row">
    <div class="cp-catalog-title"><h1 class="cp-head cp-catalog-head">Kit</h1></div>
    <p class="cp-sub">9 preview(s)</p>
    <div class="cp-head-toggles">${THEME}${ACTIONS}
    </div>
  </div>
  <div class="cp-catalog-body">
    <aside class="cp-catalog-menu">
      <div class="cp-searchbar"><input class="cp-search"></div>
      <nav class="cp-tree"></nav>
    </aside>
    <div class="cp-grid"></div>
  </div>
  <div class="cp-catalog-download"><a class="cp-action-chip" href="/bundle.zip">download all (.zip)</a></div>`;

/** The same, for a catalog with one theme: no Theme control, so the `⋯` stands alone. */
const NO_THEME = `
  <div class="cp-catalog-head-row">
    <div class="cp-catalog-title"><h1 class="cp-head cp-catalog-head">Kit</h1></div>
    <div class="cp-head-toggles">${ACTIONS}
    </div>
  </div>
  <div class="cp-catalog-body">
    <aside class="cp-catalog-menu">
      <div class="cp-searchbar"><input class="cp-search"></div>
    </aside>
    <div class="cp-grid"></div>
  </div>`;

/** A flat catalog: the filter is IN the toolbar, so the pills stay there with it. */
const FLAT = `
  <div class="cp-catalog-head-row">
    <div class="cp-catalog-title"><h1 class="cp-head cp-catalog-head">Kit</h1></div>
    <p class="cp-sub">6 preview(s)</p>
  </div>
  <div class="cp-catalog-tools">
    <div class="cp-searchbar"><input class="cp-search"></div>
    <div class="cp-head-toggles">${THEME}${ACTIONS}
    </div>
  </div>
  <div class="cp-grid"></div>
  <div class="cp-catalog-download"><a class="cp-action-chip" href="/bundle.zip">download all (.zip)</a></div>`;

/**
 * A controllable `(max-width: 640px)`. happy-dom has no layout, so the real query would answer
 * for a viewport that does not exist; the component only ever asks "phone?" and "tell me when
 * that changes", which is exactly what this hands it.
 */
function stubBreakpoint(matches: boolean): (next: boolean) => void {
    const listeners = new Set<() => void>();
    const query = {
        matches,
        addEventListener: (_: string, fn: () => void) => void listeners.add(fn),
        removeEventListener: (_: string, fn: () => void) =>
            void listeners.delete(fn),
    };
    Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: () => query,
    });
    return (next: boolean) => {
        query.matches = next;
        for (const fn of listeners) fn();
    };
}

async function mount(markup: string): Promise<void> {
    document.body.innerHTML = `${markup}<cp-catalog-toolbar></cp-catalog-toolbar>`;
    await flush();
}

/** The classes of the toolbar's children, in order — the row as it reads. */
function row(selector = ".cp-catalog-tools"): string[] {
    return Array.from(document.querySelector(selector)!.children).map(
        (el) => el.className.split(" ")[0],
    );
}

describe("<cp-catalog-toolbar>", () => {
    afterEach(() => resetDom());

    it("builds the phone's one row: the filter, then the menus", async () => {
        // The server emits no toolbar for a sectioned catalog — its row would carry the pills and
        // nothing else, which is the empty band of issue #4224 — so the phone row is built here,
        // and it reads exactly as the served one did: filter first, menus at the trailing edge.
        stubBreakpoint(true);
        await mount(SECTIONED);
        assert.deepEqual(row(), ["cp-searchbar", "cp-head-toggles"]);
        // Directly under the identity row, where the served toolbar stood.
        const bar = document.querySelector(".cp-catalog-tools")!;
        assert.equal(
            bar.previousElementSibling?.className,
            "cp-catalog-head-row",
        );
    });

    it("leaves a served toolbar to carry its own filter and menus", async () => {
        // A flat catalog's filter IS in the toolbar, so that row is a toolbar rather than a band of
        // pills — nothing is built and nothing moves into it.
        stubBreakpoint(true);
        await mount(FLAT);
        assert.deepEqual(row(), ["cp-searchbar", "cp-head-toggles"]);
        assert.equal(
            document.querySelectorAll(".cp-catalog-tools").length,
            1,
            "no second row is built for one the server already emitted",
        );
    });

    it("does not touch the DOM at all above the breakpoint", async () => {
        // Not merely "ends up in the right place": re-inserting a node where it already is
        // detaches and re-attaches it, and the browser rebuilds what hangs off the attachment —
        // for the filter field, an `<input type="search">`, that is its clear button and its focus
        // ring. A desktop page came back subtly different from one this element never touched.
        stubBreakpoint(false);
        document.body.innerHTML = SECTIONED;
        const records: MutationRecord[] = [];
        const observer = new MutationObserver((rs) => records.push(...rs));
        observer.observe(document.body, { childList: true, subtree: true });
        document.body.insertAdjacentHTML(
            "beforeend",
            "<cp-catalog-toolbar></cp-catalog-toolbar>",
        );
        await flush();
        observer.disconnect();
        // ELEMENT removals: the pill's value is a text node this element may legitimately rewrite,
        // and a text write disturbs nothing.
        const moves = records.filter((r) =>
            Array.from(r.removedNodes).some((n) => n.nodeType === 1),
        );
        assert.equal(moves.length, 0, "nothing is moved at desktop width");
    });

    it("leaves the served layout alone above the breakpoint", async () => {
        stubBreakpoint(false);
        await mount(SECTIONED);
        assert.equal(
            document.querySelector(".cp-catalog-tools"),
            null,
            "no row is built at a width that never asked for one",
        );
        assert.ok(
            document.querySelector(".cp-catalog-menu > .cp-searchbar"),
            "the filter stays in the tree's sidebar",
        );
        assert.ok(
            document.querySelector(".cp-catalog-head-row .cp-catalog-actions"),
            "and the actions stay on the identity row",
        );
    });

    it("puts everything back exactly where the server had it when the window widens", async () => {
        const set = stubBreakpoint(true);
        await mount(SECTIONED);
        set(false);
        // The built row goes with them: left behind, it would be the empty band again.
        assert.equal(document.querySelector(".cp-catalog-tools"), null);
        // Position, not merely parent: the filter is the sidebar's FIRST child, above the tree.
        const sidebar = document.querySelector(".cp-catalog-menu")!;
        assert.equal(sidebar.children[0].className, "cp-searchbar");
        assert.equal(sidebar.children[1].className, "cp-tree");
        // …and the pills are back at the trailing edge of the identity row, after the tally.
        assert.deepEqual(row(".cp-catalog-head-row"), [
            "cp-catalog-title",
            "cp-sub",
            "cp-head-toggles",
        ]);
    });

    it("still builds the row when the catalog has no Theme control", async () => {
        stubBreakpoint(true);
        await mount(NO_THEME);
        // The `⋯` menu is the row's only pill here, and the filter still leads it.
        assert.deepEqual(row(), ["cp-searchbar", "cp-head-toggles"]);
    });

    it("names the theme in force on the folded pill, and follows it", async () => {
        stubBreakpoint(true);
        await mount(SECTIONED);
        const value = document.getElementById("cp-catalog-theme-value")!;
        assert.equal(value.textContent, "Light");

        // What the landing's own script does when the visitor picks a chip.
        const bar = document.getElementById("cp-catalog-theme-bar")!;
        bar.querySelector('[data-theme-choice="light"]')!.setAttribute(
            "aria-pressed",
            "false",
        );
        bar.querySelector('[data-theme-choice="dark"]')!.setAttribute(
            "aria-pressed",
            "true",
        );
        await flush();
        assert.equal(value.textContent, "Dark");
    });

    it("moves the summary line below the grid, not into the row", async () => {
        // It is a tally, not a control: on a phone it was the last row between the toolbar and the
        // previews it counts, so it goes down with the catalog's other metadata.
        stubBreakpoint(true);
        await mount(SECTIONED);
        const main = document.body;
        const sub = document.querySelector(".cp-sub")!;
        assert.equal(
            sub.nextElementSibling?.className,
            "cp-catalog-download",
            "the summary sits just above the download action",
        );
        assert.ok(
            main
                .querySelector(".cp-catalog-body")!
                .compareDocumentPosition(sub) &
                Node.DOCUMENT_POSITION_FOLLOWING,
            "and below the grid",
        );
    });

    it("closes the Theme menu once a theme is picked", async () => {
        // Picking re-renders the grid in place, so nothing else would dismiss the panel — it sat
        // over the previews the visitor had just asked to look at.
        stubBreakpoint(true);
        await mount(SECTIONED);
        const menu = document.querySelector(
            ".cp-catalog-theme",
        ) as HTMLDetailsElement;
        menu.open = true;
        (
            document.querySelector(
                '.cp-theme-menu-panel .cp-theme-btn[data-theme-choice="dark"]',
            ) as HTMLElement
        ).click();
        assert.equal(menu.open, false);
    });

    it("closes the actions menu when Transparent is toggled", async () => {
        // The links in that panel navigate; Transparent is a toggle on the cards behind it.
        stubBreakpoint(true);
        await mount(SECTIONED);
        const menu = document.querySelector(
            ".cp-actions-menu",
        ) as HTMLDetailsElement;
        menu.open = true;
        (
            document.querySelector(
                ".cp-actions-panel .cp-bg-btn",
            ) as HTMLElement
        ).click();
        assert.equal(menu.open, false);
    });

    it("opens one toolbar menu at a time", async () => {
        // Two independent `<details>` whose panels are absolutely positioned at the same z-index:
        // with both open the later actions panel paints over the Theme panel's first rows, and the
        // theme underneath cannot be clicked. Measured on the real page at 1280px before the fix —
        // Theme x=1060–1200, actions x=1118–1252, `elementFromPoint` over Theme's first row
        // answering `cp-bg-btn`.
        stubBreakpoint(false);
        await mount(SECTIONED);
        const theme = document.querySelector(
            ".cp-catalog-theme",
        ) as HTMLDetailsElement;
        const actions = document.querySelector(
            ".cp-actions-menu",
        ) as HTMLDetailsElement;

        actions.open = true;
        actions.dispatchEvent(new Event("toggle"));
        theme.open = true;
        theme.dispatchEvent(new Event("toggle"));
        assert.equal(actions.open, false, "opening Theme puts the ⋯ away");
        assert.equal(theme.open, true);

        // …and the other way round: neither is the privileged one.
        actions.open = true;
        actions.dispatchEvent(new Event("toggle"));
        assert.equal(theme.open, false, "opening the ⋯ puts Theme away");
        assert.equal(actions.open, true);
    });

    it("does nothing on a page with no toolbar and no actions", async () => {
        stubBreakpoint(true);
        await mount(`<div class="cp-grid"></div>`);
        assert.ok(document.querySelector(".cp-grid"));
    });
});
