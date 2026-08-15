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

/** A sectioned catalog: the filter lives in the tree's sidebar, not the toolbar. */
const SECTIONED = `
  <div class="cp-catalog-actions">
    <details class="cp-actions-menu"><summary>⋯</summary></details>
    <div class="cp-actions-panel"><a class="cp-action-chip" href="/compare">compare SVG</a></div>
  </div>
  <div class="cp-catalog-tools">
    <div class="cp-toolbar">
      <details class="cp-catalog-theme">
        <summary><span class="cp-toggle-value" id="cp-catalog-theme-value">Light</span></summary>
      </details>
      <span class="cp-theme" id="cp-catalog-theme-bar">
        <button class="cp-theme-btn" data-theme-choice="light" aria-pressed="true">Light</button>
        <button class="cp-theme-btn" data-theme-choice="dark" aria-pressed="false">Dark</button>
      </span>
    </div>
  </div>
  <div class="cp-catalog-body">
    <aside class="cp-catalog-menu">
      <div class="cp-searchbar"><input class="cp-search"></div>
      <nav class="cp-tree"></nav>
    </aside>
    <div class="cp-grid"></div>
  </div>`;

/** A catalog with one theme: no Theme control, so no `.cp-catalog-tools` at all. */
const NO_THEME = `
  <div class="cp-catalog-actions">
    <details class="cp-actions-menu"><summary>⋯</summary></details>
    <div class="cp-actions-panel"><a class="cp-action-chip" href="/compare">compare SVG</a></div>
  </div>
  <div class="cp-catalog-body">
    <aside class="cp-catalog-menu">
      <div class="cp-searchbar"><input class="cp-search"></div>
    </aside>
    <div class="cp-grid"></div>
  </div>`;

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

    it("puts the filter and the actions in the toolbar on a phone", async () => {
        stubBreakpoint(true);
        await mount(SECTIONED);
        assert.deepEqual(row(), [
            "cp-toolbar",
            "cp-searchbar",
            "cp-catalog-actions",
        ]);
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
        assert.deepEqual(row(), ["cp-toolbar"]);
        assert.ok(
            document.querySelector(".cp-catalog-menu > .cp-searchbar"),
            "the filter stays in the tree's sidebar",
        );
        assert.ok(
            document.querySelector(".cp-catalog-body")!.previousElementSibling
                ?.previousElementSibling?.className === "cp-catalog-actions",
            "the actions stay above the toolbar",
        );
    });

    it("puts both back exactly where the server had them when the window widens", async () => {
        const set = stubBreakpoint(true);
        await mount(SECTIONED);
        set(false);
        assert.deepEqual(row(), ["cp-toolbar"]);
        // Position, not merely parent: the filter is the sidebar's FIRST child, above the tree.
        const sidebar = document.querySelector(".cp-catalog-menu")!;
        assert.equal(sidebar.children[0].className, "cp-searchbar");
        assert.equal(sidebar.children[1].className, "cp-tree");
    });

    it("uses the actions row as the bar when the catalog has no Theme control", async () => {
        stubBreakpoint(true);
        await mount(NO_THEME);
        // The filter leads, because there is no Theme pill for it to follow.
        assert.deepEqual(row(".cp-catalog-actions"), [
            "cp-searchbar",
            "cp-actions-menu",
            "cp-actions-panel",
        ]);
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

    it("does nothing on a page with no toolbar and no actions", async () => {
        stubBreakpoint(true);
        await mount(`<div class="cp-grid"></div>`);
        assert.ok(document.querySelector(".cp-grid"));
    });
});
