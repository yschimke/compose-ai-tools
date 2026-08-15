// Behavioural contract for `<cp-viewer-drawers>`.
//
// `assets/viewer-drawers.js` had no test at all, and it is the file behind every drawer regression
// this migration has had to chase: #3893 changed the served default and the only thing that
// noticed was a page capture timing out on a control inside a `display: none` column, weeks later.
// The rules themselves are pinned in `drawerState.test.ts`; these cover the wiring — that the
// classes, `aria-expanded`, the scrim, the stored preference and the phone reflow all move
// together, which is the part a pure function cannot say anything about.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import "../src/components/ViewerDrawers.js";

type Band = "phone" | "middle" | "wide";

/**
 * Stub `matchMedia` for a viewport band; must be installed before the element connects.
 * Returns a handle that can move the page to another band and fire the `change` listeners, the
 * way a real resize across a breakpoint does.
 */
function stubViewport(band: Band): { resizeTo(next: Band): void } {
    let current = band;
    const listeners: Array<() => void> = [];
    Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: (query: string) => ({
            get matches() {
                return query.includes("max-width: 640px")
                    ? current === "phone"
                    : query.includes("min-width: 1100px")
                      ? current === "wide"
                      : false;
            },
            addEventListener: (_: string, fn: () => void) =>
                void listeners.push(fn),
            removeEventListener: (_: string, fn: () => void) => {
                const i = listeners.indexOf(fn);
                if (i >= 0) listeners.splice(i, 1);
            },
        }),
    });
    return {
        resizeTo(next: Band) {
            current = next;
            for (const fn of [...listeners]) fn();
        },
    };
}

const VIEWER = (controlsOpen = false) => `
  <div class="cp-preview-head">
    <h1>Profile card</h1>
    <div class="cp-head-toggles">
      <button type="button" id="cp-controls-toggle" aria-expanded="false">⚙ Overrides</button>
    </div>
  </div>
  <div class="cp-preview-primary">
    <button type="button" id="cp-nav-toggle" aria-expanded="false">☰ Components</button>
  </div>
  <div class="cp-viewer${controlsOpen ? " cp-controls-open" : ""}" data-fold-scope="compose-m3">
    <nav class="cp-nav">
      <button id="cp-nav-close">×</button>
      <input type="search" id="cp-nav-search">
      <div class="cp-nav-current"></div>
      <ul id="cp-nav-list">
        <li><a class="cp-nav-item" data-search="Button · Filled" aria-current="page">Filled</a></li>
        <li><a class="cp-nav-item" data-search="Switch">Switch</a></li>
      </ul>
      <p id="cp-nav-empty" hidden>No components match</p>
    </nav>
    <div class="cp-controls"></div>
  </div>
  <div class="cp-scrim" id="cp-scrim"></div>
  <cp-viewer-drawers></cp-viewer-drawers>`;

let viewport: { resizeTo(next: Band): void };

async function mount(
    band: Band = "middle",
    controlsOpen = false,
): Promise<HTMLElement> {
    viewport = stubViewport(band);
    document.body.innerHTML = VIEWER(controlsOpen);
    await flush();
    return document.querySelector(".cp-viewer") as HTMLElement;
}

const toggle = (id: string) => document.getElementById(id) as HTMLElement;

describe("<cp-viewer-drawers>", () => {
    afterEach(() => resetDom());

    it("keeps the server's open drawer open, and its closed drawer closed", async () => {
        stubStorage();
        assert.equal(
            (await mount("middle", true)).classList.contains(
                "cp-controls-open",
            ),
            true,
        );
        resetDom();
        stubStorage();
        assert.equal(
            (await mount("middle", false)).classList.contains(
                "cp-controls-open",
            ),
            false,
        );
    });

    it("says the nav's closed state out loud rather than leaving it to CSS", async () => {
        stubStorage();
        const viewer = await mount("middle");
        // Absent `cp-nav-open` means OPEN above 1100px, so the class has to be explicit or the
        // toggle is inert at the width where the 240px column costs most.
        assert.equal(viewer.classList.contains("cp-nav-closed"), true);
        assert.equal(
            toggle("cp-nav-toggle").getAttribute("aria-expanded"),
            "false",
        );
    });

    it("opens the nav by default when wide", async () => {
        stubStorage();
        const viewer = await mount("wide");
        assert.equal(viewer.classList.contains("cp-nav-open"), true);
        assert.equal(
            toggle("cp-nav-toggle").getAttribute("aria-expanded"),
            "true",
        );
    });

    it("moves the class, aria-expanded and the stored preference together on a click", async () => {
        const store = stubStorage();
        const viewer = await mount("middle");
        toggle("cp-controls-toggle").click();
        assert.equal(viewer.classList.contains("cp-controls-open"), true);
        assert.equal(
            toggle("cp-controls-toggle").getAttribute("aria-expanded"),
            "true",
        );
        assert.equal(store.get("cp-fold:compose-m3.cp-controls-toggle"), "1");
    });

    it("restores a stored choice over the server default", async () => {
        const store = stubStorage();
        await mount("middle", true);
        toggle("cp-controls-toggle").click(); // close it, and remember that
        assert.equal(store.get("cp-fold:compose-m3.cp-controls-toggle"), "0");
        resetDom();
        const viewer = await mount("middle", true);
        assert.equal(viewer.classList.contains("cp-controls-open"), false);
    });

    it("stores nothing about the drawers on a phone", async () => {
        const store = stubStorage();
        await mount("phone");
        toggle("cp-controls-toggle").click();
        // A sheet is transient: remembering it open would cover the next preview you navigate to.
        assert.equal(store.get("cp-fold:compose-m3.cp-controls-toggle"), null);
    });

    it("closes the other sheet on a phone so they never stack", async () => {
        stubStorage();
        const viewer = await mount("phone");
        toggle("cp-controls-toggle").click();
        assert.equal(viewer.classList.contains("cp-controls-open"), true);
        toggle("cp-nav-toggle").click();
        assert.equal(viewer.classList.contains("cp-nav-open"), true);
        assert.equal(viewer.classList.contains("cp-controls-open"), false);
        assert.equal(
            toggle("cp-controls-toggle").getAttribute("aria-expanded"),
            "false",
            "the displaced sheet's toggle is told too",
        );
    });

    it("leaves both columns open off the phone", async () => {
        stubStorage();
        const viewer = await mount("wide");
        toggle("cp-controls-toggle").click();
        assert.equal(viewer.classList.contains("cp-controls-open"), true);
        assert.equal(viewer.classList.contains("cp-nav-open"), true);
    });

    it("raises the scrim behind an open sheet and drops it when both close", async () => {
        stubStorage();
        await mount("phone");
        const scrim = document.getElementById("cp-scrim") as HTMLElement;
        assert.equal(scrim.classList.contains("cp-scrim-on"), false);
        toggle("cp-nav-toggle").click();
        assert.equal(scrim.classList.contains("cp-scrim-on"), true);
        scrim.click();
        assert.equal(scrim.classList.contains("cp-scrim-on"), false);
    });

    it("dismisses the nav from its own close button", async () => {
        stubStorage();
        const viewer = await mount("wide");
        toggle("cp-nav-close").click();
        assert.equal(viewer.classList.contains("cp-nav-open"), false);
        assert.equal(viewer.classList.contains("cp-nav-closed"), true);
    });

    describe("the phone's row order", () => {
        it("moves the control rows below the stage on a phone", async () => {
            stubStorage();
            const viewer = await mount("phone");
            const order = [...document.body.children].map((el) => el.className);
            const stage = order.indexOf("cp-viewer");
            assert.ok(
                order.indexOf("cp-preview-primary") > stage &&
                    order.indexOf("cp-head-toggles") > stage,
                `rows should follow the stage, got ${order.join(" | ")}`,
            );
        });

        it("does not touch a single node above the breakpoint", async () => {
            stubStorage();
            // The bug this guards is invisible: re-inserting a node into the position it already
            // occupies detaches and re-attaches it, and the browser rebuilds what hangs off the
            // attachment — an <input type="search"> loses its clear button and focus ring. The
            // catalog toolbar shipped exactly that, so "ends up in the right place" is not the
            // assertion; "never moved" is.
            await mount("middle");
            const row = document.querySelector(
                ".cp-preview-primary",
            ) as HTMLElement;
            let moves = 0;
            const observer = new MutationObserver((records) => {
                for (const r of records) {
                    if ([...r.removedNodes].includes(row)) moves++;
                }
            });
            observer.observe(document.body, { childList: true, subtree: true });
            // Re-running the reflow at the same band must be a genuine no-op.
            document.querySelector("cp-viewer-drawers")?.remove();
            document.body.appendChild(
                document.createElement("cp-viewer-drawers"),
            );
            await flush();
            observer.disconnect();
            assert.equal(moves, 0, "the row was detached and re-attached");
        });
    });

    it("mirrors the pressed theme chip into the toggle's value", async () => {
        stubStorage();
        await mount("middle");
        const bar = document.createElement("div");
        bar.id = "cp-theme-bar";
        bar.innerHTML = `
          <button class="cp-theme-btn" aria-pressed="false">Day</button>
          <button class="cp-theme-btn" aria-pressed="true">Night</button>`;
        const value = document.createElement("span");
        value.id = "cp-theme-toggle-value";
        document.body.append(bar, value);
        // Re-connect so the element picks up the markup this test added.
        document.querySelector("cp-viewer-drawers")?.remove();
        document.body.appendChild(document.createElement("cp-viewer-drawers"));
        await flush();
        assert.equal(value.textContent, "Night");
    });

    it("filters the component list, pinning the preview being viewed", async () => {
        stubStorage();
        await mount("middle");
        const search = document.getElementById(
            "cp-nav-search",
        ) as HTMLInputElement;
        search.value = "zzz";
        search.dispatchEvent(new Event("input"));
        const rows = [
            ...document.querySelectorAll<HTMLElement>(
                "#cp-nav-list .cp-nav-item",
            ),
        ].map((el) => (el.parentNode as HTMLElement).hidden);
        assert.deepEqual(
            rows,
            [false, true],
            "aria-current stays, the rest go",
        );
        // `.cp-nav-current` pins the active component, so this is not an empty result.
        assert.equal(
            (document.getElementById("cp-nav-empty") as HTMLElement).hidden,
            true,
        );
    });

    describe("crossing a breakpoint", () => {
        it("drops the nav when a wide window narrows to a phone", async () => {
            stubStorage();
            const viewer = await mount("wide");
            assert.equal(viewer.classList.contains("cp-nav-open"), true);
            // Making the state explicit cost the CSS default its own responsiveness. Without
            // re-resolving, the page keeps `cp-nav-open` — which below 640px is a fixed bottom
            // sheet and a scrim dropped over a viewer nobody asked to cover.
            viewport.resizeTo("phone");
            await flush();
            assert.equal(viewer.classList.contains("cp-nav-open"), false);
        });

        it("brings the nav back when a phone widens again", async () => {
            stubStorage();
            const viewer = await mount("phone");
            viewport.resizeTo("wide");
            await flush();
            assert.equal(viewer.classList.contains("cp-nav-open"), true);
        });

        it("still honours a stored choice across the crossing", async () => {
            const store = stubStorage();
            const viewer = await mount("wide");
            toggle("cp-nav-toggle").click(); // close it, off the phone, so it is remembered
            assert.equal(store.get("cp-fold:compose-m3.cp-nav-toggle"), "0");
            viewport.resizeTo("middle");
            await flush();
            assert.equal(
                viewer.classList.contains("cp-nav-open"),
                false,
                "a stored close survives the resize",
            );
        });

        it("returns the rows to their exact homes when a phone widens", async () => {
            stubStorage();
            await mount("phone");
            viewport.resizeTo("middle");
            await flush();
            const head = document.querySelector(".cp-preview-head");
            assert.equal(
                document.querySelector(".cp-head-toggles")?.parentElement,
                head,
                "the pills go back inside the title row, not merely above the stage",
            );
        });
    });

    it("stays inert on a page with no viewer", async () => {
        stubStorage();
        stubViewport("middle");
        document.body.innerHTML = `<cp-viewer-drawers></cp-viewer-drawers>`;
        await flush();
        assert.ok(true, "connecting without a .cp-viewer must not throw");
    });

    it("unbinds everything when removed", async () => {
        const store = stubStorage();
        const viewer = await mount("middle");
        document.querySelector("cp-viewer-drawers")?.remove();
        toggle("cp-controls-toggle").click();
        assert.equal(viewer.classList.contains("cp-controls-open"), false);
        assert.equal(store.get("cp-fold:compose-m3.cp-controls-toggle"), null);
    });
});
